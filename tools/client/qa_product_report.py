"""Fail-closed LOGH report bridge; never launches a product or fixture.

The reviewed prestart runner observes a product but has no product check suite.
Consequently this bridge deliberately cannot emit product PASS, even for a
pinned runtime report claiming PASS. A hash is integrity, not trusted provenance.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import socket
import subprocess

CHECKS = ('original-client API behavior', 'original-client audio coverage',
          'original-client rendering', 'original-client protocol interoperability')
RUNTIME_STATUSES = {'PASS', 'PRODUCT_OBSERVED', 'PRODUCT_WINDOW_COMPLETE', 'WAIT_USER_GAME_OR_STEAM',
                    'WAIT_ISOLATION_UNPROVEN', 'WAIT_GUI_TEST_MUTEX',
                    'WAIT_SESSION_SET_CHANGED', 'TIMEOUT', 'FAIL', 'CLEANUP_FAILED',
                    'CANCELLED', 'COMMAND_FAILED', 'SESSION_STATE_CHANGED',
                    'UI_ENFORCEMENT_UNPROVEN', 'SILENCE_UNPROVEN'}
FAILURES = {'TIMEOUT', 'FAIL', 'CLEANUP_FAILED', 'COMMAND_FAILED',
            'SESSION_STATE_CHANGED', 'UI_ENFORCEMENT_UNPROVEN', 'SILENCE_UNPROVEN'}


def product_report(device, head, runtime=None, evidence=None):
    if runtime is not None and (not isinstance(runtime, dict)
                               or not isinstance(runtime.get('status'), str)
                               or (runtime.get('status') not in RUNTIME_STATUSES and
                                   not runtime['status'].startswith('WAIT_'))):
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
        'runtimeHeadProvenanceVerified': False,
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


def read_object(path):
    value = json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=unique_object)
    if not isinstance(value, dict):
        raise ValueError('Expected JSON object: ' + str(path))
    return value


def same_run_provenance(run, runtime_path, runtime, head, manifest_pin, repo):
    """Integrity/run binding only, not protection against same-user tampering."""
    run = run.resolve(strict=True)
    if runtime_path.resolve(strict=True) != run / 'runtime.json':
        raise ValueError('Runtime report is outside this run')
    binding = read_object(run / 'invocation.json')
    for key in ('before', 'after'):
        check = binding[key]
        validate_checkout(head, check['head'], check['dirty'])
    if (Path(binding['repo']).resolve() != repo.resolve() or
            Path(binding['run_directory']).resolve() != run or
            binding['runner_exit'] not in (2, 3) or binding['wait_exceeded'] is not False):
        raise ValueError('Invocation binding mismatch')
    if not re.fullmatch('[0-9a-f]{64}', manifest_pin or ''):
        raise ValueError('External manifest pin required')
    manifest_path = Path(binding['manifest'])
    manifest = read_runtime(manifest_path, manifest_pin)
    if runtime.get('manifest_sha256') != manifest_pin:
        raise ValueError('Runtime manifest pin mismatch')
    if runtime['status'].startswith('WAIT_'):
        return False  # No target/config yet; never manufacture execution proof.
    owned = Path(runtime['owned_temp']).resolve(strict=True)
    if owned.name != runtime['run_id'] or not re.fullmatch('[0-9a-f]{32}', owned.name):
        raise ValueError('Owned run id mismatch')
    config = read_runtime(owned / 'controller-config.json', runtime['config_sha256'])
    if (config != runtime['controller_config'] or config.get('product_logh') is not True
            or Path(config['runtime_report']).resolve() != runtime_path.resolve()
            or config.get('expected_runtime') != manifest.get('app')
            or config.get('executable') != manifest.get('executable')):
        raise ValueError('Controller config binding mismatch')
    target = runtime['controller']['target']
    # Keep synchronized with qa_prestart_audio.TARGET_SHA256 without importing Win32.
    if (target.get('image_sha256') !=
            'bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'
            or type(target.get('pid')) is not int or target['pid'] <= 0
            or type(target.get('creation_filetime')) is not int or target['creation_filetime'] <= 0):
        raise ValueError('Original target identity/hash mismatch')
    evidence_root = owned / 'evidence'
    if not evidence_root.is_dir() or evidence_root.is_symlink():
        raise ValueError('Missing owned evidence directory')
    listed = set()
    if not isinstance(runtime['evidence_files'], list):
        raise ValueError('Evidence inventory must be a list')
    for entry in runtime['evidence_files']:
        path = Path(entry['path']).resolve(strict=True)
        if not path.is_relative_to(evidence_root) or path in listed:
            raise ValueError('Evidence outside owned run or duplicate')
        listed.add(path)
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError('Evidence SHA256 mismatch')
        # Raw capture companions are bound by hash; JSON collector envelopes
        # additionally bind target/config/run. I2-I5 define their raw formats.
        if path.suffix == '.json':
            envelope = read_object(path)
            for key, expected in (('run_id', runtime['run_id']),
                                  ('manifest_sha256', manifest_pin),
                                  ('config_sha256', runtime['config_sha256']), ('target', target)):
                if envelope.get(key) != expected:
                    raise ValueError('Evidence binding mismatch: ' + key)
    if listed != {p.resolve() for p in evidence_root.rglob('*') if p.is_file()}:
        raise ValueError('Evidence inventory mismatch')
    return True


def safety_fields(runtime):
    """Every missing/malformed condition fails closed, independently per field."""
    result = dict(isolationVerified=False, silentVerified=False, cleanupVerified=False)
    c = runtime.get('controller', {})
    try:
        b = runtime['before']
        sid = b['runner_session']
        ids = runtime['session_ids_before']
        spec = importlib.util.spec_from_file_location(
            'logh_session_mutex', Path(__file__).with_name('qa_session_mutex.py'))
        assert spec is not None and spec.loader is not None
        mutex = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mutex)
        mutexes = runtime['mutexes']
        result['isolationVerified'] = bool(
            sid != b['active_console_session'] and
            all(sid != i for i, state in b['sessions'] if state in (0, 1)) and
            b['winsta'].lower() != 'winsta0' and not b['flags'] & 1 and
            runtime['after'] == b and ids == runtime['session_ids_after'] and 0 in ids and
            set(ids) == {0, *(i for i, _ in b['sessions'])} and
            {m['session'] for m in mutexes} == set(ids) and
            all(m['acquired'] is True and m['wait'] in (0, 128) and
                m['object_path'].casefold() == mutex.object_path(m['session']).casefold() for m in mutexes) and
            bool(runtime['private_desktop']) and runtime['controller_config']['desktop'] ==
            b['winsta'] + '\\' + runtime['private_desktop'] and
            c['pre_resume_receipt']['effective_ui_flags'] == 255 and bool(c['guard_receipts']) and
            all(r['effective_ui_flags'] == 255 for r in c['guard_receipts']) and
            runtime['protected_processes'] == [] and runtime['reference_processes_unchanged'] is True and
            not runtime['status'].startswith('WAIT_') and
            runtime['status'] not in ('SESSION_STATE_CHANGED', 'UI_ENFORCEMENT_UNPROVEN') and
            c['resumed'] is True)
    except (KeyError, TypeError, ValueError, AttributeError):
        pass
    try:
        receipts = [c['pre_resume_receipt'], *c['guard_receipts']]
        times = [r['recorded_mono'] for r in receipts]
        resumed, stopped, detached = (c[k] for k in
                                      ('resumed_mono', 'target_stopped_mono', 'guard_detached_mono'))
        numbers = [*times, resumed, stopped, detached]
        result['silentVerified'] = bool(
            c['resumed'] is True and isinstance(c['guard_receipts'], list) and len(receipts) >= 2 and
            isinstance(c['guard_messages'], list) and
            all(type(t) in (int, float) and math.isfinite(t) for t in numbers) and
            all(r['ready'] is True and r.get('failed') is None for r in receipts) and
            times[0] <= times[1] <= resumed <= times[1] + 1 and
            all(0 <= y - x <= 1 for x, y in zip(times, times[1:])) and
            times[-1] <= stopped <= times[-1] + 1 and stopped < detached and
            not any(m.get('type') == 'error' or m.get('payload', {}).get('type') == 'audio-fatal'
                    for m in c['guard_messages']) and
            type(c['product_audio_observation']['originals_called']) is int and
            c['product_audio_observation']['originals_called'] == 0)
    except (KeyError, TypeError, ValueError, AttributeError):
        pass
    try:
        result['cleanupVerified'] = bool(
            c['cleanup_verified'] is True and type(c['inner_active_after']) is int and c['inner_active_after'] == 0 and
            runtime['cleanup_verified'] is True and type(runtime['outer_active_after']) is int and runtime['outer_active_after'] == 0 and
            runtime['remaining_owned_pids'] == [] and bool(runtime['owned_processes']) and
            all(type(p['pid']) is int and p['pid'] > 0 and p['creation_time'] and
                p['signalled'] is True for p in runtime['owned_processes']) and
            runtime['status'] != 'CLEANUP_FAILED' and
            runtime['protected_processes_after'] == runtime['protected_processes'])
    except (KeyError, TypeError, ValueError, AttributeError):
        pass
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--runtime-report', type=Path)
    parser.add_argument('--runtime-report-sha256')
    parser.add_argument('--same-run', type=Path)
    parser.add_argument('--manifest-sha256')
    parser.add_argument('--check-only', action='store_true')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    report = product_report(socket.gethostname(), args.head)
    code = 3
    try:
        if not re.fullmatch('[0-9a-f]{40}', args.head):
            raise ValueError('Head must be an exact lowercase 40-character Git SHA')
        if bool(args.runtime_report) != bool(args.runtime_report_sha256):
            raise ValueError('Runtime report and externally pinned SHA256 must be supplied together')
        if args.same_run and args.runtime_report is None:
            raise ValueError('same-run requires runtime report')
        if args.runtime_report is not None or args.check_only:
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
            if args.check_only:
                print(json.dumps({'head': actual, 'dirty': dirty}))
                return 3
            assert args.runtime_report is not None and args.runtime_report_sha256 is not None
            raw = read_runtime(args.runtime_report, args.runtime_report_sha256)
            report = product_report(socket.gethostname(), args.head, raw, str(args.runtime_report.resolve()))
            report['runtimeEvidenceSha256'] = args.runtime_report_sha256
            # A matching checkout does NOT bind historical runtime evidence to it.
            report['runtimeHeadProvenanceVerified'] = False
            report['blockers'].append('Runtime evidence has no independently verified head/run provenance')
            if args.same_run:
                verified = same_run_provenance(args.same_run, args.runtime_report, raw,
                                               args.head, args.manifest_sha256, args.repo)
                report['runtimeHeadProvenanceVerified'] = verified
                if verified:
                    report.update(safety_fields(raw))
                    report['blockers'].pop()
                if raw['status'].startswith('WAIT_'):
                    report['blockers'].append(raw['status'])
            validate_checkout(args.head, preflight.git(args.repo, 'rev-parse', 'HEAD'),
                              preflight.git(args.repo, 'status', '--porcelain=v1', '--untracked-files=normal'))
            if report['platforms']['windows']['status'] == 'FAIL':
                code = 2
        with args.out.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=True, indent=2)
            stream.write('\n')
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError,
            subprocess.TimeoutExpired) as error:
        report = product_report(socket.gethostname(), args.head)
        report['platforms']['windows']['status'] = 'FAIL'
        report['blockers'].append(f'{type(error).__name__}: {error}')
        code = 2
    print(json.dumps(report, ensure_ascii=True))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
