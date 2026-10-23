"""Suspended-process x86 audio guard. Target coverage is independent of hook readiness."""
import hashlib,json,threading
from pathlib import Path
TARGET_SHA256='bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'
# No approved real-target manifest exists: recording hashes is not approval.
APPROVED_RUNTIME_MANIFEST = None
# Every regular file is inventoried, including extensionless/data/mutable files.
# There are NO mutable-output exclusions. Exact path spelling is part of approval.
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

def runtime_manifest(executable, extra_files=(), overlays=None, app_root=None):
    import os,stat
    executable=_plain_path(executable)
    root=_plain_path(app_root) if app_root is not None else executable.parent
    if not executable.is_relative_to(root):raise RuntimeError('executable outside app root')
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
    if 'app/'+executable.relative_to(root).as_posix() not in entries:raise RuntimeError('missing executable')
    return dict(sorted(entries.items()))

def verify_runtime_manifest(executable, expected, extra_files=(), overlays=None, app_root=None):
    actual=runtime_manifest(executable,extra_files,overlays,app_root)
    if not expected or actual!=expected:
        raise RuntimeError('Exact runtime hash manifest mismatch or unapproved')
    return actual

def readiness(executable=None, extra_files=(), overlays=None):
    result={'status':'WAITING_FOR_BACKGROUND_QA','prestart_audio_guaranteed':False,
            'adapter_implemented':True,'target_sha256':TARGET_SHA256,
            'target_coverage_verified':False,'exact_runtime_match':False,
            'reason':'Real DirectSound/WASAPI and complete target coverage unverified; no approved runtime manifest'}
    if executable is not None:
        try:
            result['actual_sha256']=hashlib.sha256(Path(executable).read_bytes()).hexdigest()
            result['exact_binary_match']=result['actual_sha256']==TARGET_SHA256
            result['runtime_manifest']=runtime_manifest(executable,extra_files,overlays)
            verify_runtime_manifest(executable,APPROVED_RUNTIME_MANIFEST,extra_files,overlays)
            result['exact_runtime_match']=True
        except (OSError,RuntimeError) as error:
            result['manifest_error']=str(error)
    return result

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


# V1 native Windows identities. Frida 17.11.0 helper bytes were captured in the
# harmless x64-controller/x86-target experiment; names alone are not approval.
LOGH_OUTER_ACTIVE_LIMIT = 5  # controller + target + three Frida helpers; conhosts are OS-accounted separately.
FRIDA_HELPER_SHA256 = {
 'frida-helper-x86_64.exe':'e4368b42c29ada8dba0a40e12cbe81afaf466611861f6e881797793b196ec90b',
 'frida-helper-x86.exe':'c5c9f451fffa6402144a57ca143c4fe5a7919eaaf63b7b39689cce4bd5cc8573'}
import ctypes as C,os,struct
K=C.WinDLL('kernel32',use_last_error=True); N=C.WinDLL('ntdll')
P=C.c_void_p; U=C.c_uint32; Z=C.c_size_t
K.QueryInformationJobObject.argtypes=[P,C.c_int,P,U,C.POINTER(U)];K.QueryInformationJobObject.restype=C.c_int
K.OpenProcess.argtypes=[U,C.c_int,U];K.OpenProcess.restype=P
K.CloseHandle.argtypes=[P]
K.GetProcessTimes.argtypes=[P,P,P,P,P]
K.QueryFullProcessImageNameW.argtypes=[P,U,C.c_wchar_p,C.POINTER(U)]
K.CreateFileW.argtypes=[C.c_wchar_p,U,U,P,U,U,P];K.CreateFileW.restype=P
K.GetFinalPathNameByHandleW.argtypes=[P,C.c_wchar_p,U,U];K.GetFinalPathNameByHandleW.restype=U
class BASIC(C.Structure):
 _fields_=[('exit',P),('peb',P),('affinity',P),('priority',P),('pid',P),('parent',P)]
N.NtQueryInformationProcess.argtypes=[P,U,P,U,C.POINTER(U)]
def canonical(path):
 h=K.CreateFileW(path,0,7,None,3,0x02000000,None)
 if h==P(-1).value:raise C.WinError(C.get_last_error())
 try:
  b=C.create_unicode_buffer(32768); n=K.GetFinalPathNameByHandleW(h,b,len(b),0)
  if not n or n>=len(b):raise C.WinError(C.get_last_error())
  return b.value.removeprefix('\\\\?\\').casefold()
 finally:K.CloseHandle(h)
