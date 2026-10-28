"""One GUI-test mutex owner coordinating two isolated client copies. Author: 최병호."""
import os
import threading
from pathlib import Path

NAME = r'Local\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC'
_registry_lock = threading.Lock()
_active_group = None


def assert_no_gui_group():
    with _registry_lock:
        if _active_group is not None:
            raise RuntimeError('A coordinated GUI test is already active in this process')


class GuiTestGroup:
    def __init__(self, reusable=False):
        self._reusable = reusable
        self._handle = None
        self._pid = os.getpid()
        self._owner = threading.current_thread()
        self._lock = threading.Lock()
        self._members = set()
        self._used = set()
        self._stop = threading.Event()

    def __enter__(self):
        import win32event
        global _active_group
        with _registry_lock:
            if _active_group is not None:
                raise RuntimeError('A coordinated GUI test is already active in this process')
            if os.getpid() != self._pid or threading.current_thread() is not self._owner or self._handle is not None:
                raise RuntimeError('Invalid GUI group owner')
            handle = win32event.CreateMutex(None, False, NAME)
            if win32event.WaitForSingleObject(handle, 0) not in (0, 128):
                handle.Close()
                raise RuntimeError('Another owned GUI test holds the shared mutex')
            self._handle = handle
            _active_group = self
            return self

    def claim(self, instance):
        key = str(Path(instance).resolve()).casefold()
        with self._lock:
            if self._stop.is_set() or self._handle is None or os.getpid() != self._pid or not self._owner.is_alive():
                raise RuntimeError('GUI group is not held by this process')
            if (key in self._members or (key in self._used and not self._reusable)
                    or (key not in self._used and len(self._used) >= 2)):
                raise RuntimeError('GUI group permits two distinct client copies only')
            self._used.add(key)
            self._members.add(key)
        return _Lease(self, key)

    def request_stop(self):
        self._stop.set()

    def stopping(self):
        return self._stop.is_set()

    def __exit__(self, *unused):
        import win32event
        global _active_group
        with self._lock:
            if os.getpid() != self._pid or threading.current_thread() is not self._owner:
                raise RuntimeError('Only the GUI group owner can release its mutex')
            if self._members:
                raise RuntimeError('Clients must finish cleanup before releasing GUI group')
            if self._handle is not None:
                win32event.ReleaseMutex(self._handle)
                self._handle.Close()
                self._handle = None
                with _registry_lock:
                    _active_group = None


class _Lease:
    def __init__(self, group, key):
        self.group, self.key = group, key

    def close(self):
        with self.group._lock:
            self.group._members.discard(self.key)
