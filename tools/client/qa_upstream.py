"""Owned D1 JVM lifetime around the existing client supervisor (Windows only).

No server/game is started by inventory or validation. Requires independent review
of the exact head before use. A runtime manifest is integrity, NOT approval.
"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import threading
import time
import uuid

PORTS = (47900, 47901, 47902, 47903)
MAIN = 'org.logh7.app.MainKt'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


def inventory(root):
    root = Path(root).resolve(strict=True)
    files = {}
    for p in sorted(root.rglob('*')):
        if p.is_symlink() or getattr(p.stat(), 'st_file_attributes', 0) & 0x400:
            raise ValueError('Reparse points refused')
        if p.is_file():
            files[p.relative_to(root).as_posix()] = digest(p)
    return files


def validate(args):
    from qa_prestart_audio import _plain_path
    manifest = _plain_path(args.upstream_manifest)
    if digest(manifest) != args.upstream_manifest_sha256:
        raise ValueError('Upstream external pin mismatch')
    data = json.loads(manifest.read_text(encoding='utf-8'))
    head = subprocess.check_output(['git', '-C', str(args.repo), 'rev-parse', 'HEAD'], text=True).strip()
    dirty = subprocess.check_output(['git', '-C', str(args.repo), 'status', '--porcelain'], text=True).strip()
    if data['head'] != head or dirty or data['main'] != MAIN:
        raise ValueError('Upstream requires exact clean head/MainKt')
    runtime = _plain_path(data['runtime'])
    if not runtime.is_relative_to(Path('E:/HermesQA/logh-runtime')):
        raise ValueError('Private upstream runtime required')
    for name in ('jdk', 'distribution'):
        root = _plain_path(runtime / name)
        if inventory(root) != data[name]['files']:
            raise ValueError('Upstream closure mismatch: ' + name)
    release = (runtime / 'jdk/release').read_text(encoding='utf-8')
    import re
    if not re.search(r'^JAVA_VERSION="25(?:[.+\-"]|$)', release, re.M):
        raise ValueError('JDK25 required')
    provenance = json.loads((runtime / 'distribution/distribution-provenance.json').read_text())
    if (provenance['head'] != head or provenance['build'] != ':app:installDist'
            or str(provenance['run_id']) != str(data['distribution']['run_id'])
            or provenance['files'] != inventory(runtime / 'distribution/server/app/build/install/app')):
        raise ValueError('Distribution provenance mismatch')
    if args.port != 20000 or args.upstream_port != 47900:
        raise ValueError('D1 requires recorder20000/upstream47900')
    return data, runtime


def environment(runtime, run):
    # Never inherit JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS, classpath, user state,
    # accounts or JVM agents. Windows bootstrap variables only.
    env = {k: os.environ[k] for k in ('SystemRoot', 'WINDIR', 'COMSPEC') if k in os.environ}
    env.update(TEMP=str(run / 'temp'), TMP=str(run / 'temp'),
               LOGH7_CAPTURE_DIR=str(run / 'capture'), LOGH7_BIND_ADDRESS='127.0.0.1',
               LOGH7_SESSION_ADDRESS='127.0.0.1', LOGH7_SESSION_PORT='47903')
    return env


def command(runtime, run):
    return [str(runtime / 'jdk/bin/java.exe'), '-Xmx256m', '-XX:ActiveProcessorCount=2',
            '-Djava.awt.headless=true', '-Duser.home=' + str(run / 'state'),
            '-Djava.io.tmpdir=' + str(run / 'temp'), '-cp',
            str(runtime / 'distribution/server/app/build/install/app/lib/*'), MAIN]


def reserve_check():
    sockets = []
    try:
        for port in (20000, *PORTS):
            s = socket.socket()
            sockets.append(s)
            if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
                s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            s.bind(('127.0.0.1', port))
    finally:
        for s in sockets:
            s.close()


def listener_owners():
    # IPv4 TCP_TABLE_OWNER_PID_LISTENER: DWORD count + six DWORDs/row.
    api = ctypes.WinDLL('iphlpapi', use_last_error=True).GetExtendedTcpTable
    api.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong), ctypes.c_bool,
                    ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
    api.restype = ctypes.c_ulong
    size = ctypes.c_ulong()
    rc = api(None, ctypes.byref(size), False, 2, 3, 0)
    if rc != 122:
        raise RuntimeError('TCP table sizing failed: ' + str(rc))
    buf = ctypes.create_string_buffer(size.value)
    if api(buf, ctypes.byref(size), False, 2, 3, 0):
        raise RuntimeError('TCP table read failed')
    raw = buf.raw
    owners = {}
    for i in range(struct.unpack_from('<I', raw)[0]):
        state, addr, port, _, _, pid = struct.unpack_from('<6I', raw, 4 + i * 24)
        if state == 2:
            owners[(socket.inet_ntoa(struct.pack('<I', addr)), socket.ntohs(port & 65535))] = pid
    return owners


def run(args, client):
    """Same thread callback: SessionMutex reentrancy is balanced, not delegated.

    Closing this non-inherited Job on supervisor death kills JVM descendants.
    A bounded watchdog also terminates the Job if SSH remains alive/disconnects.
    JVM cleanup is independently proven before client cleanup may remain true.
    """
    report = {'status': 'FAIL', 'product_qa': 'NOT_RUN', 'product_pass': False,
              'environment_ready': False, 'games_executed': False, 'cleanup_verified': False}
    proof = {'started': False, 'cleanup_verified': False}
    mutex = job = process = thread = timer = None
    files = []
    assigned = False
    expired = threading.Event()
    try:
        data, runtime = validate(args)
        import win32api as wa
        import win32con as wc
        import win32event as we
        import win32file as wf
        import win32job as wj
        import win32process as wp
        import pywintypes
        from qa_isolated_runner import snapshot, isolated
        from qa_prestart_runner import session_ids, protected_processes, job_policy
        from qa_session_mutex import SessionMutex
        before = snapshot()
        if not isolated(before):
            raise RuntimeError('WAIT_ISOLATION_UNPROVEN')
        ids = session_ids()
        mutex = SessionMutex(ids).acquire()
        proof['mutexes'] = mutex.evidence
        if protected_processes():
            raise RuntimeError('WAIT_USER_GAME_OR_STEAM')
        if wa.GlobalMemoryStatus()['AvailPhys'] < 2 * 1024 ** 3:
            raise RuntimeError('WAIT_LOW_MEMORY')
        if session_ids() != ids:
            raise RuntimeError('WAIT_SESSION_SET_CHANGED')
        reserve_check()  # Never kill a listener. Recheck ownership after startup.
        root = runtime / 'runs' / uuid.uuid4().hex
        root.mkdir(parents=True)
        proof['run'] = str(root)
        for name in ('temp', 'capture', 'state'):
            (root / name).mkdir()
        (root / 'stdin').touch()
        security = pywintypes.SECURITY_ATTRIBUTES()
        security.bInheritHandle = True
        for name, access, disposition in (('stdin', wc.GENERIC_READ, wc.OPEN_EXISTING),
                                           ('stdout', wc.GENERIC_WRITE, wc.CREATE_NEW),
                                           ('stderr', wc.GENERIC_WRITE, wc.CREATE_NEW)):
            files.append(wf.CreateFile(str(root / name), access, 0, security, disposition, 0, None))
        job = wj.CreateJobObject(None, None)  # No inherited/named Job handle.
        proof['job_policy'] = job_policy(job, target=True)
        startup = wp.STARTUPINFO()
        startup.dwFlags = wc.STARTF_USESTDHANDLES
        startup.hStdInput, startup.hStdOutput, startup.hStdError = files
        argv = command(runtime, root)
        proof['command'] = argv
        process, thread, pid, _ = wp.CreateProcess(argv[0], subprocess.list2cmdline(argv),
            None, None, True, wc.CREATE_SUSPENDED | wc.CREATE_NO_WINDOW | wc.CREATE_UNICODE_ENVIRONMENT,
            environment(runtime, root), str(root), startup)
        proof.update(pid=pid, creation_time=str(wp.GetProcessTimes(process)['CreationTime']),
                     retained_handle=True)
        wj.AssignProcessToJobObject(job, process)
        assigned = True
        if not wj.IsProcessInJob(process, job):
            raise RuntimeError('JVM Job ownership unproven')
        def watchdog():
            expired.set()
            try:
                if args.cancel_file:
                    args.cancel_file.touch(exist_ok=True)
            finally:
                wj.TerminateJobObject(job, 2)
        timer = threading.Timer(65, watchdog)
        timer.daemon = True
        timer.start()
        wp.ResumeThread(thread)
        proof['started'] = True
        end = time.monotonic() + 10
        while True:
            if args.cancel_file and args.cancel_file.exists():
                raise RuntimeError('CANCELLED')
            if we.WaitForSingleObject(process, 0) == 0:
                raise RuntimeError('UPSTREAM_EXITED')
            owners = listener_owners()
            if all(owners.get(('127.0.0.1', p)) == pid for p in PORTS):
                proof['listeners'] = {str(p): pid for p in PORTS}
                break
            if time.monotonic() >= end:
                raise TimeoutError('UPSTREAM_START_TIMEOUT')
            time.sleep(0.05)
        # No nested process owns our mutex. Existing client runs synchronously
        # on this exact thread and recursively acquires/releases the same objects.
        report = client()
        if expired.is_set() or we.WaitForSingleObject(process, 0) == 0:
            report['status'] = 'UPSTREAM_LIFETIME_FAILED'
            report['cleanup_verified'] = False
    except BaseException as error:
        report['error'] = str(error)
        report['status'] = str(error) if str(error).startswith('WAIT_') else 'FAIL'
    finally:
        if timer:
            timer.cancel()
            timer.join()
        try:
            if job:
                wj.TerminateJobObject(job, 2)
            if process and not assigned:
                wa.TerminateProcess(process, 2)  # Our suspended retained handle only.
            if process:
                proof['handle_signalled'] = we.WaitForSingleObject(process, 5000) == 0
            if job:
                end = time.monotonic() + 5
                while wj.QueryInformationJobObject(job, wj.JobObjectBasicAccountingInformation)['ActiveProcesses'] and time.monotonic() < end:
                    time.sleep(0.02)
                proof['active_processes_after'] = wj.QueryInformationJobObject(job, wj.JobObjectBasicAccountingInformation)['ActiveProcesses']
            proof['cleanup_verified'] = (bool(process) and proof.get('handle_signalled') is True
                                         and proof.get('active_processes_after') == 0)
            if process and not proof['cleanup_verified']:
                report['status'] = 'CLEANUP_FAILED'
            if not proof['cleanup_verified']:
                report['cleanup_verified'] = False
        except BaseException as error:
            proof['cleanup_error'] = str(error)
            report['status'] = 'CLEANUP_FAILED'
            report['cleanup_verified'] = False
        finally:
            for handle in (thread, process, job, *files):
                if handle:
                    handle.Close()
            if mutex:
                mutex.close()
        proof['manifest_sha256'] = getattr(args, 'upstream_manifest_sha256', None)
        proof['watchdog_expired'] = expired.is_set()
        report['upstream'] = proof
    return report
