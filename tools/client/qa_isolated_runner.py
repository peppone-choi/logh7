"""Project-owned harmless-fixture runner. Never launches LOGH.

Manifest is an integrity inventory, not an independent review approval.
Only the bundled fixture is accepted; no arbitrary commands or imports.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import time
import uuid

MUTEX = 'Local\\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC'
FILES = ('qa_isolated_runner.py', 'qa_silent_fixture.py')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(repo):
    directory = Path(__file__).resolve().parent
    files = {str((directory / name).relative_to(repo)).replace('\\', '/'): digest(directory / name) for name in FILES}
    if os.name == 'nt':
        runtime = set()
        lib = Path(sys.base_prefix) / 'Lib'
        parents = [p for p in lib.iterdir() if p.is_dir() and p.name not in ('site-packages', '__pycache__')]
        runtime.update(p.resolve() for p in lib.glob('*.py'))
        for parent in (*parents, Path(sys.base_prefix) / 'DLLs', Path(sys.prefix)):
            runtime.update(p.resolve() for p in parent.rglob('*') if p.is_file() and p.suffix.lower() in ('.py', '.pyd', '.dll', '.exe'))
        runtime.update(p.resolve() for p in Path(sys.base_prefix).glob('*.dll'))
        runtime.add(Path(sys.executable).resolve())
        runtime.add(base_executable())
        files.update({str(p): digest(p) for p in sorted(runtime)})
    return {'version': 2, 'fixture_executable': str(base_executable()), 'files': files}


def base_executable():
    return Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True)


def ui_enforced(records, identities, mode):
    return (bool(records) and {r['pid'] for r in records} == set(identities)
            and all(r.get('in_named_job') and r.get('effective_ui_flags') == 255
                    and r.get('atom_isolated') and (mode != 'desktop-denial' or
                    (r.get('desktop_creation_denied') and r.get('create_desktop_err') == 5))
                    for r in records))


def validate(repo, manifest, fixture):
    root = repo.resolve(strict=True)
    directory = Path(__file__).resolve().parent
    if not directory.is_relative_to(root):
        raise ValueError('Runner must be inside explicit repo')
    data = json.loads(manifest.read_text(encoding='utf-8'))
    if data != inventory(root):
        raise ValueError('UNREVIEWED_RUNTIME: inventory mismatch')
    if fixture.resolve(strict=True) != directory / FILES[1]:
        raise ValueError('Only bundled silent fixture allowed')
    return digest(manifest)


def snapshot():
    import ctypes
    import win32api
    import win32process
    import win32service
    import win32ts
    station = win32service.GetProcessWindowStation()
    flags = win32service.GetUserObjectInformation(station, 1)
    return {'runner_session': win32ts.ProcessIdToSessionId(os.getpid()),
            'active_console_session': ctypes.windll.kernel32.WTSGetActiveConsoleSessionId(),
            'sessions': sorted((s['SessionId'], s['State']) for s in win32ts.WTSEnumerateSessions(None, 1, 0)),
            'winsta': win32service.GetUserObjectInformation(station, 2),
            'flags': flags['Flags'], 'pid': win32api.GetCurrentProcessId()}


def isolated(state):
    sid = state['runner_session']
    return (sid != state['active_console_session'] and
            all(sid != i for i, status in state['sessions'] if status in (0, 1)) and
            state['winsta'].lower() != 'winsta0' and not state['flags'] & 1)


def run(args):
    report = {'product_qa': 'NOT_RUN', 'games_executed': False,
              'status': 'WAIT_ISOLATION_UNPROVEN', 'cleanup_verified': False,
              'remaining_owned_pids': [], 'owned_processes': [],
              'isolation_mode': 'session_isolated', 'silent_mode': 'silent_by_construction'}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation: never overwrite previous evidence.
    output = args.report.open('x', encoding='utf-8')
    mutex = desk = job = process = thread = None
    held = False
    atom = 0
    identities = {}
    owned_handles = {}
    assigned = False
    references = {}
    try:
        report['manifest_sha256'] = validate(args.repo, args.manifest, args.fixture)
        report['runner_sha256'] = digest(Path(__file__))
        if args.product_logh:
            report['status'] = 'WAIT_PRESTART_SILENCE_INTEGRATION'
            return report
        import win32api
        import win32con
        import win32event
        import win32job
        import win32process
        import win32service
        import win32ts
        import ctypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.GlobalAddAtomW.argtypes = [ctypes.c_wchar_p]
        kernel.GlobalAddAtomW.restype = ctypes.c_ushort
        kernel.GlobalDeleteAtom.argtypes = [ctypes.c_ushort]
        def identity(pid):
            handle = win32api.OpenProcess(0x1000 | 0x100000, False, pid)
            try:
                return str(win32process.GetProcessTimes(handle)['CreationTime'])
            finally:
                handle.Close()
        def members():
            return win32job.QueryInformationJobObject(job, win32job.JobObjectBasicProcessIdList)
        def observe():
            for pid in members():
                if pid not in owned_handles:
                    owned_handles[pid] = win32api.OpenProcess(0x1000 | 0x100000, False, pid)
                created = str(win32process.GetProcessTimes(owned_handles[pid])['CreationTime'])
                session = win32ts.ProcessIdToSessionId(pid)
                if session != before['runner_session']:
                    raise RuntimeError('ISOLATION_BREACH')
                identities[pid] = created
            report['owned_processes'] = [{'pid': p, 'creation_time': c, 'session': before['runner_session']} for p, c in identities.items()]
        mutex = win32event.CreateMutex(None, False, MUTEX)
        if win32event.WaitForSingleObject(mutex, 0) != win32event.WAIT_OBJECT_0:
            report['status'] = 'WAIT_GUI_TEST_MUTEX'
            return report
        held = True
        before = snapshot()
        report['before'] = before
        try:
            input_desktop = win32service.OpenInputDesktop(0, False, 1)
            report['input_desktop_open'] = True
            input_desktop.CloseDesktop()
        except Exception as error:
            report['input_desktop_open'] = False
            report['winerror'] = getattr(error, 'winerror', None)
        if not isolated(before):
            return report
        references = {p: identity(p) for p in args.reference_pid}
        name = 'LOGH_QA_' + uuid.uuid4().hex
        desk = win32service.CreateDesktop(name, 0, 0x10000000, None)
        report['private_desktop'] = name
        job = win32job.CreateJobObject(None, name + '_JOB')
        limits = win32job.QueryInformationJobObject(job, win32job.JobObjectExtendedLimitInformation)
        limits['BasicLimitInformation']['LimitFlags'] = win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        win32job.SetInformationJobObject(job, win32job.JobObjectExtendedLimitInformation, limits)
        win32job.SetInformationJobObject(job, win32job.JobObjectBasicUIRestrictions, {'UIRestrictionsClass': 0xFF})
        actual = win32job.QueryInformationJobObject(job, win32job.JobObjectBasicUIRestrictions)['UIRestrictionsClass']
        report['job_ui_restrictions'] = actual
        if actual != 0xFF:
            raise RuntimeError('ISOLATION_POLICY_NOT_APPLIED')
        startup = win32process.STARTUPINFO()
        startup.lpDesktop = before['winsta'] + '\\' + name
        startup.dwFlags = win32con.STARTF_USESHOWWINDOW | 0x80
        startup.wShowWindow = 4
        executable = str(base_executable())
        argv = [executable, '-I', '-B', str(args.fixture), '--mode', args.mode]
        atom_name = 'LOGH_QA_ATOM_' + uuid.uuid4().hex
        atom = kernel.GlobalAddAtomW(atom_name)
        if not atom:
            raise ctypes.WinError(ctypes.get_last_error())
        if shutil.disk_usage(args.report.parent).free < 128 * 1024 * 1024:
            raise RuntimeError('INSUFFICIENT_FREE_SPACE')
        temp = args.report.parent / ('TEMP_' + name)
        temp.mkdir()
        environment = dict(os.environ, TEMP=str(temp), TMP=str(temp), LOGH_QA_JOB=name + '_JOB', LOGH_QA_ATOM=atom_name)
        report['owned_temp'] = str(temp)
        report['fixture_executable'] = executable
        process, thread, pid, _ = win32process.CreateProcess(executable, subprocess.list2cmdline(argv), None, None, False, win32con.CREATE_SUSPENDED, environment, str(args.repo), startup)
        identities[pid] = identity(pid)
        win32job.AssignProcessToJobObject(job, process)
        assigned = True
        observe()
        win32process.ResumeThread(thread)
        start = time.monotonic()
        while win32event.WaitForSingleObject(process, 20) == win32event.WAIT_TIMEOUT:
            observe()
            elapsed = time.monotonic() - start
            if args.cancel_file and args.cancel_file.exists():
                raise RuntimeError('CANCELLED')
            if args.test_exception_after is not None and elapsed >= args.test_exception_after:
                raise RuntimeError('EXCEPTION_TEST')
            if elapsed >= args.timeout:
                raise RuntimeError('TIMEOUT')
        observe()
        report['exit_code'] = win32process.GetExitCodeProcess(process)
        report['status'] = 'PASS' if report['exit_code'] == 0 else 'COMMAND_FAILED'
    except Exception as error:
        report['status'] = 'FAIL'
        report['error'] = str(error)
    finally:
        try:
            if job:
                win32job.TerminateJobObject(job, 2)
                # Assignment may have failed while our root was suspended.
                if process and not assigned and win32event.WaitForSingleObject(process, 0) != 0:
                    win32api.TerminateProcess(process, 2)
                    win32event.WaitForSingleObject(process, 5000)
                deadline = time.monotonic() + 5
                while win32job.QueryInformationJobObject(job, win32job.JobObjectBasicAccountingInformation)['ActiveProcesses'] and time.monotonic() < deadline:
                    time.sleep(0.02)
                report['job_active_processes_after_cleanup'] = win32job.QueryInformationJobObject(job, win32job.JobObjectBasicAccountingInformation)['ActiveProcesses']
                # Held handles retain the original process object across PID reuse.
                survivors = []
                for pid, created in identities.items():
                    handle = owned_handles.get(pid, process if process and pid == win32process.GetProcessId(process) else None)
                    if handle is None or win32event.WaitForSingleObject(handle, 5000) != 0:
                        survivors.append(pid)
                report['remaining_owned_pids'] = sorted(survivors)
                report['cleanup_verified'] = not survivors and report['job_active_processes_after_cleanup'] == 0
                if 'owned_temp' in report:
                    report['fixture_records'] = [json.loads(p.read_text(encoding='utf-8')) for p in Path(report['owned_temp']).glob('fixture-*.json')]
                    report['silent_verified'] = bool(report['fixture_records']) and all(r['silent_by_construction'] and r['pid'] in identities for r in report['fixture_records'])
                    report['ui_enforced'] = ui_enforced(report['fixture_records'], identities, args.mode)
                    if not report['ui_enforced']:
                        report['status'] = 'UI_ENFORCEMENT_UNPROVEN'
                    if report['status'] == 'PASS' and not report['silent_verified']:
                        report['status'] = 'SILENCE_UNPROVEN'
                if not report['cleanup_verified']:
                    report['status'] = 'CLEANUP_FAILED'
            elif process:
                # Only our held, still-suspended root handle, never a name/PID kill.
                win32api.TerminateProcess(process, 2)
                report['cleanup_verified'] = win32event.WaitForSingleObject(process, 5000) == 0
            if held and 'before' in report:
                report['after'] = snapshot()
                report['reference_processes_unchanged'] = all(identity(p) == c for p, c in references.items())
                if report['after'] != report['before'] or not report['reference_processes_unchanged']:
                    report['status'] = 'SESSION_STATE_CHANGED'
                report['isolation_checks'] = {
                    'I1': before['runner_session'] != before['active_console_session'] and all(before['runner_session'] != i for i, s in before['sessions'] if s in (0, 1)),
                    'I2': before['winsta'].lower() != 'winsta0' and not before['flags'] & 1,
                    'I3': bool(report.get('private_desktop')),
                    'I4': report.get('ui_enforced', False),
                    'I5': bool(report['owned_processes']) and all(p['session'] == before['runner_session'] for p in report['owned_processes']),
                    'I6': report['after'] == before,
                    'I7': report['reference_processes_unchanged']}
        except Exception as error:
            report['status'] = 'CLEANUP_FAILED'
            report['cleanup_error'] = str(error)
        finally:
            for handle in (*owned_handles.values(), thread, process, job):
                if handle:
                    handle.Close()
            if atom:
                kernel.GlobalDeleteAtom(atom)
            if desk:
                desk.CloseDesktop()
            if held:
                win32event.ReleaseMutex(mutex)
            if mutex:
                mutex.Close()
            json.dump(report, output, indent=2)
            output.close()
    return report


def main():
    parser = argparse.ArgumentParser()
    for name in ('repo', 'report', 'manifest', 'fixture'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--product-logh', action='store_true')
    parser.add_argument('--mode', choices=('success', 'failure', 'tree', 'hang', 'desktop-denial'), default='success')
    parser.add_argument('--timeout', type=float, default=5)
    parser.add_argument('--cancel-file', type=Path)
    parser.add_argument('--test-exception-after', type=float)
    parser.add_argument('--reference-pid', type=int, action='append', default=[])
    result = run(parser.parse_args())
    print(json.dumps(result))
    return 0 if result['status'] == 'PASS' else (3 if result['status'].startswith('WAIT') else 2)


if __name__ == '__main__':
    sys.exit(main())
