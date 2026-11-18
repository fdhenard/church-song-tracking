import http.cookiejar
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('vimeo_session', Path(__file__).with_name('vimeo_session.py'))
session = importlib.util.module_from_spec(spec)
spec.loader.exec_module(session)


def cookie(domain, name, value='private-session-value'):
    return http.cookiejar.Cookie(0, name, value, None, False, domain, True, domain.startswith('.'), '/', True, True, None, True, None, None, {})


class SessionTests(unittest.TestCase):
    def test_capture_keeps_only_vimeo_and_restricts_permissions(self):
        jar = http.cookiejar.CookieJar()
        for c in [cookie('.vimeo.com', 'vimeo'), cookie('.example.com', 'unrelated'), cookie('.evilvimeo.com', 'other')]:
            jar.set_cookie(c)
        with tempfile.TemporaryDirectory() as directory, patch('yt_dlp.cookies.extract_cookies_from_browser', return_value=jar):
            destination = Path(directory) / 'session.cookies'
            session.capture('chrome', destination)
            saved = http.cookiejar.MozillaCookieJar(str(destination))
            saved.load(ignore_discard=True)
            self.assertEqual([('vimeo', '.vimeo.com')], [(c.name, c.domain) for c in saved])
            self.assertEqual(0o600, destination.stat().st_mode & 0o777)

    def test_unsigned_browser_does_not_replace_existing_session(self):
        with tempfile.TemporaryDirectory() as directory, patch('yt_dlp.cookies.extract_cookies_from_browser', return_value=[]):
            destination = Path(directory) / 'session.cookies'
            destination.write_text('existing')
            with self.assertRaisesRegex(ValueError, 'No signed-in Vimeo session'):
                session.capture('firefox', destination)
            self.assertEqual('existing', destination.read_text())
