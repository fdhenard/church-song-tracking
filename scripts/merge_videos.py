"""Join explicitly ordered local service recordings. Never writes H2 or deletes sources."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import imageio_ffmpeg


def emit(message, status='merging'):
    print(json.dumps({'status': status, 'message': message}), flush=True)


def probe(ffmpeg, path):
    result = subprocess.run([ffmpeg, '-hide_banner', '-nostdin', '-i', str(path)], capture_output=True, text=True, timeout=30)
    text = result.stderr
    duration = re.search(r'Duration: (\d+):(\d+):(\d+\.\d+)', text)
    video = re.search(r'Stream #[^\n]+Video: ([^\n]+)', text)
    audio = re.search(r'Stream #[^\n]+Audio: ([^\n]+)', text)
    size = re.search(r'(\d{2,5})x(\d{2,5})', video[1] if video else '')
    if not duration or not video or not size:
        raise RuntimeError('A source recording is incomplete or has no readable video stream. Download it again before merging.')
    return {'duration': int(duration[1])*3600+int(duration[2])*60+float(duration[3]),
            'video': video[1], 'audio': audio[1] if audio else None, 'width': int(size[1]), 'height': int(size[2])}


def signature(info):
    # Bitrate may vary; codec/profile, dimensions, pixel format, fps and timebase must match.
    return (re.sub(r', \d+ kb/s', '', info['video']).replace(' (default)', ''),
            re.sub(r', \d+ kb/s', '', info['audio'] or '').replace(' (default)', ''))


def command(ffmpeg, args, directory, total=None):
    with (directory / 'merge-ffmpeg.log').open('a') as log:
        process = subprocess.Popen([ffmpeg, '-hide_banner', '-nostdin', '-y', '-loglevel', 'warning',
                                    '-progress', 'pipe:1', *args], stdout=subprocess.PIPE, stderr=log, text=True)
        last = -1
        for line in process.stdout:
            if total and line.startswith('out_time_us='):
                try:
                    percent = min(99, int(int(line.split('=', 1)[1])/1_000_000/total*100))
                    if percent // 5 > last // 5:
                        emit(f'Merging recordings · {percent}%')
                        last = percent
                except ValueError:
                    pass
        process.stdout.close()
        if process.wait():
            raise RuntimeError('Video merging or validation failed. Source files were kept. Check local merge-ffmpeg.log and available disk space, then retry.')


def merge(manifest_path):
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    manifest = json.loads(Path(manifest_path).read_text())
    directory = Path(manifest_path).parent
    sources = manifest['sources']
    if not 2 <= len(sources) <= 8:
        raise RuntimeError('Choose between two and eight recordings.')
    # Preserve the first source before replacing the parent's video. A same-volume hard link avoids a large extra copy.
    first = Path(sources[0]['path'])
    saved = directory / ('source-original' + first.suffix)
    if not saved.exists():
        try:
            os.link(first, saved)
        except OSError:
            temporary_copy = directory / 'source-original-copy.tmp'
            try:
                shutil.copy2(first, temporary_copy)
                os.replace(temporary_copy, saved)
            finally:
                temporary_copy.unlink(missing_ok=True)
    paths = [saved] + [Path(s['path']) for s in sources[1:]]
    infos = [probe(ffmpeg, path) for path in paths]
    total = sum(info['duration'] for info in infos)
    required = int(sum(path.stat().st_size for path in paths)*1.5) + 256*1024*1024
    if shutil.disk_usage(directory).free < required:
        raise RuntimeError(f'Not enough free disk space to merge safely. Free at least {required/1_000_000_000:.1f} GB before retrying. Source files were kept.')
    compatible = not manifest.get('force_conversion') and all(signature(info) == signature(infos[0]) for info in infos)
    if not compatible and not manifest.get('allow_conversion'):
        raise RuntimeError('These recordings need conversion to a common format. Open the merge screen and allow conversion, then retry.')
    output = directory / 'merged-output.mp4'
    # Local generated filenames avoid concat-manifest escaping and unsafe arbitrary paths.
    with tempfile.TemporaryDirectory(prefix='merge-input-', dir=directory) as temporary:
        staging = Path(temporary)
        for index, path in enumerate(paths):
            os.symlink(path.resolve(), staging / f'part-{index}{path.suffix}')
        if compatible:
            emit('Compatible recordings · joining without re-encoding')
            listing = staging / 'parts.txt'
            listing.write_text(''.join(f"file 'part-{i}{p.suffix}'\nduration {info['duration']:.6f}\n" for i, (p, info) in enumerate(zip(paths, infos))))
            command(ffmpeg, ['-f', 'concat', '-safe', '1', '-i', str(listing), '-map', '0:v:0', '-map', '0:a:0?', '-c', 'copy', '-movflags', '+faststart', str(output)], directory, total)
        else:
            emit('Converting recordings to a common format · this may take longer')
            arguments, filters, streams = [], [], []
            audio = any(info['audio'] for info in infos)
            for i, (path, info) in enumerate(zip(paths, infos)):
                arguments += ['-i', str(path)]
                filters.append(f'[{i}:v:0]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p,setpts=PTS-STARTPTS[v{i}]')
                streams.append(f'[v{i}]')
                if audio:
                    if info['audio']:
                        filters.append(f'[{i}:a:0]aresample=48000,aformat=channel_layouts=stereo,apad,atrim=duration={info["duration"]},asetpts=PTS-STARTPTS[a{i}]')
                    else:
                        filters.append(f'anullsrc=r=48000:cl=stereo,atrim=duration={info["duration"]},asetpts=PTS-STARTPTS[a{i}]')
                    streams.append(f'[a{i}]')
            filters.append(''.join(streams)+f'concat=n={len(paths)}:v=1:a={int(audio)}[v]'+('[a]' if audio else ''))
            arguments += ['-filter_complex_threads', '2', '-filter_complex', ';'.join(filters), '-map', '[v]']
            if audio:
                arguments += ['-map', '[a]', '-c:a', 'aac', '-b:a', '128k']
            arguments += ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-threads', '2', '-movflags', '+faststart', str(output)]
            command(ffmpeg, arguments, directory, total)
    emit('Validating the combined recording · originals remain available')
    actual = probe(ffmpeg, output)
    if abs(actual['duration'] - total) > max(1, len(paths)*0.25):
        raise RuntimeError('The combined recording has an unexpected length. Source files were kept; the merged video was not activated.')
    command(ffmpeg, ['-xerror', '-i', str(output), '-threads', '2', '-f', 'null', '-'], directory)
    offset = 0.0
    entries = []
    for source, info in zip(sources, infos):
        entries.append({k: source[k] for k in ['selection_id', 'url', 'title', 'order']})
        entries[-1].update(duration=info['duration'], merged_start=offset)
        offset += info['duration']
    metadata = {'title': manifest['title'], 'duration': actual['duration'], 'webpage_url': sources[0]['url'],
                'merged': True, 'sources': entries, 'merge_method': 'stream-copy' if compatible else 'converted',
                'audio_missing_sources': [s['selection_id'] for s, info in zip(sources, infos) if not info['audio']]}
    # Only expose a completed video after all validation. Original first part remains linked separately.
    # Old sampled frames must never be mistaken for frames on the new combined timeline.
    archive = directory / 'pre-merge-ocr'
    for item in list(directory.iterdir()):
        if item.name in ('frames', 'result.json', 'deduplication.json', 'deduplication-local.json') or item.name.startswith('recognition-'):
            archive.mkdir(exist_ok=True)
            target = archive / item.name
            if not target.exists():
                os.replace(item, target)
    os.replace(output, directory / 'video.mp4')
    if first.name != 'video.mp4' and first.exists():
        first.unlink()
    temporary_metadata = directory / 'merged-metadata.tmp'
    temporary_metadata.write_text(json.dumps(metadata))
    os.replace(temporary_metadata, directory / 'metadata.json')
    emit('Merged video ready · choose an OCR range, then review as one service', 'downloaded')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args()
    try:
        merge(args.manifest)
    except Exception as error:
        message = str(error) if isinstance(error, RuntimeError) else 'Could not merge the recordings. Check disk space and local merge-ffmpeg.log. Source files were kept.'
        emit(message, 'failed')
        sys.exit(1)
