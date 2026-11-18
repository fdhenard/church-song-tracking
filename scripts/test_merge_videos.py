import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import imageio_ffmpeg
import merge_videos as worker


class MergeTests(unittest.TestCase):
    def clip(self, file, color, size='320x180', audio=False):
        args = [imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-f', 'lavfi', '-i', f'color={color}:s={size}:r=30:d=0.5']
        if audio:
            args += ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=0.5', '-c:a', 'aac']
        args += ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p', '-shortest', str(file)]
        subprocess.run(args, check=True, capture_output=True)

    def setup_merge(self, root, different=False):
        first, second = root/'1', root/'2'
        first.mkdir(); second.mkdir()
        self.clip(first/'video.mp4', 'blue')
        self.clip(second/'video.mp4', 'red', '640x360' if different else '320x180', different)
        (first/'metadata.json').write_text(json.dumps({'title': 'Original', 'duration': .5}))
        (first/'frames').mkdir()
        (first/'frames'/'old.jpg').write_text('old OCR evidence')
        (first/'result.json').write_text('old result')
        plan = {'title':'Livestream 08-09-26', 'allow_conversion':True, 'sources':[
            {'selection_id':1,'url':'https://vimeo.com/123','title':'Livestream 08-09-26','order':1,'path':str(first/'video.mp4')},
            {'selection_id':2,'url':'https://vimeo.com/456','title':'Livestream 08-09-26','order':2,'path':str(second/'video.mp4')}]}
        path = first/'merge-plan.json'
        path.write_text(json.dumps(plan))
        return first, second, path

    def test_lossless_order_offsets_old_ocr_and_repeat_after_interruption(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            first, second, plan = self.setup_merge(Path(tmp))
            worker.merge(plan)
            metadata = json.loads((first/'metadata.json').read_text())
            self.assertEqual('stream-copy', metadata['merge_method'])
            self.assertEqual([1,2], [s['selection_id'] for s in metadata['sources']])
            self.assertAlmostEqual(.5, metadata['sources'][1]['merged_start'], places=1)
            self.assertTrue((first/'pre-merge-ocr'/'frames'/'old.jpg').exists())
            self.assertFalse((first/'frames').exists())
            self.assertTrue((first/'source-original.mp4').exists())
            self.assertTrue((second/'video.mp4').exists())
            r=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-v','error','-ss','0.7','-i',str(first/'video.mp4'),'-frames:v','1','-vf','scale=1:1','-pix_fmt','rgb24','-f','rawvideo','pipe:1'],capture_output=True,check=True)
            self.assertGreater(r.stdout[0],200)
            self.assertLess(r.stdout[2],30)
            worker.merge(plan)
            self.assertAlmostEqual(1, json.loads((first/'metadata.json').read_text())['duration'], places=1)

    def test_different_formats_and_missing_audio(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            first, second, plan = self.setup_merge(Path(tmp), True)
            worker.merge(plan)
            metadata=json.loads((first/'metadata.json').read_text())
            self.assertEqual('converted', metadata['merge_method'])
            self.assertEqual([1], metadata['audio_missing_sources'])
            self.assertIsNotNone(worker.probe(imageio_ffmpeg.get_ffmpeg_exe(), first/'video.mp4')['audio'])

    def test_insufficient_space_keeps_sources_and_original_metadata(self):
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            first, second, plan = self.setup_merge(Path(tmp))
            from collections import namedtuple
            usage=namedtuple('Usage','total used free')(1,1,0)
            with patch('merge_videos.shutil.disk_usage', return_value=usage):
                with self.assertRaisesRegex(RuntimeError,'Not enough free disk space'):
                    worker.merge(plan)
            self.assertEqual('Original',json.loads((first/'metadata.json').read_text())['title'])
            self.assertTrue((first/'frames'/'old.jpg').exists())
            self.assertTrue((second/'video.mp4').exists())
