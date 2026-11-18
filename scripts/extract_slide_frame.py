"""Recover one slide frame from a retained local video, without OCR or network access."""
import argparse
from pathlib import Path
import subprocess
import imageio_ffmpeg

parser = argparse.ArgumentParser()
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--index', type=int, required=True)
args = parser.parse_args()
if not 0 <= args.index <= 1000000:
    raise SystemExit('Invalid frame index')
video = next((args.directory / ('video.' + ext) for ext in ('mp4', 'webm', 'mkv', 'mov')
              if (args.directory / ('video.' + ext)).is_file()), None)
if video is None:
    raise SystemExit('No retained video')
frame = args.directory / 'frames' / f'frame-{args.index + 1:06}.jpg'
frame.parent.mkdir(exist_ok=True)
temporary = frame.with_name(frame.stem + '-archive.tmp.jpg')
try:
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error', '-y',
                    '-ss', str(args.index * 3), '-i', str(video), '-frames:v', '1', '-vf', 'scale=1280:-2',
                    '-q:v', '3', str(temporary)], check=True, timeout=60)
    if not temporary.is_file() or not temporary.stat().st_size:
        raise SystemExit('No frame at the selected time')
    temporary.replace(frame)
finally:
    temporary.unlink(missing_ok=True)
