"""Run a copied LOGH7 client on an inactive Windows desktop, then clean up.

Adapted from Breaking Point's CreateDesktop / lpDesktop / PrintWindow method.
Requires the existing Windows Python environment with pywin32 and Pillow.
No desktop switching, global input, installer, or system configuration writes.
"""
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import time
import traceback
import uuid

from PIL import Image, ImageStat
import win32api
import win32con
import win32event
import win32gui
import win32job
import win32process
import win32service
import win32ui

WORK = Path(__file__).resolve().parents[2] / "work"


def state():
    return {"foreground": win32gui.GetForegroundWindow(),
            "cursor": win32gui.GetCursorPos()}


def capture(hwnd, out):
    width, height = win32gui.GetClientRect(hwnd)[2:]
    if width < 100 or height < 100 or win32gui.IsIconic(hwnd):
        return {"hwnd": hwnd, "valid": False, "size": [width, height]}
    dc = win32gui.GetWindowDC(hwnd)
    src = win32ui.CreateDCFromHandle(dc)
    dst = src.CreateCompatibleDC()
    bitmap = win32ui.CreateBitmap()
    bitmap.CreateCompatibleBitmap(src, width, height)
    dst.SelectObject(bitmap)
    try:
        result = ctypes.windll.user32.PrintWindow(hwnd, dst.GetSafeHdc(), 3)
        im = Image.frombuffer("RGB", (width, height), bitmap.GetBitmapBits(True),
                              "raw", "BGRX", 0, 1).copy()
    finally:
        win32gui.DeleteObject(bitmap.GetHandle())
        dst.DeleteDC()
        src.DeleteDC()
        win32gui.ReleaseDC(hwnd, dc)
    sample = im.resize((160, 90))
    variation = max(ImageStat.Stat(sample).stddev)
    valid = bool(result and variation > 3 and len(sample.getcolors(160 * 90) or []) > 64)
    if valid:
        im.save(out)
    return {"hwnd": hwnd, "valid": valid, "size": [width, height],
            "printwindow_result": result, "variation": variation,
            "image": str(out) if valid else None}


