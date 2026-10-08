"""Mute only an owned process's Core Audio sessions, without stopping playback."""
import ctypes as C
from contextlib import contextmanager
from uuid import UUID

P = C.c_void_p
HR = C.c_long
ole = C.WinDLL("ole32")
ole.CoInitializeEx.argtypes = [P, C.c_uint32]
ole.CoInitializeEx.restype = HR
ole.CoCreateInstance.argtypes = [P, P, C.c_uint32, P, C.POINTER(P)]
ole.CoCreateInstance.restype = HR
ole.CoTaskMemFree.argtypes = [P]


def guid(value):
    return C.create_string_buffer(UUID(value).bytes_le, 16)


def call(obj, slot, types=(), *args):
    address = C.cast(obj, C.POINTER(C.POINTER(P))).contents[slot]
    return C.WINFUNCTYPE(HR, P, *types)(address)(obj, *args)


def check(hr):
    if hr < 0:
        raise OSError(f"Core Audio HRESULT 0x{hr & 0xffffffff:08x}")


@contextmanager
def managed(pointer):
    try:
        yield pointer
    finally:
        if pointer:
            call(pointer, 2)  # IUnknown::Release


def query(obj, iid):
    result = P()
    check(call(obj, 0, (P, C.POINTER(P)), guid(iid), C.byref(result)))
    return result


class SessionMute:
    """Use on one thread, and close only after the owned game process stops."""
    def __init__(self, pid):
        self.pid = pid
        self.sessions = {}
        self.managers = []
        self.initialized = False
        hr = ole.CoInitializeEx(None, 0)  # MTA; reuse an existing STA if necessary.
        if hr != -2147417850:  # RPC_E_CHANGED_MODE
            check(hr)
            self.initialized = True
        try:
            enumerator = P()
            check(ole.CoCreateInstance(guid("bcde0395-e52f-467c-8e3d-c4579291692e"), None, 23,
                                       guid("a95664d2-9614-4f35-a746-de8db63617e6"), C.byref(enumerator)))
            with managed(enumerator):
                devices = P()
                check(call(enumerator, 3, (C.c_int, C.c_uint32, C.POINTER(P)), 0, 1, C.byref(devices)))
                with managed(devices):
                    count = C.c_uint32()
                    check(call(devices, 3, (C.POINTER(C.c_uint32),), C.byref(count)))
                    for index in range(count.value):
                        device = P()
                        check(call(devices, 4, (C.c_uint32, C.POINTER(P)), index, C.byref(device)))
                        with managed(device):
                            manager = P()
                            check(call(device, 3, (P, C.c_uint32, P, C.POINTER(P)),
                                       guid("77aa99a0-1bd6-484f-8bc7-2c654c9a9b6f"), 23, None, C.byref(manager)))
                            self.managers.append(manager)
        except Exception:
            self.close()
            raise

    def poll(self):
        for manager in self.managers:
            enumerator = P()
            check(call(manager, 5, (C.POINTER(P),), C.byref(enumerator)))
            with managed(enumerator):
                count = C.c_int()
                check(call(enumerator, 3, (C.POINTER(C.c_int),), C.byref(count)))
                for index in range(count.value):
                    control = P()
                    check(call(enumerator, 4, (C.c_int, C.POINTER(P)), index, C.byref(control)))
                    with managed(control), managed(query(control, "bfb7ff88-7239-4fc9-8fa2-07c950be9c6d")) as control2:
                        owner = C.c_uint32()
                        hr = call(control2, 14, (C.POINTER(C.c_uint32),), C.byref(owner))
                        # Refuse shared sessions (AUDCLNT_S_NO_SINGLE_PROCESS) and unrelated PIDs.
                        if hr != 0 or owner.value != self.pid:
                            continue
                        name = P()
                        check(call(control2, 13, (C.POINTER(P),), C.byref(name)))
                        try:
                            identity = C.wstring_at(name)
                        finally:
                            ole.CoTaskMemFree(name)
                        with managed(query(control, "87ce5498-68d6-44e5-9215-6da47ef883d8")) as volume:
                            muted = C.c_int()
                            check(call(volume, 6, (C.POINTER(C.c_int),), C.byref(muted)))
                            if identity not in self.sessions:
                                # Retain a reference to restore this session's prior mute state.
                                call(volume, 1)
                                self.sessions[identity] = {"volume": volume, "original_mute": bool(muted.value),
                                                           "active_while_muted": False, "peak_max": 0.0}
                            item = self.sessions[identity]
                            if not muted.value:
                                check(call(volume, 5, (C.c_int, P), 1, None))
                            check(call(volume, 6, (C.POINTER(C.c_int),), C.byref(muted)))
                            if not muted.value:
                                raise RuntimeError("Owned audio session did not stay muted")
                            state = C.c_int()
                            check(call(control, 3, (C.POINTER(C.c_int),), C.byref(state)))
                            item["active_while_muted"] |= state.value == 1
                            # Metering is optional; an active session is still observable without it.
                            meter = P()
                            if call(control, 0, (P, C.POINTER(P)), guid("c02216f6-8c67-4b5b-9d00-d008e73e0064"), C.byref(meter)) == 0:
                                with managed(meter):
                                    peak = C.c_float()
                                    check(call(meter, 3, (C.POINTER(C.c_float),), C.byref(peak)))
                                    item["peak_max"] = max(item["peak_max"], peak.value)
        return {"pid": self.pid, "session_count": len(self.sessions),
                "active_while_muted": any(s["active_while_muted"] for s in self.sessions.values()),
                "peak_max": max((s["peak_max"] for s in self.sessions.values()), default=0.0)}

    def close(self):
        errors = []
        for item in self.sessions.values():
            try:
                check(call(item["volume"], 5, (C.c_int, P), item["original_mute"], None))
                restored = C.c_int()
                check(call(item["volume"], 6, (C.POINTER(C.c_int),), C.byref(restored)))
                if bool(restored.value) != item["original_mute"]:
                    raise OSError("Audio session mute state was not restored")
            except OSError as error:
                errors.append(str(error))
            finally:
                call(item["volume"], 2)
        self.sessions.clear()
        for manager in self.managers:
            call(manager, 2)
        self.managers.clear()
        if self.initialized:
            ole.CoUninitialize()
            self.initialized = False
        return errors
