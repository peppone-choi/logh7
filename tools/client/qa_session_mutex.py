"""Hold the legacy Local GUI mutex in every existing Windows session.

No broker/service, desktop action, privilege adjustment or shared runner edit.
Session 0's Local namespace is \\BaseNamedObjects, not \\Sessions\\0.
Failure to access any namespace is a WAIT, never permission to launch.
"""
import ctypes as C

NAME = 'OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC'


def object_path(session):
    if not isinstance(session, int) or session < 0:
        raise ValueError('Invalid session')
    return ('\\BaseNamedObjects\\' if session == 0 else
            '\\Sessions\\' + str(session) + '\\BaseNamedObjects\\') + NAME


class SessionMutex:
    def __init__(self, sessions):
        self.sessions = sorted(set(sessions))
        if not self.sessions or 0 not in self.sessions:
            raise ValueError('Session enumeration must include service session 0')
        self.handles = []
        self.evidence = []
        self.kernel = C.WinDLL('kernel32', use_last_error=True)
        self.nt = C.WinDLL('ntdll')
        class UnicodeString(C.Structure):
            _fields_ = [('Length', C.c_ushort), ('MaximumLength', C.c_ushort),
                        ('Buffer', C.c_void_p)]
        class Attributes(C.Structure):
            _fields_ = [('Length', C.c_ulong), ('RootDirectory', C.c_void_p),
                        ('ObjectName', C.POINTER(UnicodeString)), ('Attributes', C.c_ulong),
                        ('SecurityDescriptor', C.c_void_p), ('SecurityQualityOfService', C.c_void_p)]
        self.UnicodeString = UnicodeString
        self.Attributes = Attributes
        self.nt.NtCreateMutant.argtypes = [C.POINTER(C.c_void_p), C.c_ulong,
                                          C.POINTER(Attributes), C.c_ubyte]
        self.nt.NtCreateMutant.restype = C.c_long
        self.nt.NtQueryObject.argtypes = [C.c_void_p, C.c_ulong, C.c_void_p,
                                         C.c_ulong, C.POINTER(C.c_ulong)]
        self.nt.NtQueryObject.restype = C.c_long
        self.kernel.WaitForSingleObject.argtypes = [C.c_void_p, C.c_ulong]
        self.kernel.WaitForSingleObject.restype = C.c_ulong
        self.kernel.ReleaseMutex.argtypes = [C.c_void_p]
        self.kernel.CloseHandle.argtypes = [C.c_void_p]

    def acquire(self):
        try:
            for sid in self.sessions:
                path = object_path(sid)
                text = C.create_unicode_buffer(path)
                name = self.UnicodeString(len(path) * 2, (len(path) + 1) * 2,
                                          C.cast(text, C.c_void_p))
                attrs = self.Attributes(C.sizeof(self.Attributes), None, C.pointer(name),
                                        0x40 | 0x80, None, None)  # CASE_INSENSITIVE|OPENIF
                handle = C.c_void_p()
                status = self.nt.NtCreateMutant(C.byref(handle), 0x100001,
                                                C.byref(attrs), False)
                if status < 0 or not handle.value:
                    raise RuntimeError('WAIT_GUI_TEST_MUTEX_NAMESPACE: ' + path + ':' + hex(status & 0xffffffff))
                held = False
                try:
                    wait = self.kernel.WaitForSingleObject(handle, 0)
                    if wait not in (0, 128):
                        raise RuntimeError('WAIT_GUI_TEST_MUTEX: ' + path)
                    held = True
                    # Query the actual kernel object, not just the requested spelling.
                    buf = C.create_string_buffer(4096)
                    needed = C.c_ulong()
                    status = self.nt.NtQueryObject(handle, 1, buf, len(buf), C.byref(needed))
                    if status < 0:
                        raise RuntimeError('WAIT_GUI_TEST_MUTEX_NAME_UNPROVEN')
                    actual = C.cast(buf, C.POINTER(self.UnicodeString)).contents
                    observed = C.wstring_at(actual.Buffer, actual.Length // 2)
                    if observed.casefold() != path.casefold():
                        raise RuntimeError('WAIT_GUI_TEST_MUTEX_NAME_MISMATCH: ' + observed)
                    self.handles.append(handle)
                    self.evidence.append({'session': sid, 'object_path': observed,
                                          'wait': wait, 'acquired': True})
                except BaseException:
                    if held:
                        self.kernel.ReleaseMutex(handle)
                    self.kernel.CloseHandle(handle)
                    raise
            return self
        except BaseException:
            self.close()
            raise

    def close(self):
        for handle in reversed(self.handles):
            self.kernel.ReleaseMutex(handle)
            self.kernel.CloseHandle(handle)
        self.handles.clear()
