"""Fail-closed LOGH report bridge; never launches a product or fixture.

The reviewed prestart runner observes a product but has no product check suite.
Consequently this bridge deliberately cannot emit product PASS, even for a
pinned runtime report claiming PASS. A hash is integrity, not trusted provenance.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import socket
import subprocess

CHECKS = ('original-client API behavior', 'original-client audio coverage',
          'original-client rendering', 'original-client protocol interoperability')
RUNTIME_STATUSES = {'PASS', 'PRODUCT_OBSERVED', 'WAIT_USER_GAME_OR_STEAM',
                    'WAIT_ISOLATION_UNPROVEN', 'WAIT_GUI_TEST_MUTEX',
                    'WAIT_SESSION_SET_CHANGED', 'TIMEOUT', 'FAIL', 'CLEANUP_FAILED',
                    'CANCELLED', 'COMMAND_FAILED', 'SESSION_STATE_CHANGED',
                    'UI_ENFORCEMENT_UNPROVEN', 'SILENCE_UNPROVEN'}
FAILURES = {'TIMEOUT', 'FAIL', 'CLEANUP_FAILED', 'COMMAND_FAILED',
            'SESSION_STATE_CHANGED', 'UI_ENFORCEMENT_UNPROVEN', 'SILENCE_UNPROVEN'}


def product_report(device, head, runtime=None, evidence=None):
    if runtime is not None and (not isinstance(runtime, dict)
                               or not isinstance(runtime.get('status'), str)
                               or runtime.get('status') not in RUNTIME_STATUSES):
        raise ValueError('Missing or unknown runtime status')
    status = 'FAIL' if runtime and runtime['status'] in FAILURES else 'NOT_RUN'
    return {
        'head_sha': head, 'project': 'logh', 'product_pass': False,
        'productQA': 'NOT_RUN', 'windows_qa_receipt': None,
        'platforms': {'windows': {'status': status, 'deviceId': device,
            'checks': [{'name': name, 'status': 'NOT_RUN',
                        'evidence': 'No implemented product assertion collector'} for name in CHECKS]}},
        # These fields mean product-level proof, not a fixture guard handshake.
        'isolationVerified': False, 'silentVerified': False, 'cleanupVerified': False,
        'runtimeExecutedByAdapter': False,
        'runtimeEvidence': evidence,
        'runtimeStatus': runtime['status'] if runtime else None,
        'blockers': ['Product API/audio/render/protocol assertion collectors are missing; '
                     'runtime observation, fixture PASS, exec and probe are not product PASS.'],
    }


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('Duplicate JSON key: ' + key)
        value[key] = item
    return value


def read_runtime(path, expected_hash):
    if not re.fullmatch('[0-9a-f]{64}', expected_hash):
        raise ValueError('Runtime report SHA256 must be 64 lowercase hex characters')
    # Hash and parse the same bytes. Never import code named by the evidence.
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected_hash:
        raise ValueError('Runtime report SHA256 mismatch')
    value = json.loads(data.decode('utf-8'), object_pairs_hook=unique_object)
    if not isinstance(value, dict):
        raise ValueError('Runtime report must be a JSON object')
    return value


def validate_checkout(head, actual, dirty):
    if not re.fullmatch('[0-9a-f]{40}', head) or actual != head or dirty:
        raise ValueError('Exact clean checkout required; do not reset or overwrite')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--runtime-report', type=Path)
    parser.add_argument('--runtime-report-sha256')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = product_report(socket.gethostname(), args.head)
    code = 3
    try:
        if not re.fullmatch('[0-9a-f]{40}', args.head):
            raise ValueError('Head must be an exact lowercase 40-character Git SHA')
        if bool(args.runtime_report) != bool(args.runtime_report_sha256):
            raise ValueError('Runtime report and externally pinned SHA256 must be supplied together')
        if args.runtime_report is not None:
            # Import only the existing non-launching git inspection helper.
            import importlib.util
            spec = importlib.util.spec_from_file_location(
                'logh_qa_preflight', Path(__file__).with_name('windows-qa-preflight.py'))
            assert spec is not None and spec.loader is not None
            preflight = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(preflight)
            actual = preflight.git(args.repo, 'rev-parse', 'HEAD')
            dirty = preflight.git(args.repo, 'status', '--porcelain=v1', '--untracked-files=normal')
            validate_checkout(args.head, actual, dirty)
            raw = read_runtime(args.runtime_report, args.runtime_report_sha256)
            report = product_report(socket.gethostname(), args.head, raw, str(args.runtime_report.resolve()))
            report['runtimeEvidenceSha256'] = args.runtime_report_sha256
            # A matching checkout does NOT bind historical runtime evidence to it.
            report['runtimeHeadProvenanceVerified'] = False
            report['blockers'].append('Runtime evidence has no independently verified head/run provenance')
            validate_checkout(args.head, preflight.git(args.repo, 'rev-parse', 'HEAD'),
                              preflight.git(args.repo, 'status', '--porcelain=v1', '--untracked-files=normal'))
            if report['platforms']['windows']['status'] == 'FAIL':
                code = 2
        with args.out.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=True, indent=2)
            stream.write('\n')
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report = product_report(socket.gethostname(), args.head)
        report['platforms']['windows']['status'] = 'FAIL'
        report['blockers'].append(f'{type(error).__name__}: {error}')
        code = 2
    print(json.dumps(report, ensure_ascii=True))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
