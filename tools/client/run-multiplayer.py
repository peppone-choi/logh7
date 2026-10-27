"""Run two original client copies under one owned GUI test. Author: 최병호."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import importlib.util
import json
from pathlib import Path
from qa_gui_group import GuiTestGroup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--instance-root', type=Path, action='append', required=True)
    parser.add_argument('--account', action='append', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--upstream-port', type=int, default=47900)
    args = parser.parse_args()
    work = Path(__file__).resolve().parents[2] / 'work'
    copies = [p.resolve(strict=True) for p in args.instance_root]
    out = args.out.resolve()
    if len(copies) != 2 or len(set(copies)) != 2 or len(args.account) != 2 or len(set(args.account)) != 2:
        parser.error('Use two distinct client copies and two distinct accounts')
    if any(not p.is_relative_to(work) or not (p/'exe/G7MTClient.exe').is_file() for p in copies):
        parser.error('Both copies must exist under repository work/')
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
    with GuiTestGroup() as group, ThreadPoolExecutor(max_workers=2) as pool:
        futures = {pool.submit(background.run,args.source_root,out/f'client-{i}',args.seconds,
                    instance_root=copies[i],render_capture=True,upstream_port=args.upstream_port,
                    ui_login=True,account=args.account[i],credential='dummy',ui_control=True,
                    silent_device=True,gui_group=group,manual_login=True): i for i in range(2)}
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