def run(source, out, seconds, wrapper=None):
    source = source.resolve(strict=True)
    out = out.resolve()
    if not out.is_relative_to(WORK.resolve()) or out == WORK.resolve():
        raise ValueError("Output must be a new subdirectory under repository work/")
    if out.exists():
        raise ValueError("Use a fresh output directory; previous evidence is preserved")
    original = source / "exe" / "G7MTClient.exe"
    if not original.is_file():
        raise ValueError("Source root must contain exe/G7MTClient.exe")
    out.mkdir(parents=True)
    instance = out / "client"
    # Both exe/ and data/ are needed; relative paths follow the original updater.
    shutil.copytree(source, instance)
    executable = instance / "exe" / "G7MTClient.exe"
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    original_hash = digest(original)
    if digest(executable) != original_hash:
        raise RuntimeError("Copied client hash differs from source")
    report = {"source_sha256": original_hash, "desktop": "LOGH7_TEST_" + uuid.uuid4().hex,
              "before": state(), "captures": [], "packet_bytes": 0,
              "full_login_verified": False}
    if wrapper is not None:
        wrapper = wrapper.resolve(strict=True)
        destination = executable.parent / "d3d8.dll"
        if destination.exists():
            raise ValueError("Copied client already contains d3d8.dll; refusing to overwrite it")
        shutil.copy2(wrapper, destination)
        report["d3d8_wrapper"] = {"path": str(wrapper), "sha256": digest(destination)}
    desk = process = thread = job = connection = None
    received = bytearray()
    try:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.setblocking(False)
            port = listener.getsockname()[1]
            report["endpoint"] = f"127.0.0.1:{port}"
            desk = win32service.CreateDesktop(report["desktop"], 0, 0x10000000, None)
            startup = win32process.STARTUPINFO()
            startup.lpDesktop = "winsta0\\" + report["desktop"]
            startup.dwFlags = win32con.STARTF_USESHOWWINDOW | 0x80  # no startup cursor feedback
            startup.wShowWindow = win32con.SW_SHOWNOACTIVATE
            job = win32job.CreateJobObject(None, report["desktop"] + "_JOB")
            info = win32job.QueryInformationJobObject(job, win32job.JobObjectExtendedLimitInformation)
            info["BasicLimitInformation"]["LimitFlags"] = win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            win32job.SetInformationJobObject(job, win32job.JobObjectExtendedLimitInformation, info)
            argv = [str(executable), "127.0.0.1", str(port), "ginei00", "1", "dummy"]
            process, thread, pid, _ = win32process.CreateProcess(
                str(executable), subprocess.list2cmdline(argv), None, None, False,
                win32process.BELOW_NORMAL_PRIORITY_CLASS | win32con.CREATE_SUSPENDED,
                None, str(executable.parent), startup)
            win32job.AssignProcessToJobObject(job, process)
            win32process.ResumeThread(thread)
            report["pid"] = pid
            print(json.dumps({"pid": pid, "desktop": report["desktop"], "endpoint": report["endpoint"]}), flush=True)
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                if connection is None:
                    try:
                        connection, peer = listener.accept()
                        connection.setblocking(False)
                        report["peer"] = peer
                    except BlockingIOError:
                        pass
                if connection is not None:
                    try:
                        received.extend(connection.recv(4096))
                    except BlockingIOError:
                        pass
                if win32event.WaitForSingleObject(process, 0) != win32con.WAIT_TIMEOUT:
                    break
                time.sleep(0.2)
            for handle in desk.EnumDesktopWindows():
                hwnd = int(handle)
                if win32process.GetWindowThreadProcessId(hwnd)[1] == pid and win32gui.IsWindowVisible(hwnd):
                    report["captures"].append(capture(hwnd, out / f"window-{hwnd}.png"))
            report["exit_code_before_cleanup"] = win32process.GetExitCodeProcess(process)
    except Exception as error:
        report["error"] = str(error)
        report["traceback"] = traceback.format_exc()
    finally:
        if connection is not None:
            connection.close()
        # The handles belong to this invocation; never terminate by a borrowed PID.
        if process is not None:
            if win32event.WaitForSingleObject(process, 0) == win32con.WAIT_TIMEOUT:
                win32process.TerminateProcess(process, 0)
            win32event.WaitForSingleObject(process, 5000)
            report["process_stopped"] = win32event.WaitForSingleObject(process, 0) == win32con.WAIT_OBJECT_0
            process.Close()
        for handle in (thread, job):
            if handle is not None:
                handle.Close()
        if desk is not None:
            desk.CloseDesktop()
        if received:
            (out / "first-packet.bin").write_bytes(received)
        report["packet_bytes"] = len(received)
        report["packet_prefix"] = received[:4].hex()
        report["complete_first_0x34"] = (len(received) >= 4 and received[2:4] == b"\x00\x34"
                                         and int.from_bytes(received[:2], "big") + 2 == len(received))
        report["after"] = state()
        report["source_hash_unchanged"] = digest(original) == original_hash
        report["success"] = (any(c["valid"] and c["size"][0] >= 640 and c["size"][1] >= 480
                                 for c in report["captures"])
                             and report["complete_first_0x34"] and report.get("process_stopped", False)
                             and report["source_hash_unchanged"] and "error" not in report)
        (out / "run.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report), flush=True)
    return 0 if report["success"] else 2


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seconds", type=int, choices=range(5, 61), default=15)
    parser.add_argument("--d3d8-wrapper", type=Path, help="Optional locally verified DLL, copied only to the test instance")
    args = parser.parse_args()
    raise SystemExit(run(args.source_root, args.out, args.seconds, args.d3d8_wrapper))
