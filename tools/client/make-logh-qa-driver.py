"""Generate a literal-only LOGH server driver; never execute it."""
import argparse
from pathlib import Path, PurePosixPath
import re
import uuid

SERVER_ROOT = Path('/srv/hermes-workspace/projects/logh')


def private_path(value):
    # Deliberately narrow grammar: no quotes, controls, PS expansion, ADS,
    # backslashes, dot traversal, trailing dots/spaces or alternate drives.
    if not re.fullmatch(r'E:/HermesQA/(?:[A-Za-z0-9_-][A-Za-z0-9_. -]*/)*[A-Za-z0-9_-][A-Za-z0-9_. -]*', value):
        raise ValueError('Expected a literal E:/HermesQA child path')
    if any(p in ('.', '..') or p.endswith(('.', ' ')) for p in value.split('/')):
        raise ValueError('Noncanonical private path')
    return value


def render_driver(head, copy, python, manifest, manifest_sha256, app_root,
                  temp_parent, port, observe_seconds, out_root, timeout=30):
    if not re.fullmatch('[0-9a-f]{40}', head):
        raise ValueError('Head must be lowercase 40hex')
    if not re.fullmatch('[0-9a-f]{64}', manifest_sha256):
        raise ValueError('Manifest pin must be lowercase 64hex')
    if not (1024 <= port <= 65535 and 1 <= observe_seconds <= 25
            and observe_seconds < timeout <= 30):
        raise ValueError('Port/observation/timeout outside supported bounds')
    for value in (copy, python, manifest, app_root, temp_parent, out_root):
        private_path(value)
    out = str(PurePosixPath(out_root) / ('product-' + uuid.uuid4().hex + '.json'))
    values = [('Mode', 'Run'), ('Python', python), ('Repo', copy), ('Head', head),
              ('Out', out), ('Manifest', manifest), ('ManifestSha256', manifest_sha256),
              ('AppRoot', app_root), ('TempParent', temp_parent), ('Port', str(port)),
              ('ObserveSeconds', str(observe_seconds)), ('Timeout', str(timeout))]
    def literal(value):
        return "'" + value.replace("'", "''") + "'"
    return ('& ' + literal(copy + '/tools/client/windows-product-qa.ps1') + ' ' +
            ' '.join('-' + key + ' ' + literal(value) for key, value in values) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('head', 'copy', 'python', 'manifest', 'manifest-sha256', 'app-root',
                 'temp-parent', 'out-root'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--port', type=int, default=20000)
    parser.add_argument('--observe-seconds', type=int, default=5)
    parser.add_argument('--timeout', type=int, default=30)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        output = args.output
        if (not output.parent.resolve(strict=True).is_relative_to(SERVER_ROOT)
                or output.suffix != '.ps1'):
            raise ValueError('Driver must be a new .ps1 under the LOGH server workspace')
        content = render_driver(args.head, args.copy, args.python, args.manifest,
                                args.manifest_sha256, args.app_root, args.temp_parent,
                                args.port, args.observe_seconds, args.out_root, args.timeout)
        with output.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(content)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(str(output))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
