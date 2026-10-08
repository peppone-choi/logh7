"""Observe selected models in one owned inactive-desktop run. Author: 최병호.

Requires an existing work/ client copy with the documented dxwrapper setup.
No game files are copied by this entry point. Windows execution is opt-in.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys


CD_SHA256 = 'bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'
MODEL = re.compile(r'[py][0-9]{3}')
EVENT = re.compile(r'[py][0-9]{3}-(?:loaded|error|[0-9]+-(?:primary|[12]-[0-9]+)-[0-9]+-(?:vertices|indices))')


def model_names(values):
    if not values or len(values) > 32 or len(set(values)) != len(values):
        raise ValueError('Specify 1..32 distinct models')
    if any(not MODEL.fullmatch(value) for value in values):
        raise ValueError('Model names must match p000..p999 or y000..y999')
    return values


def probe_source(names):
    return ('\nconst probeModels = ' + json.dumps(model_names(names)) + ';\n'
            + Path(__file__).with_suffix('.js').read_text(encoding='utf-8'))


class Recorder:
    def __init__(self, output):
        self.output = output
        self.events = []
        self.failures = []

    def receive(self, message, data):
        payload = message.get('payload', {})
        if payload.get('type') != 'analysis-model':
            return
        name = payload.get('name', '')
        if not EVENT.fullmatch(name):
            raise ValueError('Unexpected model event name')
        if len(self.events) >= 4096:
            raise ValueError('Too many model events')
        is_buffer = name.endswith(('-vertices', '-indices'))
        if is_buffer != (data is not None):
            raise ValueError('Unexpected model buffer payload')
        if data is not None and not 0 < len(data) <= 8 * 1024 * 1024:
            raise ValueError('Unexpected model buffer length')
        self.output.mkdir(parents=True, exist_ok=True)
        if data is not None:
            with (self.output / (name + '.bin')).open('xb') as target:
                target.write(data)
        self.events.append(payload)
        (self.output / 'model-events.json').write_text(
            json.dumps(self.events, indent=2) + '\n', encoding='utf-8')

    def complete(self, names):
        loaded = {e['name'][:-7] for e in self.events if e['name'].endswith('-loaded')}
        observed = {e['name'][:4] for e in self.events if e['name'].endswith('-vertices')}
        vertices = {e['name'][:-9] for e in self.events if e['name'].endswith('-vertices')}
        indices = {e['name'][:-8] for e in self.events if e['name'].endswith('-indices')}
        return (not self.failures and loaded == observed == set(names)
                and vertices == indices
                and not any(e['name'].endswith('-error') for e in self.events))


def run(args):
    if sys.platform != 'win32':
        raise ValueError('Native probe requires Windows; Wine is unverified')
    names = model_names(args.model)
    source = args.source_root.resolve(strict=True)
    instance = args.instance_root.resolve(strict=True)
    output = args.out.resolve()
    work = Path(__file__).resolve().parents[2] / 'work'
    if (output.exists() or output == work.resolve()
            or not output.is_relative_to(work.resolve())
            or not instance.is_relative_to(work.resolve())
            or instance.is_relative_to(source) or source.is_relative_to(instance)
            or output.is_relative_to(source) or source.is_relative_to(output)
            or output.is_relative_to(instance) or instance.is_relative_to(output)):
        raise ValueError('Use a fresh work/ output, separate from source and existing work/ copy')
    for root in (source, instance):
        executable = root / 'exe/G7MTClient.exe'
        if hashlib.sha256(executable.read_bytes()).hexdigest() != CD_SHA256:
            raise ValueError('Only the documented CD client profile is supported')
    for name in ('d3d8.dll', 'dxwrapper.dll', 'dxwrapper.ini'):
        if not (instance / 'exe' / name).is_file():
            raise ValueError('Existing client copy needs the documented dxwrapper setup')
    if not 5 <= args.seconds <= 300:
        raise ValueError('Duration must be 5..300 seconds')
    extra = probe_source(names)
    recorder = Recorder(output / 'model-probe')
    # Windows/Frida imports are deferred so help and static tests are portable.
    import frida
    actual_attach = frida.attach

    class ScriptProxy:
        def __init__(self, script):
            self.script = script

        def on(self, event, callback):
            def receive(message, data):
                try:
                    recorder.receive(message, data)
                except Exception as error:
                    recorder.failures.append(str(error))
                callback(message, data)
            return self.script.on(event, receive)

        def __getattr__(self, name):
            return getattr(self.script, name)

    class SessionProxy:
        def __init__(self, session):
            self.session = session

        def create_script(self, text):
            return ScriptProxy(self.session.create_script(text + extra))

        def __getattr__(self, name):
            return getattr(self.session, name)

    spec = importlib.util.spec_from_file_location('background_probe', Path(__file__).with_name('run-background.py'))
    launcher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(launcher)
    frida.attach = lambda pid: SessionProxy(actual_attach(pid))
    try:
        status = launcher.run(source, output, args.seconds, instance_root=instance, render_capture=True)
    finally:
        frida.attach = actual_attach
    complete = recorder.complete(names)
    print(json.dumps({'launcher_status': status, 'models_complete': complete,
                      'recording_errors': recorder.failures}, indent=2))
    return 0 if status == 0 and complete else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--instance-root', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--model', action='append', required=True)
    parser.add_argument('--seconds', type=int, default=20)
    args = parser.parse_args()
    try:
        return run(args)
    except (ValueError, OSError, ImportError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    raise SystemExit(main())
