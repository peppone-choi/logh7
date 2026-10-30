"""Cache wiki reference image candidates under work/. Author: Peppone Choi.

Images remain unverified references, never recovered VII face mappings.
Only the explicit roster pages and their namu.moe image hosts are fetched.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
from html import unescape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import quote, urlsplit, urlunsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
HOSTS = {'namu.wiki', 'i.namu.wiki', 'namu.moe', 'd.namu.moe', 'file.namu.moe'}


def safe_url(url):
    parts = urlsplit('https:' + url if url.startswith('//') else url)
    if parts.scheme != 'https' or parts.hostname not in HOSTS or parts.port or parts.username:
        raise ValueError('Reference URL must use an allowed HTTPS wiki host')
    return urlunsplit(('https', parts.netloc, quote(parts.path, safe='/%:@'), parts.query, ''))


def read_url(url, limit):
    with urlopen(Request(safe_url(url), headers={'User-Agent': 'LOGH7-NPC-Reference/1.0'}), timeout=12) as response:
        safe_url(response.url)
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError('Reference exceeds size limit')
        return data


class Images(HTMLParser):
    """Ignore navigation icons, flags, signatures and non-raster files.

    This selects document image candidates, not detected/identified faces.
    """
    def __init__(self):
        super().__init__()
        self.items = []
        self.noscript = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'noscript':
            self.noscript += 1
        if tag != 'img' or self.noscript:
            return
        label = a.get('alt', '')
        url = a.get('data-original') or a.get('data-src') or a.get('src', '')
        width = a.get('width', '')
        if (not re.search(r'\.(?:png|jpe?g|webp)', label + url, re.I) or
                re.search(r'국기|아이콘|서명|sign|flag|alliance|empire|logo|attachment/free|mxx5q0t3ulw01|lion02|Goldenbaum-Dynas', label, re.I) or
                width.isdecimal() and int(width) < 90):
            return
        if url:
            try:
                url = safe_url(url)
                if url not in [i['image_url'] for i in self.items]:
                    self.items.append(dict(label=label, image_url=url))
            except ValueError:
                pass

    def handle_endtag(self, tag):
        if tag == 'noscript':
            self.noscript = max(0, self.noscript - 1)


def image_type(data):
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'png', 'image/png'
    if data.startswith(b'\xff\xd8\xff'):
        return 'jpg', 'image/jpeg'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'webp', 'image/webp'
    raise ValueError('Not a supported raster image')


class MinorImages(HTMLParser):
    """Associate inline images only with their enclosing named list entry."""
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.depth = 0
        self.fragment = ''
        self.people = {}

    def handle_starttag(self, tag, attrs):
        if tag == 'li':
            self.depth += 1
        if self.depth:
            self.fragment += self.get_starttag_text()

    def handle_endtag(self, tag):
        if self.depth:
            self.fragment += '</' + tag + '>'
        if tag == 'li' and self.depth:
            self.depth -= 1
            if not self.depth:
                parser = Images()
                parser.feed(self.fragment)
                head = re.split(r'<br\b|:', self.fragment, maxsplit=1, flags=re.I)[0]
                name = unescape(re.sub(r'<[^>]+>|\([^)]*\)|[☆★]', '', head)).strip()
                if parser.items and len(name) < 80:
                    for part in re.split(r'\s*[,&·]\s*', name):
                        self.people[part] = parser.items
                self.fragment = ''

    def handle_data(self, data):
        if self.depth:
            self.fragment += data

    def handle_entityref(self, name):
        self.handle_data('&' + name + ';')


def collect(entry, destination, maximum):
    page = safe_url(entry['page'])
    key = hashlib.sha256(page.encode()).hexdigest()[:24]
    cache = destination / 'pages' / (key + '.html')
    result = dict(page=entry['page'], mirror=entry['mirror'], fetched_from=page, images=[], status='unavailable')
    try:
        if '_images' in entry:
            items = entry['_images']
        else:
            if cache.exists():
                data = cache.read_bytes()
            else:
                data = read_url(page, 4 * 1024 * 1024)
                cache.write_bytes(data)
            parser = Images()
            parser.feed(data.decode('utf-8'))
            items = parser.items
        failures = []
        for item in items[:maximum * 2]:
            if len(result['images']) >= maximum:
                break
            try:
                image_key = hashlib.sha256(item['image_url'].encode()).hexdigest()[:32]
                existing = list((destination / 'images').glob(image_key + '.*'))
                blob = existing[0].read_bytes() if existing else read_url(item['image_url'], 2 * 1024 * 1024)
                ext, mime = image_type(blob)
                path = destination / 'images' / (image_key + '.' + ext)
                if not existing:
                    path.write_bytes(blob)
                result['images'].append(dict(item, path=path.relative_to(destination).as_posix(), mime=mime))
            except (OSError, ValueError) as error:
                failures.append(str(error)[:150])
        result['status'] = 'cached' if result['images'] else 'unavailable' if failures else 'no_candidate'
        if failures:
            result['errors'] = failures[:8]
    except (OSError, ValueError) as error:
        result['error'] = str(error)[:200]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--roster', type=Path, default=ROOT / 'l10n/npc-roster.ko.json')
    parser.add_argument('--output', type=Path, required=True, help='Cache directory under work/')
    parser.add_argument('--per-person', type=int, default=3, choices=range(1, 7))
    args = parser.parse_args()
    destination = args.output.resolve()
    if not destination.is_relative_to((ROOT / 'work').resolve()) or destination == (ROOT / 'work').resolve():
        parser.error('Cache must be a subdirectory under repository work/')
    entries = json.loads(args.roster.read_text(encoding='utf-8-sig'))['characters']
    for name in ('pages', 'images'):
        (destination / name).mkdir(parents=True, exist_ok=True)
    result = dict(schema=1, references={})
    minor = next(e for e in entries if e['origin'] == 'minor')
    minor_url = safe_url(minor['page'])
    minor_cache = destination / 'pages' / (hashlib.sha256(minor_url.encode()).hexdigest()[:24] + '.html')
    try:
        data = minor_cache.read_bytes() if minor_cache.exists() else read_url(minor_url, 4 * 1024 * 1024)
        minor_cache.write_bytes(data)
        summary = MinorImages()
        summary.feed(data.decode('utf-8'))
        for entry in entries:
            if entry['origin'] == 'minor':
                entry['_images'] = summary.people.get(entry['name'], [])
    except (OSError, ValueError) as error:
        print(f'Minor reference page unavailable: {error}', flush=True)
        for entry in entries:
            if entry['origin'] == 'minor':
                entry['_images'] = []
    # Three bounded requests at a time; retries are explicit reruns using cache.
    with ThreadPoolExecutor(max_workers=3) as pool:
        jobs = {pool.submit(collect, e, destination, args.per_person): e for e in entries}
        for i, job in enumerate(as_completed(jobs), 1):
            entry = jobs[job]
            result['references'][entry['name']] = job.result()
            if i % 20 == 0:
                print(f'{i}/{len(entries)} pages processed', flush=True)
    (destination / 'references.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    count = sum(bool(r['images']) for r in result['references'].values())
    print(f'{len(entries)} people; {count} with cached image candidates', flush=True)


if __name__ == '__main__':
    main()
