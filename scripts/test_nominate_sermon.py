import unittest
from nominate_sermon import sermon_from_frame, nominate
from unittest.mock import patch
from pathlib import Path
import tempfile, json

class NominationTests(unittest.TestCase):
    def test_nominated_unquoted_title_and_explicit_pastor(self):
        result = sermon_from_frame('FIELD NOTES from Creation\n- PSALM 104 - Pastor Justin Waples', 633)
        self.assertEqual('FIELD NOTES from Creation', result['sermon-title'])
        self.assertEqual('Justin Waples', result['preacher'])
        self.assertEqual('PSALM 104', result['sermon-reference'])
        self.assertEqual('31:39', result['sermon-time'])
        self.assertNotIn('sermon-series', result)
    def test_song_nomination_uses_selected_image_and_local_credit_parser(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            (directory / 'metadata.json').write_text(json.dumps({'duration': 100}))
            (directory / 'video.mp4').touch()
            (directory / 'frames').mkdir()
            (directory / 'frames' / 'frame-000011.jpg').touch()
            text = 'A Song\n"A Song" by A Writer. Public Domain. CCLI #1'
            with patch('nominate_sermon.read_frames', return_value={10: {'text': text}}) as reader:
                result = nominate(directory, 10, 'song_title')
            self.assertEqual('song_title', result['frame']['kind'])
            self.assertEqual(10, result['frame']['index'])
            self.assertEqual('A Song', result['details'][0]['title'])
            self.assertEqual('A Writer', result['details'][0]['author'])
            reader.assert_called_once_with([(10, directory / 'frames' / 'frame-000011.jpg')], directory)

    def test_unknown_fields_are_not_invented(self):
        result = sermon_from_frame('A face with no readable text', 10)
        self.assertNotIn('preacher', result)
        self.assertNotIn('sermon-title', result)
    def test_quoted_sermon_and_explicit_series_still_work(self):
        result = sermon_from_frame('Series: Gospel\n"Welcome the Gentiles" Acts 11:1-18', 10)
        self.assertEqual('Gospel', result['sermon-series'])
        self.assertEqual('Welcome the Gentiles', result['sermon-title'])

if __name__ == '__main__': unittest.main()
