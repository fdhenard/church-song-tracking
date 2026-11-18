"""Explicitly capture only Vimeo cookies from a user-selected local browser."""
import argparse
import contextlib
import io
import http.cookiejar
import json
import os
from pathlib import Path
import tempfile


class SessionError(ValueError):
    pass


def capture(browser, destination):
    from yt_dlp.cookies import extract_cookies_from_browser
    if browser not in ('chrome', 'firefox'):
        raise SessionError('Choose Chrome or Firefox.')
    # Keep browser diagnostics out of the JSON response and app logs.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        jar = extract_cookies_from_browser(browser)
    cookies = [c for c in jar if c.domain.lstrip('.') == 'vimeo.com' or c.domain.lstrip('.').endswith('.vimeo.com')]
    if not any(c.name == 'vimeo' and not c.is_expired() for c in cookies):
        raise SessionError('No signed-in Vimeo session was found. Sign in to Vimeo in the selected browser, then save the session again.')
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=destination.parent, prefix='.vimeo-session-')
    os.close(fd)
    try:
        saved = http.cookiejar.MozillaCookieJar(temporary)
        for cookie in cookies:
            saved.set_cookie(cookie)
        saved.save(ignore_discard=True, ignore_expires=False)
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', choices=['chrome', 'firefox'], required=True)
    parser.add_argument('--destination', required=True)
    args = parser.parse_args()
    try:
        capture(args.browser, args.destination)
        print(json.dumps({'ok': True}))
    except SessionError as error:
        print(json.dumps({'error': str(error)}))
        raise SystemExit(1)
    except Exception:
        # Browser stores and keychain errors may contain private paths or cookie data.
        print(json.dumps({'error': 'Could not read the browser session. Open the selected browser and sign in to Vimeo. If macOS asks, allow access to the browser cookie encryption key, then try again. Firefox is another option if Chrome cannot be read.'}))
        raise SystemExit(1)
