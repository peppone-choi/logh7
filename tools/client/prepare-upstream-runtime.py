"""Non-launching D1 manifest preparation for a pinned extracted distribution/JDK."""
import argparse
import json
import re
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from qa_upstream import MAIN, digest, inventory


def prepare(runtime, head, run_id, artifact_id, artifact_sha256, jdk_source, jdk_sha256):
    if not re.fullmatch('[0-9a-f]{40}', head):
        raise ValueError('Exact head required')
    for value in (artifact_sha256, jdk_sha256):
        if not re.fullmatch('[0-9a-f]{64}', value):
            raise ValueError('Package SHA256 required')
    if not run_id.isdecimal() or not artifact_id.isdecimal():
        raise ValueError('GitHub run/artifact IDs required')
    if not jdk_source.startswith('https://github.com/adoptium/temurin25-binaries/releases/download/'):
        raise ValueError('Expected recorded Temurin source')
    if digest(runtime / 'jdk25.zip') != jdk_sha256 or digest(runtime / 'distribution.zip') != artifact_sha256:
        raise ValueError('Package pin mismatch')
    release = (runtime / 'jdk/release').read_text(encoding='utf-8')
    if not re.search(r'^JAVA_VERSION="25(?:[.+\-"]|$)', release, re.M):
        raise ValueError('JDK25 required')
    provenance = json.loads((runtime / 'distribution/distribution-provenance.json').read_text())
    if (provenance['head'] != head or str(provenance['run_id']) != run_id
            or provenance['repository'] != 'peppone-choi/logh7' or provenance['build'] != ':app:installDist'
            or provenance['files'] != inventory(runtime / 'distribution/server/app/build/install/app')):
        raise ValueError('Distribution provenance mismatch')
    license_path = runtime / 'jdk/legal/java.base/LICENSE'
    if not license_path.is_file():
        raise ValueError('Temurin license missing')
    return dict(version=1, head=head, runtime=str(runtime), main=MAIN,
        jdk=dict(source=jdk_source, archive_sha256=jdk_sha256,
                 license='GPL-2.0-only WITH Classpath-exception-2.0',
                 license_sha256=digest(license_path), release=release, files=inventory(runtime / 'jdk')),
        distribution=dict(run_id=run_id, artifact_id=artifact_id, archive_sha256=artifact_sha256,
                          files=inventory(runtime / 'distribution')),
        product_qa='NOT_RUN', environment_ready=False, windows_qa_receipt=None)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime', type=Path, required=True)
    for name in ('head', 'run-id', 'artifact-id', 'artifact-sha256', 'jdk-source', 'jdk-sha256'):
        p.add_argument('--' + name, required=True)
    args = p.parse_args()
    data = prepare(args.runtime, args.head, args.run_id, args.artifact_id,
                   args.artifact_sha256, args.jdk_source, args.jdk_sha256)
    output = args.runtime / 'runtime-manifest.json'
    with output.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, indent=2)
    print(json.dumps(dict(manifest=str(output), manifest_sha256=digest(output),
                         jdk_files=len(data['jdk']['files']), distribution_files=len(data['distribution']['files']))))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
