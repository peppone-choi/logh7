"""Run a copied LOGH7 client on an inactive Windows desktop, then clean up.

Adapted from Breaking Point's CreateDesktop / lpDesktop / PrintWindow method.
Requires the existing Windows Python environment with pywin32 and Pillow.
No desktop switching, global input, installer, or system configuration writes.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
import traceback
import uuid

from silent_audio import (SilentAudio, TARGET_SHA256, runtime_manifest,
                          stop_owned_handle, strict_owned_job, verify_runtime_manifest)

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


def activate_private(desk, pid):
    """Activate only the owned window's queue on its inactive desktop."""
    candidates = []
    for handle in desk.EnumDesktopWindows():
        hwnd = int(handle)
        if win32process.GetWindowThreadProcessId(hwnd)[1] == pid and win32gui.IsWindowVisible(hwnd):
            w, h = win32gui.GetClientRect(hwnd)[2:]
            if w >= 400 and h >= 300:
                candidates.append((w * h, hwnd))
    if not candidates:
        return False
    hwnd = max(candidates)[1]
    failures = []
    def worker():
        try:
            desk.SetThreadDesktop()
            current = win32api.GetCurrentThreadId()
            game_thread = win32process.GetWindowThreadProcessId(hwnd)[0]
            attached = ctypes.windll.user32.AttachThreadInput(current, game_thread, True)
            if not attached:
                raise RuntimeError("Cannot attach to owned private window queue")
            try:
                win32gui.SetActiveWindow(hwnd)
                win32gui.SetFocus(hwnd)
                win32gui.PostMessage(hwnd, win32con.WM_ACTIVATEAPP, 1, current)
                win32gui.PostMessage(hwnd, win32con.WM_ACTIVATE, win32con.WA_ACTIVE, 0)
            finally:
                ctypes.windll.user32.AttachThreadInput(current, game_thread, False)
        except Exception as error:
            failures.append(str(error))
    task = threading.Thread(target=worker)
    task.start()
    task.join(5)
    if task.is_alive() or failures:
        raise RuntimeError(f"Private activation failed: {failures}")
    return True


def owned_view(desk, pid):
    views = []
    for handle in desk.EnumDesktopWindows():
        hwnd = int(handle)
        if win32process.GetWindowThreadProcessId(hwnd)[1] != pid:
            continue
        def find_view(child, _):
            if (win32process.GetWindowThreadProcessId(child)[1] == pid
                    and win32gui.GetClassName(child) == "AfxFrameOrView42s"):
                views.append(child)
        win32gui.EnumChildWindows(hwnd, find_view, None)
    if len(views) != 1:
        raise RuntimeError(f"Expected one owned game view, found {len(views)}")
    return views[0]


def enter_login(desk, pid, script, account, credential):
    """Queue ASCII text and virtual Tab/Enter only to the owned private game view."""
    target = owned_view(desk, pid)
    for chunk in (account, "\t", credential, "\r"):
        for char in chunk:
            if char in "\t\r":
                code = ord(char)
                scan = win32api.MapVirtualKey(code, 0)
                script.exports_sync.setkey(code)
                try:
                    win32gui.PostMessage(target, win32con.WM_KEYDOWN, code, 1 | (scan << 16))
                    time.sleep(0.3)
                finally:
                    script.exports_sync.setkey(0)
                    win32gui.PostMessage(target, win32con.WM_KEYUP, code, 1 | (scan << 16) | (3 << 30))
            else:
                win32gui.PostMessage(target, win32con.WM_CHAR, ord(char), 0)
            time.sleep(0.05)
        time.sleep(0.4)


