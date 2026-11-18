import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import imageio_ffmpeg
from PIL import Image

class RecoverFrameTests(unittest.TestCase):
    def test_recovers_original_timeline_frame_without_ocr(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-y', '-loglevel', 'error', '-f', 'lavfi',
                            '-i', 'color=c=blue:s=320x180:d=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
                            str(root / 'video.mp4')], check=True, capture_output=True)
            subprocess.run([sys.executable, 'scripts/extract_slide_frame.py', '--directory', directory, '--index', '1'],
                           check=True, capture_output=True)
            image = root / 'frames' / 'frame-000002.jpg'
            with Image.open(image) as frame:
                self.assertEqual((1280, 720), frame.size)
                self.assertGreater(frame.getpixel((0, 0))[2], 200)
            self.assertFalse((root / 'local-ocr').exists())

if __name__ == '__main__': unittest.main()
