"""No audio/network/input; optional denied private-desktop allocation probe."""
import argparse
import ctypes
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=('success', 'failure', 'tree', 'branch', 'hang', 'desktop-denial'), required=True)
    args = parser.parse_args()
    audio = [name for name in ('audioses.dll', 'mmdevapi.dll', 'dsound.dll', 'winmm.dll') if ctypes.windll.kernel32.GetModuleHandleW(name)]
    record = {'pid': os.getpid(), 'audio_modules': audio, 'silent_by_construction': not audio}
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenJobObjectW.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.c_wchar_p]
    kernel.OpenJobObjectW.restype = ctypes.c_void_p
    job = kernel.OpenJobObjectW(4, False, os.environ['LOGH_QA_JOB'])
    if not job:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        kernel.IsProcessInJob.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
        member = ctypes.c_int()
        if not kernel.IsProcessInJob(ctypes.c_void_p(-1), job, ctypes.byref(member)):
            raise ctypes.WinError(ctypes.get_last_error())
        flags = ctypes.c_uint()
        kernel.QueryInformationJobObject.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint, ctypes.c_void_p]
        if not kernel.QueryInformationJobObject(job, 4, ctypes.byref(flags), 4, None):
            raise ctypes.WinError(ctypes.get_last_error())
        record.update(in_named_job=bool(member.value), job_ui_flags=flags.value)
    finally:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(job)
    if args.mode == 'desktop-denial':
        user = ctypes.WinDLL('user32', use_last_error=True)
        user.CreateDesktopW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p]
        user.CreateDesktopW.restype = ctypes.c_void_p
        handle = user.CreateDesktopW('DENIED_' + str(os.getpid()), None, None, 0, 0x10000000, None)
        record['desktop_creation_denied'] = not handle
        if handle:
            user.CloseDesktop.argtypes = [ctypes.c_void_p]
            user.CloseDesktop(handle)
    Path(os.environ['TEMP'], 'fixture-' + str(os.getpid()) + '.json').write_text(json.dumps(record), encoding='utf-8')
    if args.mode in ('tree', 'branch'):
        subprocess.Popen([sys.executable, '-I', '-B', str(Path(__file__).resolve()), '--mode', 'branch' if args.mode == 'tree' else 'hang'])
        time.sleep(1)
    elif args.mode == 'hang':
        time.sleep(60)
    return 7 if args.mode == 'failure' else (9 if audio or not record['in_named_job'] or record['job_ui_flags'] != 255 or record.get('desktop_creation_denied') is False else 0)


if __name__ == '__main__':
    sys.exit(main())
