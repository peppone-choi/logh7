"""Create an exact-head QA checkout from a verified bundle; never execute repo code.

Run using hermes-windows exec, not qa. No game, hooks, filters or original
untracked code is executed. Existing destinations are always refused.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def git(repo, *args):
    env = os.environ.copy()
    # Do not inherit user hooks, filters, credential helpers or alternate git dirs.
    for name in list(env):
        if name.startswith('GIT_'):
            del env[name]
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_TERMINAL_PROMPT='0', GIT_OPTIONAL_LOCKS='0')
    command = ['git', '-c', 'core.fsmonitor=false', '-c',
        'core.hooksPath=' + os.devnull, '-c', 'core.autocrlf=false',
        '-c', 'core.attributesFile=' + os.devnull, '-C', str(repo)]
    # Local clean/process filters can execute during status as well as checkout.
    # Enumerate names only (never values/credentials), before any inspection.
    filters = subprocess.run(command + ['config', '--name-only', '--get-regexp', '^filter\\.'],
                             env=env, capture_output=True, timeout=20)
    if filters.returncode not in (0, 1) or filters.stdout.strip():
        raise RuntimeError('Repository-local Git filters are not permitted')
    result = subprocess.run([*command, *args],
        env=env, capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout


def snapshot(repo):
    status = git(repo, 'status', '--porcelain=v1', '-z', '--untracked-files=all')
    names = set(git(repo, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', 'HEAD').split(b'\0'))
    names.update(git(repo, 'diff', '--no-ext-diff', '--no-textconv', '--cached', '--name-only', '-z').split(b'\0'))
    names.update(git(repo, 'ls-files', '--modified', '--deleted', '-z').split(b'\0'))
    names.update(git(repo, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0'))
    hashes = {}
    for name in sorted(names - {b''}):
        path = repo / os.fsdecode(name)
        if not path.resolve().is_relative_to(repo.resolve()) or path.is_symlink():
            raise ValueError('Dirty file escapes original repository')
        hashes[os.fsdecode(name)] = digest(path) if path.is_file() else None
    return {'head': git(repo, 'rev-parse', 'HEAD').decode().strip(),
            'statusHex': status.hex(), 'dirtyHashes': hashes}


def processes():
    # Read-only inventory. No Stop-Process, GUI or audio APIs.
    command = "$ErrorActionPreference='Stop'; @(Get-Process | Where-Object { $_.ProcessName -match '^(steam.*|hoi4|G7.*|logh.*)$' } | ForEach-Object { [pscustomobject]@{pid=$_.Id;name=$_.ProcessName;created=$_.StartTime.ToUniversalTime().ToString('o')} }) | ConvertTo-Json -Compress"
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive',
        '-Command', command], capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr)
    data = json.loads(result.stdout or '[]')
    return data if isinstance(data, list) else [data]


def validate_paths(original, destination, head, root):
    if not re.fullmatch('[0-9a-f]{40}', head):
        raise ValueError('Expected exact lowercase 40-character commit SHA')
    original, destination, root = original.resolve(), destination.resolve(), root.resolve()
    if destination == original or destination.is_relative_to(original) or original.is_relative_to(destination):
        raise ValueError('QA destination must be separate from original')
    if destination.parent != root or destination.name != head:
        raise ValueError('QA destination must be QA root / exact SHA')
    if destination.exists():
        raise FileExistsError('Refusing existing QA destination')
    return destination


def prepare(original, destination, head, root, bundle, bundle_sha256, inventory=processes,
            shallow_boundaries=()):
    destination = validate_paths(original, destination, head, root)
    if any(not re.fullmatch('[0-9a-f]{40}', boundary) for boundary in shallow_boundaries):
        raise ValueError('Invalid explicit shallow boundary SHA')
    if not re.fullmatch('[0-9a-f]{64}', bundle_sha256) or digest(bundle) != bundle_sha256:
        raise ValueError('Bundle SHA256 mismatch')
    before = snapshot(original)
    process_before = inventory()
    report = {'requestedHead': head, 'windowsRepo': str(destination),
              'bundleSha256': bundle_sha256, 'originalBefore': before,
              'processesBefore': process_before, 'productQA': 'NOT_RUN',
              'gamesExecuted': False, 'status': 'FAIL',
              'shallowBoundaries': list(shallow_boundaries)}
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        # mkdir is atomic: even an empty existing directory is not reused.
        destination.mkdir(exist_ok=False)
        git(destination, 'init', '--template=')
        git(destination, 'bundle', 'verify', str(bundle.resolve()))
        if shallow_boundaries:
            # Git bundles omit shallow metadata. Only explicit sender boundaries
            # are installed, in this newly initialized repository.
            (destination / '.git' / 'shallow').write_text(
                '\n'.join(shallow_boundaries) + '\n', encoding='ascii')
        git(destination, 'fetch', '--no-tags', '--no-recurse-submodules', str(bundle.resolve()),
            'HEAD')
        for boundary in shallow_boundaries:
            if git(destination, 'cat-file', '-t', boundary).strip() != b'commit':
                raise ValueError('Shallow boundary is not an available commit')
        actual = git(destination, 'rev-parse', 'FETCH_HEAD^{commit}').decode().strip()
        if actual != head:
            raise ValueError('Bundle commit differs from requested SHA')
        # No submodule initialization, LFS, install scripts or repo code execution.
        git(destination, 'checkout', '--detach', head)
        report['copyHead'] = git(destination, 'rev-parse', 'HEAD').decode().strip()
        report['copyStatus'] = git(destination, 'status', '--porcelain=v1', '--untracked-files=all').decode()
        if report['copyHead'] != head or report['copyStatus']:
            raise ValueError('QA copy is not exact-head and clean')
        report['status'] = 'COPY_READY'
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report['error'] = f'{type(error).__name__}: {error}'
    finally:
        report['originalAfter'] = snapshot(original)
        report['processesAfter'] = inventory()
        report['originalUnchanged'] = before == report['originalAfter']
        report['userProcessesPreserved'] = all(p in report['processesAfter'] for p in process_before)
        if not report['originalUnchanged'] or not report['userProcessesPreserved']:
            report['status'] = 'FAIL'
    # Failed destinations are preserved for inspection, never deleted/reused.
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, default=Path('E:/logh7'))
    parser.add_argument('--qa-root', type=Path, default=Path('E:/HermesQA/logh'))
    parser.add_argument('--windows-repo', type=Path, required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--bundle-sha256', required=True)
    parser.add_argument('--shallow-boundary', action='append', default=[],
                        help='Exact sender Git shallow boundary; repeat for each boundary')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('Native Windows required')
    # Reserve report without overwriting anything, outside both repositories.
    report_path = args.report.resolve()
    for repo in (args.original.resolve(), args.windows_repo.resolve()):
        if report_path == repo or report_path.is_relative_to(repo):
            parser.error('Report must be outside original and QA repositories')
    with report_path.open('x', encoding='utf-8') as stream:
        try:
            report = prepare(args.original, args.windows_repo, args.head, args.qa_root,
                             args.bundle, args.bundle_sha256,
                             shallow_boundaries=args.shallow_boundary)
        except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
            report = {'status': 'FAIL', 'productQA': 'NOT_RUN', 'gamesExecuted': False,
                      'error': f'{type(error).__name__}: {error}'}
        stream.write(json.dumps(report, ensure_ascii=True, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=True))
    return 0 if report['status'] == 'COPY_READY' else 1


if __name__ == '__main__':
    raise SystemExit(main())
