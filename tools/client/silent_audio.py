"""Process-owned memory-only DirectSound for explicitly requested local client QA."""
import hashlib
import threading
from pathlib import Path

TARGET_SHA256 = 'bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'


def _plain_path(path):
    import os,stat
    path=Path(os.path.abspath(path))
    for part in reversed((path,*path.parents)):
        st=part.lstat()
        if stat.S_ISLNK(st.st_mode) or getattr(st,'st_file_attributes',0)&0x400:
            raise RuntimeError('runtime symlink/reparse path unsupported')
        if part!=path and not stat.S_ISDIR(st.st_mode):
            raise RuntimeError('runtime ancestor is not a directory')
    return path

def _file_hash(path):
    import os,stat
    path=_plain_path(path)
    before=path.lstat()
    if not stat.S_ISREG(before.st_mode):raise RuntimeError('runtime non-regular file unsupported')
    with path.open('rb') as stream:
        opened=os.fstat(stream.fileno())
        if not stat.S_ISREG(opened.st_mode) or getattr(opened,'st_file_attributes',0)&0x400:
            raise RuntimeError('runtime non-regular/reparse file unsupported')
        identity=lambda s:(s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
        if identity(before)!=identity(opened):raise RuntimeError('runtime file changed during open')
        digest=hashlib.sha256()
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
        if identity(opened)!=identity(os.fstat(stream.fileno())):
            raise RuntimeError('runtime file changed during hashing')
    if identity(opened)!=identity(path.lstat()):raise RuntimeError('runtime file replaced during hashing')
    _plain_path(path)
    return digest.hexdigest()

def runtime_manifest(executable, extra_files=(), overlays=None):
    import os,stat
    executable=_plain_path(executable)
    root=executable.parent
    entries={};spellings={}
    def add(key,value,replace=False):
        folded=key.casefold()
        if folded in spellings and (not replace or spellings[folded]!=key):
            raise RuntimeError('ambiguous runtime filename')
        spellings[folded]=key;entries[key]=value
    def scan(directory):
        _plain_path(directory)
        with os.scandir(directory) as children:
            for child in children:
                p=Path(child.path);st=child.stat(follow_symlinks=False)
                if stat.S_ISLNK(st.st_mode) or getattr(st,'st_file_attributes',0)&0x400:
                    raise RuntimeError('runtime symlink/reparse entry unsupported')
                if p.resolve(strict=True).parent!=directory:
                    raise RuntimeError('runtime path escape')
                if stat.S_ISDIR(st.st_mode):scan(p)
                elif stat.S_ISREG(st.st_mode):
                    add('app/'+p.relative_to(root).as_posix(),_file_hash(p))
                else:raise RuntimeError('runtime non-regular entry unsupported')
        _plain_path(directory)
    scan(root)
    overlay_seen=set()
    for key,p in (overlays or {}).items():
        if not isinstance(key,str):raise RuntimeError('invalid runtime overlay')
        parts=key.split('/')
        if len(parts)<2 or parts[0]!='app' or any(
            x in {'','.','..'} or '\\' in x or ':' in x or x.endswith((' ','.'))
            for x in parts):
            raise RuntimeError('invalid runtime overlay')
        if key.casefold() in overlay_seen:raise RuntimeError('ambiguous runtime overlay')
        overlay_seen.add(key.casefold())
        # Existing intermediate destinations must be directories, never files/links.
        for n in range(2,len(parts)):
            parent=root.joinpath(*parts[1:n])
            if parent.exists() and not parent.is_dir():raise RuntimeError('invalid overlay parent')
        add(key,_file_hash(p),replace=True)
    for p in extra_files:
        p=_plain_path(p)
        add('injected/'+p.name,_file_hash(p))
    if 'app/'+executable.name not in entries:raise RuntimeError('missing executable')
    return dict(sorted(entries.items()))

def verify_runtime_manifest(executable, expected, extra_files=(), overlays=None):
    actual=runtime_manifest(executable,extra_files,overlays)
    if not expected or actual!=expected:
        raise RuntimeError('Exact runtime hash manifest mismatch')
    return actual


def strict_owned_job(job):
    import win32job
    info=win32job.QueryInformationJobObject(job,win32job.JobObjectExtendedLimitInformation)
    basic=info['BasicLimitInformation']
    basic['LimitFlags']=win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|win32job.JOB_OBJECT_LIMIT_ACTIVE_PROCESS
    basic['ActiveProcessLimit']=1
    win32job.SetInformationJobObject(job,win32job.JobObjectExtendedLimitInformation,info)
    actual=win32job.QueryInformationJobObject(job,win32job.JobObjectExtendedLimitInformation)['BasicLimitInformation']
    if (actual['ActiveProcessLimit']!=1 or actual['LimitFlags']!=basic['LimitFlags']):
        raise RuntimeError('Strict owned Job policy not applied; do not create/resume')
    return actual


def stop_owned_handle(process):
    """Idempotent retained-handle stop, including process-exit/detach races."""
    import win32process, win32event, win32con
    if win32event.WaitForSingleObject(process, 0) != win32con.WAIT_TIMEOUT:
        return
    try:
        win32process.TerminateProcess(process, 0xA001)
    except win32process.error:
        if win32event.WaitForSingleObject(process, 5000) != win32con.WAIT_OBJECT_0:
            raise


class SilentAudio:
    def __init__(self, pid, executable, on_failure, fixture=False):
        import win32api
        import win32process
        self.session = None
        self.script = None
        self.failure = None
        self.closing = False
        self.on_failure = on_failure
        if not fixture and hashlib.sha256(Path(executable).read_bytes()).hexdigest() != TARGET_SHA256:
            raise ValueError('Unsupported local client hash')
        handle = win32api.OpenProcess(0x410, False, pid)
        try:
            if Path(win32process.GetModuleFileNameEx(handle, 0)).resolve() != Path(executable).resolve():
                raise ValueError('Audio target is not the owned executable')
        finally:
            handle.Close()
        self.event = threading.Event()
        import frida
        try:
            self.session = frida.attach(pid)
            self.session.on('detached', self._detached)
            self.script = self.session.create_script(Path(__file__).with_name('silent-dsound.js').read_text(encoding='utf-8'))
            self.script.on('message', self._message)
            self.script.load()
            if not self.event.wait(10):
                raise TimeoutError('Silent audio installation did not finish before resume')
            self.assert_ready()
        except BaseException:
            self.close()
            raise

    def _detached(self, *args):
        if not self.closing:
            self.failure = 'Silent audio instrumentation detached'
            self.on_failure()

    def _message(self, message, data):
        payload = message.get('payload', {})
        if message.get('type') == 'error' or payload.get('type') == 'audio-fatal':
            self.failure = str(message)
            self.event.set()
            self.on_failure()
        if payload.get('type') == 'audio-ready':
            self.event.set()

    def assert_ready(self):
        if self.failure:
            raise RuntimeError(self.failure)
        status = self.script.exports_sync.audiostatus()
        if not status.get('ready') or status.get('failed') or status.get('mode') != 'memory-only-directsound':
            raise RuntimeError('Memory-only audio is not ready')
        return status

    def close(self):
        self.closing = True
        if self.session is not None:
            self.session.detach()
            self.session = None
