"""Conservative, local-only song/sermon suggestions from explicit OCR text."""
from datetime import date
import json
from difflib import SequenceMatcher
import re
import sys


def timestamp(index):
    seconds = index * 3
    h, r = divmod(seconds, 3600); m, s = divmod(r, 60)
    return f'{h}:{m:02}:{s:02}' if h else f'{m}:{s:02}'


def service_date_from_title(title):
    date_pattern = r'(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})'
    title = title or ''
    match = re.fullmatch(date_pattern, title.strip()) or re.search(
        r'\blive\s*stream\s*[:\-]?\s*' + date_pattern + r'(?!\d)', title, re.I)
    if not match:
        return ''
    month, day, year = map(int, match.groups())
    if year < 100:
        year += 2000
    try:
        return date(year, month, day).isoformat()
    except ValueError:
        return ''


def credit_without_by(text):
    """Accept a repeated song heading plus a quoted credit, author, and copyright boundary."""
    if not re.search(r'CCLI|Public Domain|copyright|©', text, re.I):
        return None
    match = re.search(
        r'["“]([^"”\n]{2,180})["”]\s+(.+?)(?=\s+(?:©\s*|copyright\s*)?(?:19|20)\d{2}\b|\.\s*Public Domain\b)',
        text, re.I | re.S)
    if not match:
        return None
    title, author = match.groups()
    headings = {' '.join(line.strip(' "“”').casefold().split()) for line in text.splitlines()}
    if ' '.join(title.casefold().split()) not in headings:
        return None
    author = ' '.join(author.strip(' .').split())
    # A year/publisher alone is not an author credit.
    if not author or not any(c.isalpha() for c in author) or re.search(r'©|copyright|CCLI', author, re.I):
        return None
    return title, author



def personal_credit_before_license(text):
    """Read name-shaped credits only after a repeated title and before a full license."""
    match = re.search(
        r'^\s*["“]([^"”\n]{2,180})["”]\s+([^"“”]+?)\s+CCLI[ \t]+License[ \t]*#[ \t]*\d+[ \t.]*$',
        text, re.M)
    if not match:
        return None
    title, credit = match.groups()
    headings = {' '.join(line.strip(' "“”').casefold().split())
                for line in text[:match.start()].splitlines()}
    if ' '.join(title.casefold().split()) not in headings:
        return None
    # License lines can contain publishers instead. Keep ambiguous organizations
    # in the existing title-only review path rather than treating them as writers.
    if re.search(r'\b(?:music|publishing|publisher|praise|worship|church|ministries|'
                 r'ministry|records|copyright|rights|reserved|sovereign|grace|'
                 r'integrity|capitol|group|inc|ltd|llc|company|public|domain)\b', credit, re.I):
        return None
    names = re.split(r'\s*;\s*', ' '.join(credit.split()))
    for name in names:
        words = name.split()
        if not 2 <= len(words) <= 4:
            return None
        for word in words:
            letters = word.replace('-', '').replace("'", '').replace('’', '')
            if re.fullmatch(r'[A-Z]\.', word):
                continue
            if not letters.isalpha() or not word[0].isupper() or not any(c.islower() for c in word):
                return None
    return title.strip(), '; '.join(names)


def public_domain_title(text):
    """An explicit, repeated title credit can identify a song without an author."""
    match = re.search(r'["“]([^"”\n]{2,180})["”]\s*Public\s+Domain\b', text, re.I)
    if not match:
        return None
    title = match.group(1).strip()
    headings = {' '.join(line.strip(' "“”').casefold().split()) for line in text.splitlines()}
    if ' '.join(title.casefold().split()) not in headings:
        return None
    return title, ''


def ccli_license_title(text):
    """Recognize a repeated heading in a CCLI credit with ambiguous attribution."""
    match = re.search(r'^\s*["“]([^"”\n]{2,180})["”][^"“”]{0,600}?\bCCLI\s+License\s*#\s*\d+[ \t.]*$',
                      text, re.I | re.M)
    if not match:
        return None
    title = match.group(1).strip()
    headings = {' '.join(line.strip(' "“”').casefold().split()) for line in text[:match.start()].splitlines()}
    if ' '.join(title.casefold().split()) in headings:
        return title, ''


def uncredited_song_heading(records, position):
    """Flag a repeated short heading followed promptly by matching lyric text."""
    row = records[position]
    lines = [line.strip() for line in row.get('ocr', '').splitlines() if line.strip()]
    if not (2 <= len(lines) <= 3 and len(set(line.casefold() for line in lines)) == 1):
        return None
    title = lines[0]
    words = title.split()
    if not (2 <= len(words) <= 8 and len(title) <= 100 and all(word[0].isupper() for word in words)):
        return None
    if re.search(r'welcome|newsletter|conference|church|announcement|event|breakfast|missions', title, re.I):
        return None
    for following in records[position + 1:position + 4]:
        if not 0 < following['index'] - row['index'] <= 5:
            continue
        lyrics = [line.strip() for line in following.get('ocr', '').splitlines() if line.strip()]
        if len(lyrics) < 3 or len(' '.join(lyrics).split()) < 12:
            continue
        prefix = ' '.join(lyrics[0].split()[:len(words)])
        if SequenceMatcher(None, title.casefold(), prefix.casefold()).ratio() >= .75:
            return title
    return None