def identity(pid):
 h=K.OpenProcess(0x1000|0x400,False,pid)
 if not h:raise C.WinError(C.get_last_error())
 try:
  vals=[C.c_uint64() for _ in range(4)]
  if not K.GetProcessTimes(h,*[C.byref(v) for v in vals]):raise C.WinError(C.get_last_error())
  b=C.create_unicode_buffer(32768);n=U(len(b))
  if not K.QueryFullProcessImageNameW(h,0,b,C.byref(n)):raise C.WinError(C.get_last_error())
  basic=BASIC()
  if N.NtQueryInformationProcess(h,0,C.byref(basic),C.sizeof(basic),None)!=0:raise RuntimeError('parent query failed')
  return {'pid':pid,'creation_filetime':vals[0].value,'canonical_path':canonical(b.value),'parent_pid':basic.parent or 0,'image_sha256':_file_hash(b.value)}
 finally:K.CloseHandle(h)
def members(job):
 b=C.create_string_buffer(8+8*1024);needed=U()
 if not K.QueryInformationJobObject(int(job) if job is not None else None,3,b,len(b),C.byref(needed)):raise C.WinError(C.get_last_error())
 assigned,count=struct.unpack_from('<II',b)
 if count!=assigned:raise RuntimeError('truncated member list')
 return [identity(int(struct.unpack_from('<Q',b,8+i*8)[0])) for i in range(count)]
def key(i):return (i['pid'],i['creation_filetime'],i['canonical_path'],i['parent_pid'],i['image_sha256'])

def launcher_baseline(target_pid):
    controller=identity(os.getpid());target=identity(target_pid);current=members(None)
    console=canonical(str(Path(os.environ['SystemRoot'])/'System32/conhost.exe'))
    hosts=[i for i in current if i['canonical_path']==console and i['parent_pid']==controller['pid']
           and i['creation_filetime']>=controller['creation_filetime']]
    expected=[controller,target]+hosts
    if len(hosts)>1 or {key(i) for i in expected}!={key(i) for i in current} or target['parent_pid']!=controller['pid']:
        raise RuntimeError('Unexpected pre-attach outer Job identity')
    return expected
def verify_frida_helpers(baseline,current,controller_pid=None,temp_root=None):
    import re
    expected_keys={key(i) for i in baseline}; current_keys={key(i) for i in current}
    if not expected_keys<=current_keys:raise RuntimeError('Pre-attach member identity lost/changed')
    added=[i for i in current if key(i) not in expected_keys]
    controller=next(i for i in baseline if i['pid']==(controller_pid or os.getpid()))
    console=canonical(str(Path(os.environ['SystemRoot'])/'System32/conhost.exe'))
    helpers=[i for i in added if Path(i['canonical_path']).name in FRIDA_HELPER_SHA256]
    if len(helpers)!=3:raise RuntimeError('Expected exactly three verified Frida helpers')
    dirs={str(Path(i['canonical_path']).parent) for i in helpers}
    if temp_root is None:require_owned_frida_temp()
    temp=canonical(str(temp_root) if temp_root is not None else os.environ['TEMP'])
    if len(dirs)!=1:raise RuntimeError('Mixed Frida helper directories')
    directory=Path(next(iter(dirs)))
    if str(directory.parent)!=temp or not re.fullmatch(r'frida-[0-9a-f]{1,32}',directory.name):
        raise RuntimeError('Unexpected Frida helper canonical directory')
    for i in helpers:
        if i['image_sha256']!=FRIDA_HELPER_SHA256[Path(i['canonical_path']).name]:
            raise RuntimeError('Frida helper exact hash mismatch')
        if i['creation_filetime']<controller['creation_filetime']:
            raise RuntimeError('Borrowed Frida helper identity')
    roots=[i for i in helpers if i['parent_pid']==controller['pid']
           and Path(i['canonical_path']).name=='frida-helper-x86_64.exe']
    if len(roots)!=1:raise RuntimeError('Unexpected Frida helper parent topology')
    root=roots[0]
    children=[i for i in helpers if i['parent_pid']==root['pid']]
    if sorted(Path(i['canonical_path']).name for i in children)!=['frida-helper-x86.exe','frida-helper-x86_64.exe']:
        raise RuntimeError('Unexpected Frida helper child topology')
    hosts=[i for i in added if i['canonical_path']==console and i['parent_pid']==root['pid']
           and i['creation_filetime']>=root['creation_filetime']]
    approved=baseline+helpers+hosts
    if len(hosts)!=1 or {key(i) for i in approved}!=current_keys:
        raise RuntimeError('Unexpected outer Job process; do not resume target')
    return approved

