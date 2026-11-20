"""Public archive discovery; fetch durations only for ambiguous Livestream titles."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import yt_dlp

def normalized_title(title):
    return ' '.join((title or '').casefold().split())

def enrich_duplicates(entries, lookup):
    groups = defaultdict(list)
    for entry in entries:
        title = normalized_title(entry.get('title'))
        if re.search(r'\blive ?stream', title):
            groups[title].append(entry)
    for group in groups.values():
        if len(group) < 2:
            continue
        for entry in group:
            if entry.get('duration') is None:
                try:
                    entry['duration'] = lookup(str(entry['id']))
                except Exception:
                    pass  # Leave duration unknown, but keep the ambiguity visible.
    return entries

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache-dir', required=True)
    args = parser.parse_args()
    cache = Path(args.cache_dir)
    durations = {}
    for path in cache.glob('*/metadata.json'):
        try:
            data = json.loads(path.read_text())
            video_id = data.get('webpage_url', '').rstrip('/').split('/')[-1]
            if data.get('duration') is not None:
                durations[video_id] = data['duration']
        except (ValueError, OSError):
            pass
    duration_file = cache / 'vimeo-durations.json'
    try:
        durations = dict(json.loads(duration_file.read_text()), **durations)
    except (ValueError, OSError):
        pass
    options = {'quiet': True, 'extract_flat': True, 'socket_timeout': 20, 'retries': 2}
    with yt_dlp.YoutubeDL(options) as downloader:
        catalog = downloader.extract_info('https://vimeo.com/ewobc/videos', download=False)
    catalog['entries'] = list(catalog.get('entries', []))
    for entry in catalog['entries']:
        if entry.get('duration') is None and str(entry.get('id')) in durations:
            entry['duration'] = durations[str(entry['id'])]
    with yt_dlp.YoutubeDL(dict(options, extract_flat=False)) as downloader:
        def lookup(video_id):
            if video_id not in durations:
                data = downloader.extract_info('https://player.vimeo.com/video/' + video_id, download=False)
                if data.get('duration') is not None:
                    durations[video_id] = data['duration']
            return durations.get(video_id)
        catalog['entries'] = enrich_duplicates(list(catalog.get('entries', [])), lookup)
    cache.mkdir(parents=True, exist_ok=True)
    temporary = duration_file.with_suffix('.tmp')
    temporary.write_text(json.dumps(durations))
    temporary.replace(duration_file)
    print(json.dumps(catalog))

if __name__ == '__main__':
    main()