def run(source, out, seconds, wrapper=None, instance_root=None, render_capture=False, upstream_port=None, dxwrapper_dir=None, audible=False, ui_login=False, account="ginei00", credential="dummy", clicks=(), creation_menu=False, ui_control=False, silent_device=False, gui_group=None, manual_login=False):
    # No hardware audio or post-start session-mute fallback in background QA.
    if audible or not silent_device:
        print(json.dumps({"status": "WAITING_FOR_BACKGROUND_QA",
                          "reason": "Use --silent-device for process-local memory-only audio"}), flush=True)
        return 3
    import tempfile
    if Path(tempfile.gettempdir()).drive.casefold() != WORK.drive.casefold():
        raise ValueError("Set TEMP and TMP on the workspace drive before Frida attachment")
    overlays = {}
    if wrapper is not None:
        overlays['app/d3d8.dll'] = wrapper
    if dxwrapper_dir is not None:
        overlays = {'app/d3d8.dll': dxwrapper_dir / 'Stub/d3d8.dll',
                    'app/dxwrapper.dll': dxwrapper_dir / 'dxwrapper.dll',
                    'app/dxwrapper.ini': Path(__file__).with_name('dxwrapper.ini')}
    injected = [Path(__file__).with_name('silent-dsound.js')]
    if render_capture:
        injected.append(Path(__file__).with_name('capture-frame.js'))
    if ui_login:
        injected.append(Path(__file__).with_name('input-login.js'))
    if creation_menu:
        injected.append(Path(__file__).with_name('enable-creation-menu.js'))
    candidate = (instance_root if instance_root is not None else source) / 'exe/G7MTClient.exe'
    if hashlib.sha256(candidate.read_bytes()).hexdigest() != TARGET_SHA256:
        raise ValueError('Unsupported silent-device client hash')
    # Same-run integrity snapshot; this is not a remote QA approval.
    expected_runtime = runtime_manifest(candidate, injected, overlays)
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
    instance = instance_root.resolve(strict=True) if instance_root else out / "client"
    if instance_root:
        if not instance.is_relative_to(WORK.resolve()) or not (instance / "exe" / "G7MTClient.exe").is_file():
            raise ValueError("Reused instance must be an existing client copy under repository work/")
    else:
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
    if dxwrapper_dir is not None:
        bundle = dxwrapper_dir.resolve(strict=True)
        for relative in ("Stub/d3d8.dll", "dxwrapper.dll"):
            dependency = bundle / relative
            if not dependency.is_file():
                raise ValueError(f"Incomplete dxwrapper bundle: {relative}")
        shutil.copy2(bundle / "Stub/d3d8.dll", executable.parent / "d3d8.dll")
        shutil.copy2(bundle / "dxwrapper.dll", executable.parent / "dxwrapper.dll")
        shutil.copy2(Path(__file__).with_name("dxwrapper.ini"), executable.parent / "dxwrapper.ini")
        report["graphics_backend"] = "dxwrapper D3D8 -> D3D9Ex, windowed"
    if wrapper is not None:
        wrapper = wrapper.resolve(strict=True)
        destination = executable.parent / "d3d8.dll"
        if destination.exists():
            raise ValueError("Copied client already contains d3d8.dll; refusing to overwrite it")
        shutil.copy2(wrapper, destination)
        report["d3d8_wrapper"] = {"path": str(wrapper), "sha256": digest(destination)}
    desk = process = thread = job = connection = gui_mutex = None
    gui_mutex_held = False
    gui_lease = None
    session = script = None
    audio = None
    upstream = None
    received = bytearray()
    try:
        if gui_group is None:
            from qa_gui_group import assert_no_gui_group
            assert_no_gui_group()
            gui_mutex = win32event.CreateMutex(None, False, 'Local\\OpenClaw_GUI_TEST_SHARED_PEPPONE_CHOI_PC')
            gui_mutex_held = win32event.WaitForSingleObject(gui_mutex, 0) in (win32con.WAIT_OBJECT_0, win32con.WAIT_ABANDONED)
            if not gui_mutex_held:
                raise RuntimeError('Another owned GUI test holds the shared mutex')
        else:
            from qa_gui_group import GuiTestGroup
            if not isinstance(gui_group, GuiTestGroup):
                raise TypeError('Expected a live owned GUI group')
            gui_lease = gui_group.claim(instance)
            report['gui_group_owner'] = os.getpid()
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.setblocking(False)
            port = listener.getsockname()[1]
            report["endpoint"] = f"127.0.0.1:{port}"
            if upstream_port is not None:
                upstream = socket.create_connection(("127.0.0.1", upstream_port), timeout=2)
                upstream.settimeout(0.05)
                report["upstream_endpoint"] = f"127.0.0.1:{upstream_port}"
            desk = win32service.CreateDesktop(report["desktop"], 0, 0x10000000, None)
            startup = win32process.STARTUPINFO()
            startup.lpDesktop = "winsta0\\" + report["desktop"]
            startup.dwFlags = win32con.STARTF_USESHOWWINDOW | 0x80  # no startup cursor feedback
            startup.wShowWindow = win32con.SW_SHOWNOACTIVATE
            job = win32job.CreateJobObject(None, report["desktop"] + "_JOB")
            report["strict_job_policy"] = strict_owned_job(job)
            argv = [str(executable), "127.0.0.1", str(port), account]
            if not ui_login:
                argv.extend(["1", credential])
            report["verified_runtime_manifest"] = verify_runtime_manifest(executable, expected_runtime, injected)
            process, thread, pid, _ = win32process.CreateProcess(
                str(executable), subprocess.list2cmdline(argv), None, None, False,
                win32process.BELOW_NORMAL_PRIORITY_CLASS | win32con.CREATE_SUSPENDED,
                None, str(executable.parent), startup)
            report["root_creation_time"] = str(win32process.GetProcessTimes(process)["CreationTime"])
            win32job.AssignProcessToJobObject(job, process)
            audio = SilentAudio(pid, executable, on_failure=lambda: stop_owned_handle(process))
            report["audio_mode"] = 'memory-only-directsound'
            report["audio_handshake"] = audio.assert_ready()
            if render_capture:
                import frida
                session = frida.attach(pid)
                script_source = Path(__file__).with_name("capture-frame.js").read_text()
                if ui_login:
                    script_source += "\n" + Path(__file__).with_name("input-login.js").read_text()
                if creation_menu:
                    script_source += "\n" + Path(__file__).with_name("enable-creation-menu.js").read_text()
                    report["application_patch"] = "CD creation menu enable (two in-memory bytes)"
                script = session.create_script(script_source)
                def on_message(message, data):
                    payload = message.get("payload", {})
                    if payload.get("type") == "render-frame" and data:
                        frame = Image.frombytes("RGB", (payload["width"], payload["height"]), data,
                                                "raw", "BGRX", payload["pitch"], 1)
                        valid = max(ImageStat.Stat(frame.resize((160, 90))).stddev) > 3
                        if valid:
                            path = out / "render-frame.png"
                            frame.save(path)
                            report["captures"] = [item for item in report["captures"] if item.get("method") != "owned D3D9 backbuffer"]
                            report["captures"].append({"valid": True, "size": list(frame.size),
                                                       "image": str(path), "method": "owned D3D9 backbuffer"})
                    elif message.get("type") == "error" or payload.get("type") == "capture-error":
                        report["render_capture_error"] = message
                    elif payload.get("type") == "capture-status":
                        report.setdefault("render_status", []).append(payload["event"])
                    elif payload.get("type") == "login-error-ui":
                        report["login_failure_code"] = payload["code"]
                    elif payload.get("type") == "client-exception":
                        report["client_exception"] = payload
                    elif payload.get("type") == "render-trace":
                        with (out / "render-trace.jsonl").open("a", encoding="utf-8") as trace:
                            trace.write(json.dumps(payload) + "\n")
                script.on("message", on_message)
                script.load()
            if audio is not None:
                report["audio_handshake_before_resume"] = audio.assert_ready()
            win32process.ResumeThread(thread)
            report["pid"] = pid
            print(json.dumps({"pid": pid, "desktop": report["desktop"], "endpoint": report["endpoint"]}), flush=True)
            deadline = time.monotonic() + seconds
            started_at = deadline - seconds
            pending_clicks = iter(sorted(clicks))
            next_click = next(pending_clicks, None)
            control_offset = 0
            control_path = out / "ui-commands.jsonl"
            if ui_control:
                control_path.write_text("", encoding="utf-8")
            activate_at = time.monotonic() + 3
            private_active = False
            login_entered = False
            while time.monotonic() < deadline and (gui_group is None or not gui_group.stopping()):
                if audio is not None:
                    report["audio"] = audio.assert_ready()
                if not private_active and time.monotonic() >= activate_at:
                    private_active = activate_private(desk, pid)
                    report["private_window_activated"] = private_active
                if ui_login and private_active and not login_entered:
                    if not manual_login:
                        enter_login(desk, pid, script, account, credential)
                    login_entered = True
                    report["ui_login_entered"] = not manual_login
                    report["manual_login"] = manual_login
                if next_click is not None and time.monotonic() - started_at >= next_click[0]:
                    at, x, y = next_click
                    script.exports_sync.setmouse(x, y, True)
                    try:
                        time.sleep(0.3)
                    finally:
                        script.exports_sync.setmouse(x, y, False)
                    report.setdefault("ui_clicks", []).append({"at": at, "x": x, "y": y})
                    next_click = next(pending_clicks, None)
                if ui_control:
                    commands = control_path.read_bytes()
                    if len(commands) < control_offset:
                        raise ValueError("UI command file must remain append-only")
                    while (end := commands.find(b"\n", control_offset)) >= 0:
                        action = json.loads(commands[control_offset:end])
                        control_offset = end + 1
                        if len(action) != 1:
                            raise ValueError("Expected one UI command")
                        if "click" in action:
                            x, y = action["click"]
                            script.exports_sync.setmouse(x, y, True)
                            try:
                                time.sleep(0.3)
                            finally:
                                script.exports_sync.setmouse(x, y, False)
                            time.sleep(0.15) # Allow a release frame before another queued click.
                        elif "drag" in action or "rightDrag" in action:
                            button = 2 if "rightDrag" in action else 1
                            points = action["rightDrag" if button == 2 else "drag"]
                            if not isinstance(points, list) or len(points) != 4 or not all(type(v) is int and 0 <= v <= 4095 for v in points):
                                raise ValueError("Drag requires four bounded owned-window coordinates")
                            x, y, end_x, end_y = points
                            script.exports_sync.setmouse(x, y, True, button)
                            try:
                                time.sleep(0.3)
                                for step in range(1, 13):
                                    script.exports_sync.movemouse(round(x + (end_x-x)*step/12), round(y + (end_y-y)*step/12))
                                    time.sleep(0.05)
                            finally:
                                script.exports_sync.setmouse(end_x, end_y, False, button)
                            time.sleep(0.15)
                        elif "text" in action:
                            value = action["text"]
                            if not isinstance(value, str) or not 1 <= len(value) <= 30 or not all(32 <= ord(char) < 127 for char in value):
                                raise ValueError("Only short synthetic ASCII UI text is supported")
                            for char in value:
                                win32gui.PostMessage(owned_view(desk, pid), win32con.WM_CHAR, ord(char), 0)
                            time.sleep(0.15) # Let the owned view consume text before changing focus.
                        elif "key" in action and action["key"] in (8, 9, 13, 27, 67, 68, 88):
                            code = action["key"]
                            target = owned_view(desk, pid)
                            script.exports_sync.setkey(code)
                            try:
                                win32gui.PostMessage(target, win32con.WM_KEYDOWN, code, 0)
                                time.sleep(0.3)
                            finally:
                                script.exports_sync.setkey(0)
                                win32gui.PostMessage(target, win32con.WM_KEYUP, code, 3 << 30)
                        elif action == {"traceQuads": True}:
                            script.exports_sync.tracequads()
                        elif action == {"stop": True}:
                            deadline = time.monotonic()
                        else:
                            raise ValueError("Unsupported UI command")
                        report["ui_command_count"] = report.get("ui_command_count", 0) + 1
                if connection is None:
                    try:
                        connection, peer = listener.accept()
                        connection.setblocking(False)
                        report["peer"] = peer
                    except BlockingIOError:
                        pass
                if connection is not None:
                    try:
                        chunk = connection.recv(4096)
                        received.extend(chunk)
                        if chunk and upstream is not None:
                            upstream.sendall(chunk)
                    except BlockingIOError:
                        pass
                    if upstream is not None:
                        try:
                            reply = upstream.recv(4096)
                            if reply:
                                connection.sendall(reply)
                                report["server_bytes"] = report.get("server_bytes", 0) + len(reply)
                            else:
                                connection.shutdown(socket.SHUT_WR)
                                upstream.close()
                                upstream = None
                                report["upstream_eof"] = True
                        except socket.timeout:
                            pass
                if win32event.WaitForSingleObject(process, 0) != win32con.WAIT_TIMEOUT:
                    break
                time.sleep(0.05)
            report["exit_code_before_cleanup"] = win32process.GetExitCodeProcess(process)
            ctypes.windll.kernel32.SetLastError(0)
            try:
                windows = desk.EnumDesktopWindows()
            except Exception:
                if report["exit_code_before_cleanup"] == win32con.STILL_ACTIVE:
                    raise
                windows = []
            for handle in windows:
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
        if upstream is not None:
            upstream.close()
        if audio is not None:
            audio.closing = True  # Expected Job termination must not trigger detach failure.
        # Retained Job contains only this invocation, including children.
        if job is not None:
            win32job.TerminateJobObject(job, 0)
            active = 1  # fail closed even if the cleanup loop never executes
            cleanup_deadline = time.monotonic() + 5
            while time.monotonic() < cleanup_deadline:
                active = win32job.QueryInformationJobObject(job, win32job.JobObjectBasicAccountingInformation)["ActiveProcesses"]
                if not active:
                    break
                time.sleep(.05)
            report["job_active_after_cleanup"] = active
        # The handles belong to this invocation; never terminate by a borrowed PID.
        if process is not None:
            if win32event.WaitForSingleObject(process, 0) == win32con.WAIT_TIMEOUT:
                try:
                    win32process.TerminateProcess(process, 0)
                except win32process.error:
                    # A crash can finish between the wait and termination calls.
                    if win32process.GetExitCodeProcess(process) == win32con.STILL_ACTIVE:
                        raise
            win32event.WaitForSingleObject(process, 5000)
            report["process_stopped"] = win32event.WaitForSingleObject(process, 0) == win32con.WAIT_OBJECT_0
        if audio is not None:
            audio.close()
            report["audio_restore_errors"] = []  # adapter never changes volume/session state
        if process is not None:
            process.Close()
        if session is not None:
            try:
                session.detach()
            except Exception:
                pass
        for handle in (thread, job):
            if handle is not None:
                handle.Close()
        if desk is not None:
            desk.CloseDesktop()
        if gui_mutex is not None:
            if gui_mutex_held:
                win32event.ReleaseMutex(gui_mutex)
            gui_mutex.Close()
        if gui_lease is not None:
            gui_lease.close()
        if received:
            (out / "first-packet.bin").write_bytes(received)
        report["packet_bytes"] = len(received)
        report["packet_prefix"] = received[:4].hex()
        report["complete_first_0x34"] = (len(received) >= 4 and received[2:4] == b"\x00\x34"
                                         and int.from_bytes(received[:2], "big") + 2 <= len(received))
        report["after"] = state()
        report["source_hash_unchanged"] = digest(original) == original_hash
        report["success"] = (any(c["valid"] and c["size"][0] >= 400 and c["size"][1] >= 300
                                 for c in report["captures"])
                             and report["complete_first_0x34"] and report.get("process_stopped", False)
                             and report.get("exit_code_before_cleanup") == win32con.STILL_ACTIVE
                             and report.get("job_active_after_cleanup") == 0
                             and report["source_hash_unchanged"] and "error" not in report
                             and "client_exception" not in report
                             and "render_capture_error" not in report)
        if not audible:
            report["success"] &= report.get("audio", {}).get("ready", False)
            report["success"] &= not report.get("audio_restore_errors", [])
        (out / "run.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report), flush=True)
    return 0 if report["success"] else 2



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seconds", type=int, choices=range(5, 301), default=15)
    parser.add_argument("--d3d8-wrapper", type=Path, help="Optional locally verified DLL, copied only to the test instance")
    parser.add_argument("--instance-root", type=Path, help="Reuse an existing owned work/ copy instead of copying assets again")
    parser.add_argument("--render-capture", action="store_true", help="Use installed Frida to capture the owned D3D9 backbuffer")
    parser.add_argument("--upstream-port", type=int, help="Forward only to an existing localhost stub on this port")
    parser.add_argument("--dxwrapper-dir", type=Path, help="Verified unpacked dxwrapper bundle, applied only to the work/ copy")
    parser.add_argument("--audible", action="store_true", help="Rejected for background QA; memory-only audio requires --silent-device")
    parser.add_argument("--silent-device", action="store_true", help="Use process-local memory-only DirectSound objects; no real audio device or session is opened")
    parser.add_argument("--ui-login", action="store_true", help="Enter synthetic test credentials in the normal login UI; requires render capture")
    parser.add_argument("--ui-click", action="append", default=[], metavar="SECONDS:X:Y", help="Queue a virtual left click in the owned game window; repeatable, requires --ui-login")
    parser.add_argument("--enable-creation-menu", action="store_true", help="Explicitly enable the CD client's two disabled creation menus in owned process memory")
    parser.add_argument("--ui-control", action="store_true", help="Read append-only UI commands from OUT/ui-commands.jsonl; owned process only")
    parser.add_argument("--test-account", default="ginei00", help="ASCII synthetic account, 1-30 characters")
    parser.add_argument("--test-credential", default="dummy", help="ASCII synthetic credential, 1-10 characters")
    args = parser.parse_args()
    if args.upstream_port is not None and not 1 <= args.upstream_port <= 65535:
        parser.error("Upstream port must be between 1 and 65535")
    if args.d3d8_wrapper is not None and args.dxwrapper_dir is not None:
        parser.error("Choose one graphics wrapper")
    if args.ui_login and not args.render_capture:
        parser.error("UI login requires --render-capture for private virtual key state")
    try:
        clicks = [tuple(map(int, value.split(":"))) for value in args.ui_click]
        if any(len(value) != 3 or not 0 < value[0] < args.seconds or not all(0 <= coordinate <= 4095 for coordinate in value[1:]) for value in clicks):
            raise ValueError()
    except ValueError:
        parser.error("Use --ui-click SECONDS:X:Y within the run duration and game window")
    if clicks and not args.ui_login:
        parser.error("UI clicks require --ui-login")
    if args.enable_creation_menu and not args.ui_login:
        parser.error("Creation menu compatibility patch requires --ui-login")
    if args.ui_control and not args.ui_login:
        parser.error("UI command file requires --ui-login")
    for value, limit in ((args.test_account, 30), (args.test_credential, 10)):
        if not 1 <= len(value) <= limit or not all(33 <= ord(char) <= 126 for char in value):
            parser.error("Use nonempty printable ASCII test credentials within the documented limits, without spaces")
    raise SystemExit(run(args.source_root, args.out, args.seconds, args.d3d8_wrapper,
                         args.instance_root, args.render_capture, args.upstream_port, args.dxwrapper_dir, args.audible,
                         args.ui_login, args.test_account, args.test_credential, clicks, args.enable_creation_menu, args.ui_control, args.silent_device))
