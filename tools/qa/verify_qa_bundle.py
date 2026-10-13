"""Read-only QA ZIP verifier. Never extracts/imports runtime or opens readiness."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

PINNED_SHA256 = 'f68e06a3f5364770f9d4ed3a1552211992048165dea6665b94942fa265e05d4c'
PINNED_BYTES = 74469
MANIFEST = Path(__file__).with_name('qa-bundle-manifest.json')


def verify(path):
    path = Path(path)
    if path.name != 'WINDOWS-QA-BUNDLE.zip':
        raise ValueError('QA destination basename required')
    blob = path.read_bytes()
    digest = hashlib.sha256(blob).hexdigest()
    if len(blob) != PINNED_BYTES or digest != PINNED_SHA256:
        raise ValueError('Pinned source ZIP size/SHA256 mismatch')
    expected = json.loads(MANIFEST.read_text(encoding='utf-8'))
    files = expected['files']
    if len(files) != 16:
        raise ValueError('Expected exactly 16 manifest entries')
    manifest_name = 'logh-audio/manifest.json'
    allowed = {row['archive_path'] for row in files} | {manifest_name}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != allowed:
            raise ValueError('Duplicate, missing or unapproved ZIP member')
        if json.loads(archive.read(manifest_name)) != expected:
            raise ValueError('Pinned manifest mismatch')
        for row in files:
            info = archive.getinfo(row['archive_path'])
            if info.file_size != row['size'] or info.file_size > 2_000_000:
                raise ValueError('Manifest size mismatch')
            data = archive.read(info)
            if hashlib.sha256(data).hexdigest() != row['sha256']:
                raise ValueError('Manifest SHA256 mismatch')
    return {'verification': 'MATCH', 'readiness': 'CLOSED',
            'sha256': digest, 'bytes': len(blob), 'manifest_entries': len(files),
            'unapproved_members': 0, 'upload': 'NOT_RUN',
            'game': 'NOT_RUN', 'game_audio': 'NOT_RUN', 'launcher': 'NOT_RUN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    try:
        result = verify(args.bundle)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(json.dumps({'verification': 'REJECTED', 'readiness': 'CLOSED',
                          'reason': str(error)}))
        return 3
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
