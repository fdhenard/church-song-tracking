"""Apple Vision OCR: local native execution with per-frame reusable caches."""
import hashlib
import json
from pathlib import Path
import select
import shutil
import subprocess
import sys


class LocalOCRError(RuntimeError):
    pass


def ensure_tool():
    if sys.platform != 'darwin':
        raise LocalOCRError('Local OCR currently requires macOS. Run this job on a Mac with Apple Vision.')
    root = Path(__file__).resolve().parent.parent
    source = root / 'scripts' / 'local_ocr.swift'
    binary = root / '.video-tools' / 'bin' / 'church-local-ocr'
    version = hashlib.sha256(source.read_bytes()).hexdigest()[:16]
    version_file = binary.with_suffix('.version')
    if binary.is_file() and version_file.is_file() and version_file.read_text() == version:
        return binary, version
    compiler = shutil.which('swiftc')
    if not compiler:
        raise LocalOCRError('Install Apple Command Line Tools with xcode-select --install to enable local OCR.')
    module_cache = root / '.video-tools' / 'swift-module-cache'
    module_cache.mkdir(parents=True, exist_ok=True)
    binary.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run([compiler, '-O', '-module-cache-path', str(module_cache),
                                 str(source), '-o', str(binary)], capture_output=True, timeout=300)
    except (OSError, subprocess.TimeoutExpired):
        raise LocalOCRError('The local OCR helper could not be built. Check Apple Command Line Tools.') from None
    if result.returncode:
        raise LocalOCRError('The local OCR helper could not compile. Check Apple Command Line Tools and scripts/local_ocr.swift.')
    version_file.write_text(version)
    return binary, version


def read_frames(frames, directory, progress=None):
    binary, version = ensure_tool()
    cache_dir = directory / 'local-ocr'
    cache_dir.mkdir(exist_ok=True)
    records = {}
    process = None
    try:
        with (directory / 'local-ocr.log').open('a') as log:
            for position, (index, path) in enumerate(frames):
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                cache = cache_dir / f'{index:06}-{version}-{digest[:20]}.json'
                if cache.exists():
                    row = json.loads(cache.read_text())
                else:
                    if process is None:
                        process = subprocess.Popen([str(binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                                   stderr=log, text=True, encoding='utf-8', bufsize=1)
                    process.stdin.write(json.dumps({'index': index, 'path': str(path.resolve())}) + '\n')
                    process.stdin.flush()
                    if not select.select([process.stdout], [], [], 180)[0]:
                        raise LocalOCRError('Local OCR timed out on a frame. Completed OCR results are saved; retry to resume.')
                    line = process.stdout.readline()
                    if not line:
                        raise LocalOCRError('Local OCR stopped before finishing. Check local-ocr.log and retry.')
                    row = json.loads(line)
                    if row.get('error'):
                        raise LocalOCRError('Local OCR could not read a frame. No frames were silently discarded; check local-ocr.log and retry.')
                    row['_image_hash'] = digest
                    temporary = cache.with_suffix('.json.tmp')
                    temporary.write_text(json.dumps(row))
                    temporary.replace(cache)
                if row.get('index') != index or not isinstance(row.get('text'), str) or row.get('_image_hash') != digest:
                    raise LocalOCRError('A local OCR cache entry is invalid. Check the local-ocr cache before retrying.')
                records[index] = row
                if progress:
                    progress(position + 1, len(frames))
    except (OSError, ValueError) as error:
        raise LocalOCRError('Local OCR could not read or save its results. Check local cache permissions and disk space.') from error
    finally:
        if process is not None:
            try:
                process.stdin.close()
            except BrokenPipeError:
                pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()
    return records


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Read cached video frames locally with Apple Vision. Never calls an API.')
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=0)
    args = parser.parse_args()
    frames = list(enumerate(sorted((args.directory / 'frames').glob('frame-*.jpg'))))
    if args.limit > 0:
        frames = frames[:args.limit]
    if not frames:
        raise SystemExit('No cached video frames found.')
    def progress(done, total):
        if done % 25 == 0 or done == total:
            print(f'Local OCR: {done}/{total}', flush=True)
    try:
        records = read_frames(frames, args.directory, progress)
    except LocalOCRError as error:
        raise SystemExit(str(error))
    print(json.dumps({'frames_read': len(records), 'frames_with_text': sum(bool(r['text'].strip()) for r in records.values()),
                      'api_calls': 0, 'cache': str(args.directory / 'local-ocr')}))
