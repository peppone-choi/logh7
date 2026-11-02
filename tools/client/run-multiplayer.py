"""Run two or three original client copies under one owned GUI test. Author: Peppone Choi."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import importlib.util
import json
from pathlib import Path
from qa_gui_group import GuiTestGroup


def run_client(background, source, copy, account, out, index, seconds, port, group, reconnects=0):
    """Reuse one copy only after the prior invocation proves full cleanup."""
    for attempt in range(reconnects + 1):
        destination = out / (f'client-{index}' if attempt == 0 else f'client-{index}-reconnect-{attempt}')
        print(json.dumps({'client': index, 'attempt': attempt, 'out': str(destination)}), flush=True)
        result = background.run(source, destination, seconds, instance_root=copy, render_capture=True,
            upstream_port=port, ui_login=True, account=account, credential='dummy', ui_control=True,
            silent_device=True, gui_group=group, manual_login=True)
        if result != 0:
            return result
        report = json.loads((destination / 'run.json').read_text(encoding='utf-8'))
        if report.get('reconnect_requested') is not True:
            return 0
        if (report.get('success') is not True or report.get('process_stopped') is not True
                or type(report.get('job_active_after_cleanup')) is not int
                or report['job_active_after_cleanup'] != 0 or report.get('source_hash_unchanged') is not True):
            raise RuntimeError('Reconnect requires successful process/Job cleanup and original hash preservation')
        if attempt >= reconnects:
            raise RuntimeError('Reconnect allowance exhausted')
    raise AssertionError('Unreachable reconnect state')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--instance-root', type=Path, action='append', required=True)
    parser.add_argument('--account', action='append', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--reconnect-client', type=int, choices=(0, 1, 2), help='Allow this owned copy to reconnect while its peers stay online')
    parser.add_argument('--max-reconnects', type=int, choices=range(1, 4), default=1)
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--upstream-port', type=int, default=47900)
    args = parser.parse_args()
    work = Path(__file__).resolve().parents[2] / 'work'
    copies = [p.resolve(strict=True) for p in args.instance_root]
    out = args.out.resolve()
    count = len(copies)
    if count not in (2, 3) or len(set(copies)) != count or len(args.account) != count or len(set(args.account)) != count:
        parser.error('Use two or three distinct client copies and as many distinct accounts')
    if args.reconnect_client is not None and args.reconnect_client >= count:
        parser.error('The reconnecting client must be one of the started copies')
    if any(not p.is_relative_to(work) or not (p/'exe/G7MTClient.exe').is_file() for p in copies):
        parser.error('Every copy must exist under repository work/')
    if out.exists() or not out.is_relative_to(work) or out == work:
        parser.error('Use a fresh output directory under repository work/')
    if not 30 <= args.seconds <= 1800 or not 1 <= args.upstream_port <= 65535:
        parser.error('Invalid duration or localhost upstream port')
    if any(not 1 <= len(a) <= 30 or not all(33 <= ord(c) <= 126 for c in a) for a in args.account):
        parser.error('Use short ASCII test accounts')
    spec = importlib.util.spec_from_file_location('background',Path(__file__).with_name('run-background.py'))
    background = importlib.util.module_from_spec(spec); spec.loader.exec_module(background)
    out.mkdir(parents=True)
    import frida
    device = frida.get_local_device()  # Initialize the shared manager before worker attachment.
    results = {}
    with GuiTestGroup(reusable=args.reconnect_client is not None, copies=count) as group, ThreadPoolExecutor(max_workers=count) as pool:
        futures = {pool.submit(run_client, background, args.source_root, copies[i], args.account[i], out,
            i, args.seconds, args.upstream_port, group,
            args.max_reconnects if i == args.reconnect_client else 0): i for i in range(count)}
        for future in as_completed(futures):
            index = futures[future]
            try: results[index] = future.result()
            except Exception as error: results[index] = str(error)
            if results[index] != 0:
                group.request_stop()
    summary = {'clients':results,'success':all(value == 0 for value in results.values())}
    (out/'group.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary),flush=True)
    return 0 if summary['success'] else 2


if __name__ == '__main__': raise SystemExit(main())