def propose_details(records):
    songs = []
    sermon = {}
    records = sorted(records, key=lambda r: r['index'])
    for position, row in enumerate(records):
        text = row.get('ocr', '')
        index = row['index']
        credit = re.search(r'["“]([^"”\n]{2,180})["”]\s*by\s+(.+?)(?=\.\s*(?:Public Domain|CCLI|Copyright)|\s*[©]|$)', text, re.I | re.S)
        if not credit:
            credit = credit_without_by(text) or personal_credit_before_license(text)
        if not credit and re.search(r'words?\s+(?:and|&)\s+music\s+by|written\s+by', text, re.I):
            lines = text.splitlines()
            for position, line in enumerate(lines):
                match = re.match(r'(?:words?\s+(?:and|&)\s+music|written)\s+by\s+(.+)', line, re.I)
                if match and position > 0:
                    credit = (lines[position - 1].strip(), match.group(1).strip())
                    break
        public_domain = public_domain_title(text) if not credit else None
        license_only = ccli_license_title(text) if not (credit or public_domain) else None
        credit = credit or public_domain or license_only
        if credit and (not hasattr(credit, 'groups') or re.search(r'CCLI|Public Domain|copyright|©', text, re.I)):
            title, author = credit.groups() if hasattr(credit, 'groups') else credit
            author = ' '.join(re.split(r'\s*(?:©|CCLI|Copyright|Public Domain)', author, flags=re.I)[0].strip(' .').split())
            if title.strip() and (author or public_domain or license_only) and len(author) <= 240:
                candidate = {'title': title.strip(), 'author': author, 'slide': timestamp(index), 'start': '',
                             'ocr': text, 'evidence-index': index,
                             'suggestion-notes': 'Title and author read from explicit song credits. Verify spelling and the title / author slide time. Song start time is optional when this slide time is available.'}
                if license_only:
                    candidate['suggestion-notes'] = ('Song title read from a repeated title and CCLI license credit. '
                        'Author credit needs manual review. The credit may mix writers and publishers. Choose the existing song or enter verified author details before approval.')
                elif not author:
                    candidate['suggestion-notes'] = ('Song title read from a repeated title and explicit Public Domain credit. '
                        'No author is named on this slide; choose the existing song or enter verified author details before approval. '
                        'Public Domain is a rights statement, not an author. Verify the title-slide time.')
                prior = songs[-1] if songs else None
                if prior and index - prior['evidence-index'] < 20 and SequenceMatcher(None, prior['title'].casefold(), title.strip().casefold()).ratio() >= .85:
                    if not prior['author'] and author and prior['title'].casefold() == candidate['title'].casefold():
                        candidate['evidence-alternates'] = [prior['evidence-index'], *prior.get('evidence-alternates', [])]
                        songs[-1] = candidate
                    else:
                        prior.setdefault('evidence-alternates', []).append(index)
                        prior['suggestion-notes'] += ' Nearby credit slides may contain OCR variations; check the source spelling.'
                    continue
                if not (prior and prior['title'].casefold() == title.strip().casefold()
                        and prior['author'].casefold() == author.casefold()
                        and index - prior['evidence-index'] < 200):
                    songs.append(candidate)
        if not credit:
            title = uncredited_song_heading(records, position)
            if title and not any(song['title'].casefold() == title.casefold()
                                 and index - song['evidence-index'] < 200 for song in songs):
                songs.append({'title': title, 'author': '', 'slide': '', 'start': timestamp(index),
                              'ocr': text, 'evidence-index': index,
                              'suggestion-notes': 'Possible song from a repeated heading followed by matching lyrics. '
                                  'No title / author credit slide was found. Author is unknown. '
                                  'The song start time is the first sampled heading, not a verified musical start; '
                                  'watch the video to confirm the song, author, and start time before approval.'})
        # A short title screen with a quoted heading followed by a Bible reference.
        heading = re.search(r'["“]([^"”\n]{4,180})["”]\s*((?:[1-3]\s+)?[A-Za-z]+\s+\d+:\d+(?:[-–]\d+)?)', text)
        if not sermon and heading and len(text) < 500 and not re.search(r'CCLI|Public Domain|newsletter|IN THIS ISSUE', text, re.I):
            prefix = text[:heading.start()].strip()
            explicit_series = re.search(r'(?:sermon\s+)?series\s*:\s*([^\n]+)', prefix, re.I)
            series = explicit_series.group(1).strip() if explicit_series else ''
            if not series and re.match(r'^from\b', prefix, re.I) and len(prefix.splitlines()) <= 5:
                series = ' '.join(prefix.split())
            sermon = {'sermon-title': heading.group(1).strip(), 'sermon-time': timestamp(index),
                      'sermon-series': series, 'sermon-reference': heading.group(2), 'sermon-scripture': heading.group(2),
                      'sermon-evidence-index': index,
                      'sermon-suggestion-notes': 'Possible sermon title screen. Verify the series wording and the title-slide time; speech may begin elsewhere.'}
    return {'songs': songs, 'sermon': sermon}


def apply_suggestions(draft, records):
    suggestions = propose_details(records)
    result = dict(draft, **suggestions['sermon'])
    if not result.get('date'):
        result['date'] = service_date_from_title(draft.get('title', ''))
    result['songs'] = suggestions['songs']
    return result


if __name__ == '__main__':
    try:
        data = json.load(sys.stdin)
        print(json.dumps(propose_details(data.get('evidence', []))))
    except (ValueError, KeyError, TypeError):
        print(json.dumps({'error': 'Saved OCR data could not be read.'}))
        sys.exit(1)