class AudioGuard:
    def __init__(self,pid,executable,fixture=False,timeout=5,source=None,on_failure=None,extra_files=(),verify_helpers=False,expected_runtime=None,app_root=None):
        self.session=None;self.script=None;self.messages=[];self.failure=None
        self.pid=pid;self.fixture=fixture;self.executable=Path(executable)
        self.on_failure=on_failure;self.closing=False
        if not fixture and hashlib.sha256(self.executable.read_bytes()).hexdigest()!=TARGET_SHA256:
            raise RuntimeError('Unsupported audio target hash')
        if not fixture:
            # Integrity input must be independently pinned by the supervisor;
            # no implicit approval or mutable-output exclusions.
            verify_runtime_manifest(self.executable,expected_runtime,extra_files,app_root=app_root)
        if not fixture and on_failure is None:
            raise RuntimeError('Owned-handle failure cleanup callback is required')
        # Verify the PID image matches the caller's path before injection; no borrowed image.
        import win32api,win32process
        image_handle=win32api.OpenProcess(0x410,False,pid)
        try:
            actual_image=Path(win32process.GetModuleFileNameEx(image_handle,0)).resolve()
            self.creation_time=str(win32process.GetProcessTimes(image_handle)['CreationTime'])
            if actual_image!=self.executable.resolve():
                raise RuntimeError('PID executable does not match owned audio target')
        finally:
            image_handle.Close()
        require_owned_frida_temp()
        import frida
        if frida.__version__!='17.11.0':
            raise RuntimeError('Unverified Frida version; keep readiness closed')
        self.helper_identities=None
        self.verify_helpers=not fixture or verify_helpers
        self.ready_event=threading.Event()
        try:
            baseline=launcher_baseline(pid) if self.verify_helpers else None
            self.session=frida.attach(pid)
            if self.verify_helpers:
                self.helper_identities=verify_frida_helpers(baseline,members(None))
            # Losing instrumentation must kill the owned process through retained Job/handle.
            self.session.on('detached',self._detached)
            text=source if source is not None else Path(__file__).with_name('qa_prestart_silence_x86.js').read_text()
            if fixture:text='globalThis.AUDIO_FIXTURE=true;\n'+text
            self.script=self.session.create_script(text)
            self.script.on('message',self._message)
            self.script.load()
            if not self.ready_event.wait(timeout):
                raise TimeoutError('Audio ready handshake timed out; do not ResumeThread')
            self.assert_ready()
        except BaseException:
            self.close()
            raise
    def _detached(self,*args):
        self.failure='Frida session detached'
        if not self.closing and self.on_failure:self.on_failure()
    def _message(self,message,data):
        self.messages.append(message)
        payload=message.get('payload',{})
        if message.get('type')=='error' or payload.get('type')=='audio-fatal':
            self.failure=str(message);self.ready_event.set()
            if self.on_failure:self.on_failure()
        if payload.get('type')=='audio-ready':
            self.receipt=payload;self.ready_event.set()
    def assert_ready(self):
        if self.failure:raise RuntimeError(self.failure)
        if self.helper_identities is not None and {key(i) for i in members(None)}!={key(i) for i in self.helper_identities}:
            if self.on_failure:self.on_failure()
            raise RuntimeError('Outer Job helper identities changed')
        receipt=self.script.exports_sync.audiostatus()
        if not receipt.get('ready') or receipt.get('failed') or receipt.get('arch')!='ia32':
            raise RuntimeError('Audio guard not ready')
        if receipt.get('effective_ui_flags') != 255:
            if self.on_failure:self.on_failure()
            raise RuntimeError('Target immediate Job UI restrictions not effective')
        return receipt
    def close(self):
        self.closing=True
        if self.session is not None:
            try:self.session.detach()
            except Exception:pass
            self.session=None

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



def require_owned_frida_temp():
    """Explicit private extraction root supplied by the owning supervisor."""
    import os
    root = _plain_path(os.environ['LOGH_QA_FRIDA_TEMP'])
    parent = _plain_path(os.environ['LOGH_QA_TEMP_PARENT'])
    if root.parent != parent or len(root.name) != 32:
        raise RuntimeError('Private Frida temp path mismatch')
    if any(Path(os.environ.get(n, '')) != root for n in ('TEMP', 'TMP')):
        raise RuntimeError('Private TEMP/TMP mismatch')
    marker = json.loads((root / '.logh-owned.json').read_text())
    if marker['controller_pid'] != os.getpid() or marker['run_id'] != root.name:
        raise RuntimeError('Private temp controller mismatch')
    return marker
