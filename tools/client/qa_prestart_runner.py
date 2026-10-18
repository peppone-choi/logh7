"""Project-private suspended prestart runtime for x86 fixtures and pinned LOGH.

Product invocation requires exact-head review; integrity is not approval.
Supervisor owns desktop/mutex/outer Job; direct-base controller owns the inner
active-limit-1/UI255 Job. No SessionMute, host audio query or legacy imports.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
OWN_CODE = ('qa_prestart_runner.py', 'qa_prestart_audio.py', 'qa_prestart_silence_x86.js',
            'qa_prestart_fixture.py', 'qa_session_mutex.py', 'qa_isolated_runner.py',
            'qa_silent_fixture.py')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def fixture_mode(mode):
    return mode if mode in ('success', 'failure') else 'hang'


def normal_fixture_exit(signalled, code, messages):
    return (signalled and code in (0, 7)
            and not any(m.get('type') == 'error' or m.get('payload', {}).get('type') == 'audio-fatal'
                        for m in messages))


def observation_status(alive, elapsed, observe_seconds):
    """Observation completion is never a product assertion or PASS."""
    if not alive:
        return 'COMMAND_FAILED'
    if observe_seconds is not None and elapsed >= observe_seconds:
        return 'PRODUCT_WINDOW_COMPLETE'
    return None


def timed_receipt(guard):
    receipt = dict(guard.assert_ready())
    receipt['recorded_mono'] = time.monotonic()
    return receipt


def protected_processes():
    import win32ts
    return sorted([{'session': p[0], 'pid': p[1], 'name': p[2]}
                   for p in win32ts.WTSEnumerateProcesses(0)
                   if Path(p[2]).name.casefold() in
                   ('steam.exe', 'steamwebhelper.exe', 'hoi4.exe', 'g7.exe', 'g7o.exe',
                    'gin7.exe', 'g7mtclient.exe')], key=lambda p: (p['session'], p['pid']))


def target_spec(args):
    from qa_prestart_audio import TARGET_SHA256, _file_hash, _plain_path
    if not args.product_logh:
        return args.fixture_dir / ('fixture-' + fixture_mode(args.mode) + '.exe'), args.fixture_dir
    if args.executable is None or args.app_root is None:
        raise ValueError('Product requires explicit executable and full app_root')
    executable, root = _plain_path(args.executable), _plain_path(args.app_root)
    if not root.is_relative_to(Path('E:/HermesQA')) or not executable.is_relative_to(root):
        raise ValueError('Product requires a private full app root')
    if _file_hash(executable) != TARGET_SHA256:
        raise RuntimeError('Unsupported product target hash')
    return executable, root


def controller_command(root, config, config_sha256):
    # -B alone still reads existing pyc. A new empty prefix bypasses in-tree
    # caches, and -B keeps the new prefix empty throughout this owned run.
    cache = root / 'pyc'
    cache.mkdir()
    return [str(base_python()), '-I', '-S', '-B', '-X', 'pycache_prefix=' + str(cache),
            str(HERE / 'qa_prestart_runner.py'), '--controller', str(config),
            '--config-sha256', config_sha256]


def base_python():
    return Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True)


def supervisor_prefix():
    """-S on Python <3.14 skips venv prefix setup as well as site startup."""
    prefix = Path(sys.executable).resolve(strict=True).parent.parent
    if not (prefix / 'pyvenv.cfg').is_file() or not prefix.is_relative_to(Path('E:/HermesQA')):
        raise RuntimeError('Explicit private Windows venv required with -S')
    sys.prefix = sys.exec_prefix = str(prefix)
    return prefix / 'Lib/site-packages'


def supervisor_dependencies(site):
    # Only after exact inventory validation. Never call site.main/addsitedir:
    # .pth and sitecustomize must not run, even in the private environment.
    sys.path[:0] = [str(site), str(site / 'win32'), str(site / 'win32/lib')]
    import pywin32_bootstrap


def inventory(repo, fixture_dir, executable=None, app_root=None, port=20000):
    from qa_isolated_runner import inventory as python_inventory
    from qa_prestart_fixture import image
    from qa_prestart_audio import runtime_manifest, _plain_path, _file_hash
    repo = _plain_path(repo)
    if not HERE.is_relative_to(repo):
        raise ValueError('Runner outside explicit repo')
    files = python_inventory(repo)
    files['files'].update({p: _file_hash(p) for p in
                          [Path(sys.prefix) / 'pyvenv.cfg', *[HERE / n for n in OWN_CODE]]})
    # JSON object keys must be strings.
    files['files'] = {str(k): v for k, v in files['files'].items()}
    product = executable is not None
    if not product:
        app_root = _plain_path(fixture_dir)
        for mode in ('success', 'failure', 'hang'):
            path = app_root / ('fixture-' + mode + '.exe')
            if path.read_bytes() != image(mode):
                raise RuntimeError('Harmless fixture bytes mismatch')
        executable = app_root / 'fixture-success.exe'
    extras = [HERE / n for n in OWN_CODE]
    files.update(version=4, python_version=sys.version, site=str(Path(sys.prefix) / 'Lib/site-packages'),
                 executable=str(executable), app_root=str(app_root),
                 target_args=['127.0.0.1', str(port), 'ginei00', '1', 'dummy'] if product else [],
                 app=runtime_manifest(executable, extras, app_root=app_root))
    return files


def validate(args):
    if digest(args.manifest) != args.manifest_sha256:
        raise RuntimeError('UNREVIEWED_RUNTIME: external manifest digest mismatch')
    recorded = json.loads(args.manifest.read_text())
    executable, root = target_spec(args)
    if recorded != inventory(args.repo, args.fixture_dir,
                             executable if args.product_logh else None, root, args.port):
        raise RuntimeError('UNREVIEWED_RUNTIME: exact inventory mismatch')
    return recorded


def session_ids():
    import win32ts
    return sorted({0, *(s['SessionId'] for s in win32ts.WTSEnumerateSessions(None, 1, 0))})


def job_policy(job, target=False, apply_ui=True):
    import win32job as wj
    info = wj.QueryInformationJobObject(job, wj.JobObjectExtendedLimitInformation)
    flags = wj.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | wj.JOB_OBJECT_LIMIT_ACTIVE_PROCESS
    info['BasicLimitInformation'].update(LimitFlags=flags, ActiveProcessLimit=1 if target else 5)
    wj.SetInformationJobObject(job, wj.JobObjectExtendedLimitInformation, info)
    got = wj.QueryInformationJobObject(job, wj.JobObjectExtendedLimitInformation)['BasicLimitInformation']
    if got['LimitFlags'] != flags or got['ActiveProcessLimit'] != (1 if target else 5):
        raise RuntimeError('Owned Job limit mismatch')
    ui = 255 if target and apply_ui else 0
    # Even setting UI=0 materializes a Win32k Job UI node. Two such nodes
    # reject nesting with ERROR_NOT_SUPPORTED on 19045. Leave outer/default
    # UI untouched; only the assigned suspended target gets a UI node.
    if ui:
        wj.SetInformationJobObject(job, wj.JobObjectBasicUIRestrictions,
                                  {'UIRestrictionsClass': ui})
    if wj.QueryInformationJobObject(job, wj.JobObjectBasicUIRestrictions)['UIRestrictionsClass'] != ui:
        raise RuntimeError('Owned Job UI mismatch')
    return {'flags': flags, 'active_limit': got['ActiveProcessLimit'], 'ui': ui}


def controller(config):
    # -I -S disables site/.pth execution. Only pinned, explicit dependency paths.
    site = Path(config['site'])
    sys.path[:0] = [str(HERE), str(site), str(site / 'win32'), str(site / 'win32/lib')]
    import pywin32_bootstrap
    import win32api as wa
    import win32con as wc
    import win32event as we
    import win32job as wj
    import win32process as wp
    import qa_prestart_audio as audio
    result = {'status': 'FAIL', 'resumed': False, 'product_pass': False, 'product_qa': 'NOT_RUN',
              'environment_ready': False, 'guard_receipts': [], 'cleanup_verified': False}
    process = thread = inner = guard = None
    assigned = False
    try:
        if Path(config['cancel']).exists():
            result['status'] = 'CANCELLED'
            return result
        executable = Path(config['executable'])
        if config['product_logh']:
            if audio._file_hash(executable) != audio.TARGET_SHA256:
                raise RuntimeError('Unsupported product target hash')
            audio.verify_runtime_manifest(executable, config['expected_runtime'],
                                          [HERE / n for n in OWN_CODE], app_root=config['app_root'])
        else:
            from qa_prestart_fixture import image
            if executable.read_bytes() != image(fixture_mode(config['mode'])):
                raise RuntimeError('Harmless target bytes changed')
        inner = wj.CreateJobObject(None, config['inner_job'])
        # Windows rejects assigning to a nested Job with UI restrictions already
        # set. Assign the suspended target first, then set and verify UI255.
        result['inner_initial_policy'] = job_policy(inner, target=True, apply_ui=False)
        startup = wp.STARTUPINFO()
        startup.lpDesktop = config['desktop']
        startup.dwFlags = wc.STARTF_USESHOWWINDOW | 0x80
        startup.wShowWindow = 4
        process, thread, pid, _ = wp.CreateProcess(str(executable), subprocess.list2cmdline([str(executable), *config['target_args']]),
                                                  None, None, False, wc.CREATE_SUSPENDED | wc.CREATE_NO_WINDOW,
                                                  dict(os.environ), str(executable.parent), startup)
        result['target'] = audio.identity(pid)
        wj.AssignProcessToJobObject(inner, process)
        assigned = True
        result['inner_policy'] = job_policy(inner, target=True)
        if not wj.IsProcessInJob(process, inner):
            raise RuntimeError('Target Job membership missing')
        if Path(config['cancel']).exists():
            result['status'] = 'CANCELLED'
            return result
        guard = audio.AudioGuard(pid, executable, fixture=not config['product_logh'], verify_helpers=True,
                                 expected_runtime=config['expected_runtime'], app_root=config['app_root'],
                                 extra_files=[HERE / n for n in OWN_CODE],
                                 on_failure=lambda: audio.stop_owned_handle(process))
        result['helper_identities'] = guard.helper_identities
        result['pre_resume_receipt'] = timed_receipt(guard)
        # Synthetic native callbacks/vtables only: never real audio devices or
        # sessions. Tests the installed blocking mechanism without playback.
        if not config['product_logh']:
            result['synthetic_audio'] = guard.script.exports_sync.audioselftest()
            if (result['synthetic_audio']['originals'] != 0 or
                    any(result['synthetic_audio'][k] != 0 for k in ('first', 'repeat', 'duplicate', 'start'))):
                raise RuntimeError('Synthetic prestart audio blocking failed')
        if config['mode'] == 'guard-failure':
            raise RuntimeError('INJECTED_PRE_RESUME_FAILURE')
        if Path(config['cancel']).exists():
            result['status'] = 'CANCELLED'
            return result
        # Last operation before ResumeThread is a synchronous effective-UI + guard check.
        result['guard_receipts'].append(guard.assert_ready())
        result['guard_receipts'][-1]['recorded_mono'] = time.monotonic()
        wp.ResumeThread(thread)
        result['resumed'] = True
        started = time.monotonic()
        result['resumed_mono'] = started
        while we.WaitForSingleObject(process, 20) == we.WAIT_TIMEOUT:
            if Path(config['cancel']).exists():
                result['status'] = 'CANCELLED'
                return result
            elapsed = time.monotonic() - started
            if config['mode'] == 'exception' and elapsed > 0.1:
                raise RuntimeError('EXCEPTION_TEST')
            if config['mode'] == 'detach' and elapsed > 0.1:
                guard.session.detach()
            if elapsed > config['timeout']:
                raise TimeoutError('TIMEOUT')
            try:
                receipt = timed_receipt(guard)
                if config['product_logh']:
                    # Preserve actual loaded-module/API counters separately from
                    # fixture synthetic counters. Readiness is NOT coverage.
                    result['product_audio_observation'] = receipt
                result['guard_receipts'].append(receipt)
                if config['product_logh'] and observation_status(
                        True, elapsed, config.get('observe_seconds')) == 'PRODUCT_WINDOW_COMPLETE':
                    # Retained owned handle only. Hooks stay installed until signalled.
                    audio.stop_owned_handle(process)
                    result['status'] = 'PRODUCT_WINDOW_COMPLETE'
                    break
            except Exception as error:
                # Enumerating full helper identities races ordinary target exit.
                # Only a signalled retained process with the fixture's normal
                # exit code may finish; forced guard stops use 0xA001, not 0/7.
                if not config['product_logh'] and normal_fixture_exit(we.WaitForSingleObject(process, 200) == we.WAIT_OBJECT_0,
                                       wp.GetExitCodeProcess(process), guard.messages):
                    result['normal_exit_observation_race'] = str(error)
                    break
                raise
        result['exit_code'] = wp.GetExitCodeProcess(process)
        if result['status'] != 'PRODUCT_WINDOW_COMPLETE':
            if config['product_logh'] and config.get('observe_seconds') is not None:
                result['status'] = observation_status(False, 0, config['observe_seconds'])
            else:
                result['status'] = (('PRODUCT_OBSERVED' if config['product_logh'] else 'PASS')
                                    if result['exit_code'] == 0 else 'COMMAND_FAILED')
    except BaseException as error:
        result['error'] = str(error)
        result['status'] = 'TIMEOUT' if isinstance(error, TimeoutError) else 'FAIL'
    finally:
        # Stop target BEFORE detaching hooks; no audible uninstrumented interval.
        try:
            if inner:
                wj.TerminateJobObject(inner, 2)
            if process and not assigned:
                audio.stop_owned_handle(process)
            if process and we.WaitForSingleObject(process, 5000) != we.WAIT_OBJECT_0:
                raise RuntimeError('Target did not exit')
            if process:
                result['target_stopped_mono'] = time.monotonic()
            if inner:
                end = time.monotonic() + 5
                while wj.QueryInformationJobObject(inner, wj.JobObjectBasicAccountingInformation)['ActiveProcesses'] and time.monotonic() < end:
                    time.sleep(0.02)
                result['inner_active_after'] = wj.QueryInformationJobObject(inner, wj.JobObjectBasicAccountingInformation)['ActiveProcesses']
            result['cleanup_verified'] = result.get('inner_active_after', 0) == 0
        except BaseException as error:
            result['status'] = 'CLEANUP_FAILED'
            result['cleanup_error'] = str(error)
        if process and we.WaitForSingleObject(process, 0) != we.WAIT_OBJECT_0:
            # Never detach a live target after a failed stop. Keep hooks and
            # handles alive until supervisor's bounded timeout kills the outer
            # Job (Windows terminates its nested target Job first).
            result['status'] = 'CLEANUP_FAILED'
            Path(config['controller_report']).write_text(json.dumps(result, indent=2))
            while True:
                time.sleep(1)
        if guard:
            result['guard_messages'] = guard.messages
            guard.close()
            result['guard_detached_mono'] = time.monotonic()
        for handle in (thread, process, inner):
            if handle:
                handle.Close()
        Path(config['controller_report']).write_text(json.dumps(result, indent=2))
    return result


def run(args):
    report = {'status': 'FAIL', 'product_pass': False,
              'product_qa': 'NOT_RUN', 'games_executed': False, 'environment_ready': False,
              'cleanup_verified': False, 'remaining_owned_pids': [], 'owned_processes': []}

    pinned = None
    try:
        if os.name == 'nt' and sys.flags.no_site:
            site = supervisor_prefix()
            pinned = validate(args)
            supervisor_dependencies(site)
    except Exception as error:
        report['error'] = str(error)
        return report  # No Windows dependency import or owned process yet.

    import win32api as wa
    import win32con as wc
    import win32event as we
    import win32job as wj
    import win32process as wp
    import win32service as ws
    from qa_isolated_runner import snapshot, isolated
    from qa_session_mutex import SessionMutex
    import qa_prestart_audio as audio
    mutex = desk = outer = process = thread = None
    assigned = False
    observed = {}
    before = None
    try:
        if pinned is None:
            pinned = validate(args)
        report['manifest_sha256'] = args.manifest_sha256
        before = snapshot()
        report['before'] = before
        if not isolated(before):
            report['status'] = 'WAIT_ISOLATION_UNPROVEN'
            return report
        ids = session_ids()
        report['session_ids_before'] = ids
        mutex = SessionMutex(ids).acquire()
        report['mutexes'] = mutex.evidence
        if session_ids() != ids:
            raise RuntimeError('WAIT_SESSION_SET_CHANGED')
        if args.product_logh:
            protected = protected_processes()
            report['protected_processes'] = protected
            if protected:
                report['status'] = 'WAIT_USER_GAME_OR_STEAM'
                return report
        refs = [audio.identity(p) for p in args.reference_pid]
        report['reference_before'] = refs
        executable, app_root = target_spec(args)
        for directory in (args.temp_parent, app_root, args.repo):
            actual = audio._plain_path(directory)
            if not actual.is_relative_to(Path('E:/HermesQA')):
                raise ValueError('Use explicit private E:/HermesQA paths')
        if args.cancel_file and args.cancel_file.exists():
            report['status'] = 'CANCELLED'
            return report
        if shutil.disk_usage(args.temp_parent).free < 1280 * 1024 * 1024:
            raise RuntimeError('INSUFFICIENT_FREE_SPACE')
        root = args.temp_parent / uuid.uuid4().hex
        root.mkdir()
        report['owned_temp'] = str(root)  # Retained evidence; no shared TEMP deletion.
        (root / 'evidence').mkdir()
        report['run_id'] = root.name
        name = 'LOGH_PRESTART_' + root.name
        desk = ws.CreateDesktop(name, 0, 0x10000000, None)
        report['private_desktop'] = name
        outer = wj.CreateJobObject(None, name + '_OUTER')
        report['outer_policy'] = job_policy(outer)
        cancel = args.cancel_file or root / 'cancel'
        config = dict(site=pinned['site'], mode=args.mode, timeout=args.timeout,
                      observe_seconds=getattr(args, 'observe_seconds', None),
                      runtime_report=str(args.report),
                      product_logh=args.product_logh, executable=str(executable),
                      app_root=str(app_root), expected_runtime=pinned['app'],
                      target_args=pinned['target_args'],
                      desktop=before['winsta'] + '\\' + name, inner_job=name + '_TARGET',
                      controller_report=str(root / 'controller-result.json'), cancel=str(cancel))
        config_path = root / 'controller-config.json'
        config_path.write_text(json.dumps(config))
        report['config_sha256'] = digest(config_path)
        report['controller_config'] = config
        env = dict(os.environ, TEMP=str(root), TMP=str(root), LOGH_QA_FRIDA_TEMP=str(root),
                   LOGH_QA_TEMP_PARENT=str(args.temp_parent))
        command = controller_command(root, config_path, report['config_sha256'])
        report['command'] = command
        startup = wp.STARTUPINFO()
        startup.lpDesktop = config['desktop']
        process, thread, pid, _ = wp.CreateProcess(command[0], subprocess.list2cmdline(command),
                                                  None, None, False, wc.CREATE_SUSPENDED | wc.CREATE_NO_WINDOW,
                                                  env, str(args.repo), startup)
        wj.AssignProcessToJobObject(outer, process)
        assigned = True
        marker = {'controller_pid': pid, 'run_id': root.name}
        (root / '.logh-owned.json').write_text(json.dumps(marker))
        def observe():
            for member in wj.QueryInformationJobObject(outer, wj.JobObjectBasicProcessIdList):
                if member not in observed:
                    handle = wa.OpenProcess(0x1000 | 0x100000, False, member)
                    if not wj.IsProcessInJob(handle, outer):
                        handle.Close()
                        raise RuntimeError('Owned PID changed before retained handle')
                    observed[member] = (handle, str(wp.GetProcessTimes(handle)['CreationTime']))
        observe()
        wp.ResumeThread(thread)
        # A resumed product controller may launch the game before any report
        # exists. Preserve that possibility on supervisor failure/timeout.
        report['games_executed'] = bool(args.product_logh)
        start = time.monotonic()
        while we.WaitForSingleObject(process, 20) == we.WAIT_TIMEOUT:
            observe()
            if session_ids() != ids:
                raise RuntimeError('WAIT_SESSION_SET_CHANGED')
            if args.mode == 'cancel' and time.monotonic() - start > 3:
                cancel.touch(exist_ok=True)
            if time.monotonic() - start > args.timeout + 20:
                raise TimeoutError('CONTROLLER_TIMEOUT')
        observe()
        report['controller_exit'] = wp.GetExitCodeProcess(process)
        result = json.loads(Path(config['controller_report']).read_text())
        report['games_executed'] = bool(args.product_logh and result.get('target'))
        report['controller'] = result
        report['status'] = result['status']
        report['reference_processes_unchanged'] = refs == [audio.identity(p) for p in args.reference_pid]
        if not report['reference_processes_unchanged']:
            raise RuntimeError('REFERENCE_PROCESS_CHANGED')
    except BaseException as error:
        report['error'] = str(error)
        report['status'] = str(error) if str(error).startswith('WAIT_') else 'FAIL'
    finally:
        try:
            if outer:
                wj.TerminateJobObject(outer, 2)
            if process and not assigned:
                wa.TerminateProcess(process, 2)
            if process and we.WaitForSingleObject(process, 5000) != we.WAIT_OBJECT_0:
                raise RuntimeError('Controller did not exit')
            if outer:
                end = time.monotonic() + 5
                while wj.QueryInformationJobObject(outer, wj.JobObjectBasicAccountingInformation)['ActiveProcesses'] and time.monotonic() < end:
                    time.sleep(0.02)
                report['outer_active_after'] = wj.QueryInformationJobObject(outer, wj.JobObjectBasicAccountingInformation)['ActiveProcesses']
                report['remaining_owned_pids'] = [p for p, (h, _) in observed.items()
                                                if we.WaitForSingleObject(h, 5000) != we.WAIT_OBJECT_0]
                report['cleanup_verified'] = not report['remaining_owned_pids'] and report['outer_active_after'] == 0
                report['owned_processes'] = [{'pid': p, 'creation_time': t,
                    'signalled': p not in report['remaining_owned_pids']} for p, (_, t) in observed.items()]
                if not report['cleanup_verified']:
                    raise RuntimeError('Outer Job cleanup unproven')
            if before:
                report['after'] = snapshot()
                if report['after'] != before:
                    raise RuntimeError('Session/window station changed')
            if mutex:
                report['session_ids_after'] = session_ids()
                if report['session_ids_after'] != report['session_ids_before']:
                    raise RuntimeError('Session set changed')
            if args.product_logh:
                report['protected_processes_after'] = protected_processes()
            if report.get('owned_temp'):
                evidence = Path(report['owned_temp']) / 'evidence'
                report['evidence_files'] = [{'path': str(p), 'sha256': digest(p)}
                                             for p in sorted(evidence.rglob('*')) if p.is_file()]
        except BaseException as error:
            report['status'] = 'CLEANUP_FAILED'
            report['cleanup_error'] = str(error)
        finally:
            for h in [*(h for h, _ in observed.values()), thread, process, outer]:
                if h:
                    h.Close()
            if desk:
                desk.CloseDesktop()
            if mutex:
                mutex.close()
    return report


def main():
    if '--controller' in sys.argv:
        parser = argparse.ArgumentParser()
        parser.add_argument('--controller', type=Path, required=True)
        parser.add_argument('--config-sha256', required=True)
        args = parser.parse_args()
        if digest(args.controller) != args.config_sha256:
            raise RuntimeError('Controller config digest mismatch')
        return 0 if controller(json.loads(args.controller.read_text()))['status'] == 'PASS' else 2
    # -I does not import scripts beside the entry point automatically.
    sys.path.insert(0, str(HERE))
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('repo', 'temp-parent', 'manifest', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--fixture-dir', type=Path)
    parser.add_argument('--executable', type=Path)
    parser.add_argument('--app-root', type=Path)
    parser.add_argument('--port', type=int, default=20000)
    parser.add_argument('--manifest-sha256')
    parser.add_argument('--record-manifest', action='store_true')
    parser.add_argument('--product-logh', action='store_true')
    parser.add_argument('--mode', choices=('success', 'failure', 'timeout', 'exception', 'cancel', 'guard-failure', 'detach'), default='success')
    parser.add_argument('--timeout', type=float, default=5)
    parser.add_argument('--observe-seconds', type=int)
    parser.add_argument('--cancel-file', type=Path)
    parser.add_argument('--reference-pid', type=int, action='append', default=[])
    args = parser.parse_args()
    if not 0.2 <= args.timeout <= 30:
        parser.error('timeout must be 0.2..30 seconds')
    if args.observe_seconds is not None and (not args.product_logh or
            not 1 <= args.observe_seconds <= 25 or args.observe_seconds >= args.timeout):
        parser.error('observe-seconds requires product mode, 1..25 and less than timeout')
    if not 1024 <= args.port <= 65535:
        parser.error('local stub port must be 1024..65535')
    if args.product_logh and (args.executable is None or args.app_root is None):
        parser.error('product requires --executable and --app-root')
    if not args.product_logh and args.fixture_dir is None:
        parser.error('fixture requires --fixture-dir')
    if args.product_logh and args.mode != 'success':
        parser.error('fault injection modes are fixture-only')
    if args.record_manifest:
        if os.name == 'nt' and sys.flags.no_site:
            supervisor_prefix()
        executable, root = target_spec(args)
        with args.manifest.open('x') as stream:
            json.dump(inventory(args.repo, args.fixture_dir,
                                executable if args.product_logh else None, root, args.port), stream, indent=2)
        return 0
    with args.report.open('x') as stream:
        result = run(args)
        json.dump(result, stream, indent=2)
    print(json.dumps(result))
    return 0 if result['status'] == 'PASS' else (3 if result['status'].startswith('WAIT') else 2)


if __name__ == '__main__':
    raise SystemExit(main())
