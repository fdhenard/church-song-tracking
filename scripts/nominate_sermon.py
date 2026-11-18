"""Read one user-nominated source frame with local OCR; never calls an AI API."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import imageio_ffmpeg
from local_ocr import read_frames
from ocr_suggestions import propose_details, timestamp


def sermon_from_frame(text, index):
    details = propose_details([{'index': index, 'ocr': text}])['sermon']
    person = re.search(r'\b(?:Pastor|Preacher|Speaker)\s*:?\s+([^\n|]{2,100})', text, re.I)
    if person:
        details['preacher'] = person.group(1).strip(' .–-')
    reference = re.search(r'\b((?:[1-3]\s+)?[A-Za-z]+\s+\d+(?::\d+(?:[-–]\d+)?)?)\b', text)
    if reference and not re.search(r'CCLI|Public Domain|copyright', text, re.I):
        details.setdefault('sermon-reference', reference.group(1))
        details.setdefault('sermon-scripture', reference.group(1))
        heading = text[:reference.start()].strip(' \n-–—')
        heading = re.sub(r'\b(?:Pastor|Preacher|Speaker)\b.*', '', heading, flags=re.I).strip()
        if heading and len(heading) <= 180:
            details.setdefault('sermon-title', ' '.join(heading.split()).strip(' "“”'))
    details['sermon-evidence-index'] = index
    details['sermon-time'] = timestamp(index)
    details['sermon-suggestion-notes'] = 'User-nominated sermon frame. Verify the title, series, scripture, and preacher against the image; ambiguous or missing fields require manual entry.'
    return details


def nominate(directory, index, kind="sermon_title"):
    metadata = json.loads((directory / 'metadata.json').read_text())
    duration = metadata.get('duration')
    if index < 0 or index > 1000000 or (duration and index * 3 >= duration):
        raise ValueError('Frame is outside the downloaded video.')
    video = next((directory / ('video.' + ext) for ext in ('mp4', 'webm', 'mkv', 'mov') if (directory / ('video.' + ext)).is_file()), None)
    if video is None:
        raise ValueError('Download the video first.')
    frame = directory / 'frames' / f'frame-{index + 1:06}.jpg'
    frame.parent.mkdir(exist_ok=True)
    if not frame.is_file():
        temporary = frame.with_name(frame.stem + '-nominated.jpg')
        try:
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error', '-y',
                            '-ss', str(index * 3), '-i', str(video), '-frames:v', '1', '-vf', 'scale=1280:-2',
                            '-q:v', '3', str(temporary)], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                           check=True, timeout=60)
            if not temporary.is_file():
                raise ValueError('The selected frame could not be extracted.')
            temporary.replace(frame)
        finally:
            temporary.unlink(missing_ok=True)
    text = read_frames([(index, frame)], directory)[index]['text']
    details = (propose_details([{'index': index, 'ocr': text}])['songs'] if kind == 'song_title' else sermon_from_frame(text, index))
    return {'frame': {'index': index, 'kind': kind, 'ocr': text, 'user-nominated': True}, 'details': details}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--index', type=int, required=True)
    parser.add_argument('--kind', choices=['sermon_title', 'song_title'], default='sermon_title')
    args = parser.parse_args()
    try:
        print(json.dumps(nominate(args.directory, args.index, args.kind)))
    except Exception:
        raise SystemExit('Could not read the selected frame with local OCR. Check that the video and local OCR tools are available.')
