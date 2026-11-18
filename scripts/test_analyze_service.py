import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import analyze_service as worker


def record(index, title='', author='', kind='song_title'):
    return dict(index=index, kind=kind, song_title=title, song_author=author,
                ocr=title + ' / ' + author, preacher='', song_leader='', sermon_title='',
                sermon_series='', service_date='', uncertainty='')


class AnalysisTests(unittest.TestCase):
    def test_titles_deduplicate_but_reprises_remain(self):
        result = worker.build_draft([record(0, 'Song A', 'Writer'), record(1, 'Song A', 'Writer'),
                                     record(4, 'Song B', 'Other'), record(7, 'Song A', 'Writer'),
                                     record(9, 'Guess from lyrics', '', 'lyrics')], 'Livestream')
        self.assertEqual(['Song A', 'Song B', 'Song A'], [s['title'] for s in result['songs']])
        self.assertEqual('', result['songs'][0]['start'])
        self.assertEqual('0:21', result['songs'][2]['slide'])
        self.assertEqual('', result['preacher'])

    def test_openai_payload_and_missing_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'frame.jpg'
            path.write_bytes(b'example image bytes')
            def response(request, timeout):
                body = json.loads(request.data)
                self.assertFalse(body['store'])
                self.assertEqual('input_image', body['input'][0]['content'][2]['type'])
                self.assertEqual('json_schema', body['text']['format']['type'])
                payload = dict(status='completed', output=[{'content': [{'type': 'output_text', 'text': json.dumps({'frames': []})}]}])
                return io.BytesIO(json.dumps(payload).encode())
            with patch('urllib.request.urlopen', side_effect=response):
                with self.assertRaisesRegex(RuntimeError, 'missing or mismatched'):
                    worker.recognize([(0, path)], 'test-key', 'test-model')

    def test_video_to_review_result_without_library_writes(self):
        import imageio_ffmpeg
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error',
                            '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:d=6', '-c:v', 'mpeg4',
                            str(root / 'video.mp4')], check=True)
            (root / 'metadata.json').write_text(json.dumps({'title': 'Test Livestream', 'duration': 6}))
            with patch.dict(os.environ, {'CHURCH_SONG_TRACKING_OPENAI_API_KEY': 'test-key'}), \
                 patch.object(worker, 'recognize', side_effect=lambda batch, key, model: [record(i, 'Test Song', 'Writer') for i, _ in batch]), \
                 contextlib.redirect_stdout(io.StringIO()):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory, model='test-model', no_local_ocr=True, use_openai=True))
            result = json.loads((root / 'result.json').read_text())
            self.assertEqual('Test Livestream', result['vimeo_video_title'])
            self.assertEqual('Test Song', result['draft']['songs'][0]['title'])
            self.assertEqual(1, len(result['evidence']))
            self.assertEqual(1, result['deduplication']['duplicates_skipped'])
            self.assertEqual(3, result['evidence'][0]['last_seen_seconds'])
            self.assertTrue((root / 'frames' / 'frame-000001.jpg').exists())

    def test_download_errors_give_specific_next_steps_without_raw_details(self):
        cases = [
            ('No video formats found!', 'another Livestream upload'),
            ('The web client only works when logged-in', 'does not share your browser login'),
            ('HTTP Error 401: Unauthorized', 'connection-security check'),
            ('HTTP Error 404: Not Found', 'correct video link'),
            ('HTTP Error 403: Forbidden', 'automated-request block'),
            ('HTTP Error 429: Too Many Requests', 'Wait a few minutes'),
            ('Connection timed out', 'Check your connection'),
            ('No space left on device', 'disk is full'),
            ('Permission denied', 'cannot write its local files'),
            ('Something unknown', 'cause could not be identified'),
        ]
        for raw, expected in cases:
            with self.subTest(raw=raw):
                message = worker.download_error_message(Exception(raw + ' https://media.example/?token=secret'))
                self.assertIn(expected, message)
                self.assertNotIn('secret', message)
                self.assertNotIn('media.example', message)

    def test_processing_errors_do_not_claim_download_permissions_changed(self):
        import errno
        self.assertIn('disk is full', worker.failure_message(OSError(errno.ENOSPC, 'secret path')))
        self.assertIn('FFmpeg', worker.failure_message(subprocess.CalledProcessError(1, ['secret command'])))
        self.assertIn('connection to OpenAI', worker.failure_message(TimeoutError('secret token')))
        self.assertNotIn('secret', worker.failure_message(Exception('secret token')))

    def test_download_only_needs_no_api_key_and_never_analyzes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'video.mp4').write_bytes(b'cached-download')
            (root / 'metadata.json').write_text(json.dumps({'title': 'Livestream'}))
            output = io.StringIO()
            with patch.dict(os.environ, {}, clear=True), \
                 patch.object(worker, 'recognize') as recognize, \
                 patch.object(worker.subprocess, 'run') as extract_frames, \
                 contextlib.redirect_stdout(output):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory,
                                              model='unused', download_only=True))
            recognize.assert_not_called()
            extract_frames.assert_not_called()
            self.assertEqual('downloaded', json.loads(output.getvalue())['status'])
            self.assertFalse((root / 'result.json').exists())

    def test_analysis_only_does_not_download_if_cached_file_is_missing(self):
        with tempfile.TemporaryDirectory() as directory, \
             patch.dict(os.environ, {'CHURCH_SONG_TRACKING_OPENAI_API_KEY': 'test-key'}), \
             patch('yt_dlp.YoutubeDL') as downloader:
            with self.assertRaisesRegex(worker.ProcessingError, 'downloaded video is missing'):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory,
                                              model='unused', analyze_only=True))
            downloader.assert_not_called()

    def test_speed_and_remaining_time_reporting(self):
        message = worker.download_progress_message({'total_bytes': 100_000_000,
                                                    'downloaded_bytes': 25_000_000,
                                                    'speed': 2_500_000, 'eta': 30})
        self.assertIn('25%', message)
        self.assertIn('2.50 MB/s', message)
        self.assertIn('~0m 30s remaining', message)
        estimate = worker.download_progress_message({'total_bytes_estimate': 100_000_000,
                                                     'downloaded_bytes': 50_000_000, 'speed': 1_000_000})
        self.assertIn('~50%', estimate)
        self.assertIn('~0m 50s remaining', estimate)
        unknown = worker.download_progress_message({'speed': float('nan'), 'eta': float('inf')})
        self.assertIn('speed calculating', unknown)
        self.assertIn('time remaining calculating', unknown)
        self.assertIn('finalizing', worker.download_progress_message({'status': 'finished'}))

    def test_downloader_uses_four_segments_and_reports_progress(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            captured = {}
            class Downloader:
                def __init__(self, options): captured.update(options)
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def extract_info(self, url, download):
                    captured['progress_hooks'][0]({'downloaded_bytes': 5_000_000, 'total_bytes': 10_000_000,
                                                   'speed': 1_000_000, 'eta': 5})
                    (root / 'video.mp4').write_bytes(b'test-video')
                    return {'title': 'Livestream'}
                def prepare_filename(self, info): return str(root / 'video.mp4')
            output = io.StringIO()
            with patch('yt_dlp.YoutubeDL', Downloader), contextlib.redirect_stdout(output):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory,
                                              model='unused', download_only=True))
            self.assertEqual(4, captured['concurrent_fragment_downloads'])
            self.assertFalse(captured['skip_unavailable_fragments'])
            self.assertIn('1.00 MB/s', output.getvalue())
            self.assertIn('remaining', output.getvalue())

    def test_session_fallback_is_only_used_for_access_errors(self):
        import yt_dlp
        from unittest.mock import MagicMock
        for failure, expected_calls in [('HTTP Error 401', 2), ('HTTP Error 429', 1)]:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                cookie = Path(directory) / 'session.cookies'
                cookie.write_text('secret')
                public = MagicMock()
                public.extract_info.side_effect = yt_dlp.utils.DownloadError(failure)
                authenticated = MagicMock()
                authenticated.extract_info.return_value = {'title': 'Livestream'}
                authenticated.prepare_filename.return_value = 'video.mp4'
                contexts = [MagicMock(), MagicMock()]
                contexts[0].__enter__.return_value = public
                contexts[1].__enter__.return_value = authenticated
                with patch('yt_dlp.YoutubeDL', side_effect=contexts) as factory, contextlib.redirect_stdout(io.StringIO()):
                    if expected_calls == 2:
                        worker.download_vimeo(yt_dlp, {}, 'https://vimeo.com/123', str(cookie))
                        self.assertEqual(str(cookie), factory.call_args.args[0]['cookiefile'])
                        authenticated.extract_info.assert_called_once_with('https://vimeo.com/123', download=True)
                    else:
                        with self.assertRaises(worker.ProcessingError):
                            worker.download_vimeo(yt_dlp, {}, 'https://vimeo.com/123', str(cookie))
                    self.assertNotIn('cookiefile', factory.call_args_list[0].args[0])
                    self.assertEqual(expected_calls, factory.call_count)

    def test_daily_limit_and_credit_exhaustion_do_not_retry(self):
        from urllib.error import HTTPError
        from email.message import Message
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'frame.jpg'; path.write_bytes(b'image')
            for code, kind, text, expected in [
                ('rate_limit_exceeded', 'requests', 'requests per day (RPD): Limit 50, Used 50 org-secret', 'daily request limit reached (limit 50)'),
                ('credit_balance_exhausted', 'insufficient_quota', 'no credits org-secret', 'credits or usage quota are exhausted')]:
                headers = Message(); headers['Retry-After'] = '1728'
                error = HTTPError('https://api.openai.com/v1/responses', 429, 'Too many requests', headers,
                                  io.BytesIO(json.dumps({'error': {'code': code, 'type': kind, 'message': text}}).encode()))
                with patch('urllib.request.urlopen', side_effect=error) as request, patch.object(worker.time, 'sleep') as sleep:
                    with self.assertRaises(worker.ProcessingError) as failure:
                        worker.recognize([(0, path)], 'secret-key', 'test-model')
                    self.assertIn(expected, str(failure.exception))
                    self.assertNotIn('secret', str(failure.exception))
                    if code == 'rate_limit_exceeded': self.assertIn('29 minutes', str(failure.exception))
                    self.assertEqual(1, request.call_count)
                    sleep.assert_not_called()

    def test_transient_rate_limit_honors_retry_after(self):
        from urllib.error import HTTPError
        from email.message import Message
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'frame.jpg'; path.write_bytes(b'image')
            headers = Message(); headers['Retry-After'] = '2'
            error = HTTPError('https://api.openai.com/v1/responses', 429, 'Rate limit', headers,
                              io.BytesIO(json.dumps({'error': {'code': 'rate_limit_exceeded', 'message': 'requests per minute'}}).encode()))
            response = io.BytesIO(json.dumps({'status': 'completed', 'output': [{'content': [
                {'type': 'output_text', 'text': json.dumps({'frames': [record(0)]})}]}]}).encode())
            with patch('urllib.request.urlopen', side_effect=[error, response]), patch.object(worker.time, 'sleep') as sleep:
                self.assertEqual(1, len(worker.recognize([(0, path)], 'secret-key', 'test-model')))
                sleep.assert_called_once_with(2)

    def test_duplicate_filter_keeps_small_text_changes_and_reappearing_slides(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a = Image.new('RGB', (640, 360), 'white')
            ImageDraw.Draw(a).text((50, 100), 'Song A / Original Author', fill='black')
            b = a.copy()
            ImageDraw.Draw(b).rectangle((400, 300, 406, 306), fill='black')
            frames = []
            for index, image in enumerate([a, a, a, b, a]):
                path = root / f'{index}.png'; image.save(path); frames.append(path)
            kept, groups = worker.deduplicate_frames(frames)
            self.assertEqual([0, 3, 4], [index for index, _ in kept])
            self.assertEqual([1, 2], groups[0]['duplicates'])
            self.assertTrue(worker.same_frame(Image.new('RGB', (640, 360), (100, 100, 100)),
                                              Image.new('RGB', (640, 360), (101, 101, 101))))
            self.assertFalse(worker.same_frame(a, b))

    def test_duplicate_filter_reuses_legacy_and_sparse_caches_with_original_indexes(self):
        from PIL import Image
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frames = []
            for index, color in enumerate(['white', 'white', 'black']):
                path = root / f'{index}.png'; Image.new('RGB', (640, 360), color).save(path); frames.append(path)
            model_tag = hashlib.sha256(b'test-model').hexdigest()[:12]
            legacy = root / f'recognition-{model_tag}-000000.json'
            legacy.write_text(json.dumps([record(0, 'Original Song', 'Author'), record(1, 'Original Song', 'Author')]))
            original_cache = legacy.read_bytes()
            with patch.object(worker, 'recognize', side_effect=lambda batch, key, model: [record(i, 'New Song', 'Author') for i, _ in batch]) as recognize, \
                 contextlib.redirect_stdout(io.StringIO()):
                rows, stats = worker.analyze_frames(frames, root, 'unused', 'test-model')
            self.assertEqual([2], [i for i, _ in recognize.call_args.args[0]])
            self.assertEqual([0, 2], [row['index'] for row in rows])
            self.assertEqual(6, rows[1]['first_seen_seconds'])
            self.assertEqual(3, rows[0]['last_seen_seconds'])
            self.assertEqual(1, stats['api_requests'])
            self.assertEqual(original_cache, legacy.read_bytes())
            with patch.object(worker, 'recognize') as recognize, contextlib.redirect_stdout(io.StringIO()):
                _, stats = worker.analyze_frames(frames, root, 'unused', 'test-model')
            recognize.assert_not_called()
            self.assertEqual(0, stats['api_requests'])

    def test_local_ocr_filters_repeated_text_and_preserves_author_changes_and_reappearances(self):
        kept = [(i, Path(f'{i}.jpg')) for i in range(6)]
        groups = [{'index': i, 'duplicates': []} for i in range(6)]
        texts = ['Amazing Grace / John Newton', 'AMAZING GRACE  John Newton',
                 'Other Song / Author A', '', 'Other Song / Author A', 'Other Song / Author B']
        ocr = {i: {'text': text} for i, text in enumerate(texts)}
        selected, selected_groups, no_text, duplicates = worker.filter_ocr_frames(kept, groups, ocr)
        self.assertEqual([0, 2, 4, 5], [index for index, _ in selected])
        self.assertEqual([1], selected_groups[0]['duplicates'])
        self.assertEqual([3], no_text)
        self.assertEqual([1], duplicates)

    def test_local_ocr_reuses_existing_api_results_without_new_requests(self):
        from PIL import Image
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frames = []
            for index, color in enumerate(['white', 'gray', 'black']):
                path = root / f'{index}.png'; Image.new('RGB', (640, 360), color).save(path); frames.append(path)
            tag = hashlib.sha256(b'test-model').hexdigest()[:12]
            (root / f'recognition-{tag}-000000.json').write_text(json.dumps([record(0, 'Song', 'Writer')]))
            ocr = {0: {'text': 'Song / Writer'}, 1: {'text': 'Song / Writer'}, 2: {'text': ''}}
            with patch('local_ocr.read_frames', return_value=ocr), patch.object(worker, 'recognize') as recognize, \
                 contextlib.redirect_stdout(io.StringIO()):
                rows, stats = worker.analyze_frames(frames, root, 'unused', 'test-model', use_local_ocr=True)
            recognize.assert_not_called()
            self.assertEqual(1, stats['text_duplicates_skipped'])
            self.assertEqual(1, stats['no_text_skipped'])
            self.assertEqual(0, stats['api_requests'])
            self.assertEqual(3, rows[0]['last_seen_seconds'])

    def test_default_run_has_no_key_and_cannot_call_openai(self):
        import imageio_ffmpeg
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error',
                            '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:d=6', '-c:v', 'mpeg4',
                            str(root / 'video.mp4')], check=True)
            (root / 'metadata.json').write_text(json.dumps({'title': 'Livestream', 'duration': 6}))
            with patch.dict(os.environ, {}, clear=True), \
                 patch.object(worker, 'api_key') as api_key, \
                 patch.object(worker, 'recognize') as recognize, \
                 patch.object(worker, 'load_recognition_cache') as api_cache, \
                 patch('local_ocr.read_frames', return_value={0: {'text': 'Amazing Grace / John Newton'}}), \
                 contextlib.redirect_stdout(io.StringIO()):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory,
                                              model='unused', analyze_only=True))
            api_key.assert_not_called()
            recognize.assert_not_called()
            api_cache.assert_not_called()
            result = json.loads((root / 'result.json').read_text())
            self.assertTrue(result['local_only'])
            self.assertEqual(0, result['deduplication']['api_requests'])
            self.assertEqual('apple-vision-local-ocr', result['model'])
            self.assertEqual('Amazing Grace / John Newton', result['evidence'][0]['ocr'])
            self.assertEqual('ocr_candidate', result['evidence'][0]['kind'])
            self.assertEqual([], result['draft']['songs'])
            self.assertEqual('', result['draft']['preacher'])

    def test_pre_ocr_reuse_handles_codec_noise_but_not_changed_author_text(self):
        from PIL import Image, ImageDraw
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a = Image.new('RGB', (640, 360), (240, 240, 240))
            noisy = Image.new('RGB', (640, 360), (242, 242, 242))
            b = a.copy(); ImageDraw.Draw(b).rectangle((400, 300, 406, 306), fill='black')
            frames = []
            for index, image in enumerate([a, noisy, b, a]):
                path = root / f'{index}.png'; image.save(path); frames.append((index, path))
            unique, aliases = worker.plan_ocr_reuse(frames)
            self.assertEqual([0, 2], [i for i, _ in unique])
            self.assertEqual({0: 0, 1: 0, 2: 2, 3: 0}, aliases)
            self.assertFalse(worker.same_slide(a, b))

    def test_repeated_slide_is_read_once_but_all_appearances_are_retained(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = []
            for index, color in enumerate(['white', 'black', 'white']):
                path = root / f'{index}.png'; Image.new('RGB', (640, 360), color).save(path); paths.append(path)
            with patch('local_ocr.read_frames', return_value={0: {'text': 'Song A / Writer'}, 1: {'text': 'Song B / Writer'}}) as ocr, \
                 patch.object(worker, 'recognize') as api, contextlib.redirect_stdout(io.StringIO()):
                rows, stats = worker.analyze_frames(paths, root, None, 'unused', local_only=True)
            self.assertEqual([0, 1], [i for i, _ in ocr.call_args.args[0]])
            api.assert_not_called()
            self.assertEqual(2, len(rows))
            self.assertEqual([0, 2], [r['index'] for r in rows[0]['occurrences']])
            self.assertEqual(6, rows[0]['occurrences'][1]['first_seen_seconds'])
            self.assertEqual(1, stats['pre_ocr_reused_frames'])

    def test_trim_limits_ocr_and_preserves_original_timestamps(self):
        import imageio_ffmpeg
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), '-hide_banner', '-loglevel', 'error',
                            '-f', 'lavfi', '-i', 'color=c=blue:s=320x180:d=9', '-c:v', 'mpeg4',
                            str(root / 'video.mp4')], check=True)
            (root / 'metadata.json').write_text(json.dumps({'title': 'Livestream', 'duration': 9}))
            # Old full-video frames must not leak into a shorter OCR range.
            frame_dir = root / 'frames'; frame_dir.mkdir()
            from PIL import Image
            for n in (1, 2, 3): Image.new('RGB', (320, 180), 'blue').save(frame_dir / f'frame-{n:06}.jpg')
            with patch.dict(os.environ, {}, clear=True), patch.object(worker, 'recognize') as api, \
                 patch('local_ocr.read_frames', return_value={1: {'text': 'Selected slide'}}) as ocr, \
                 contextlib.redirect_stdout(io.StringIO()):
                worker.run(argparse.Namespace(url='https://vimeo.com/123', directory=directory,
                                              model='unused', analyze_only=True, trim_start=3, trim_end=6))
            api.assert_not_called()
            self.assertEqual([1], [index for index, _ in ocr.call_args.args[0]])
            result = json.loads((root / 'result.json').read_text())
            self.assertEqual(1, result['evidence'][0]['index'])
            self.assertEqual(3, result['evidence'][0]['first_seen_seconds'])
            self.assertEqual({'start': 3, 'end': 6}, result['trim'])
            self.assertTrue((root / 'video.mp4').exists())

    def test_missing_key_fails_before_download(self):
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'legacy-key', 'OPENAI_API_KEY_FILE': '/unused/key-file'}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'CHURCH_SONG_TRACKING_OPENAI_API_KEY'):
                worker.api_key()


if __name__ == '__main__':
    unittest.main()
