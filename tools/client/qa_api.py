"""Passive product API evidence collector and pure fail-closed contract judgment."""
import hashlib
import json
from pathlib import Path
import re

API_HOOKS = {
    **dict.fromkeys(('connect', 'WSAConnect'), 'ws2_32.dll'),
    **dict.fromkeys(('CreateProcessA', 'CreateProcessW'), 'kernel32.dll'),
    **dict.fromkeys(('CreateProcessAsUserA', 'CreateProcessAsUserW',
                     'CreateProcessWithLogonW', 'CreateProcessWithTokenW'), 'advapi32.dll'),
    **dict.fromkeys(('ShellExecuteA', 'ShellExecuteW', 'ShellExecuteExA', 'ShellExecuteExW'), 'shell32.dll'),
    **dict.fromkeys(('SwitchDesktop', 'SetForegroundWindow', 'BlockInput', 'SendInput',
                     'keybd_event', 'mouse_event', 'SetCursorPos'), 'user32.dll'),
}


def judge_api(obs, pinned_port, resumed, window_complete):
    """No files, Frida or Windows. Zero interaction calls may satisfy the contract.

    Void input APIs have no observable success return; a completed invocation
    cannot prove denial and therefore fails closed. No fabricated return values.
    """
    def verdict(status, reason):
        return dict(status=status, reason=reason)
    try:
        if (type(pinned_port) is not int or not 1024 <= pinned_port <= 65535 or
                type(resumed) is not bool or type(window_complete) is not bool):
            return verdict('FAIL', 'invalid API observation context')
        if (obs['ready'] is not True or not isinstance(obs['errors'], list) or obs['errors'] or
                not isinstance(obs['hooks'], list) or not isinstance(obs['calls'], list) or
                len(obs['calls']) > 8192):
            return verdict('FAIL', 'API hook/script failure or overflow')
        seen = set()
        for hook in obs['hooks']:
            api = hook['api']
            if (api in seen or api not in API_HOOKS or hook['module'] != API_HOOKS[api] or
                    hook['installed'] is not True or
                    not re.fullmatch(r'0x[0-9a-fA-F]+', hook['address']) or
                    not 0 < int(hook['address'], 16) <= 0xffffffff):
                return verdict('FAIL', 'missing/invalid API hook installation')
            seen.add(api)
        if seen != set(API_HOOKS):
            return verdict('FAIL', 'missing API hook installation')
        for call in obs['calls']:
            api, value = call['api'], call['return_value']
            if (api not in API_HOOKS or call['completed'] is not True or
                    not isinstance(call['caller_module'], str) or not call['caller_module'] or
                    type(call['caller_size']) is not int or not 0 < call['caller_size'] <= 0xffffffff or
                    not re.fullmatch(r'0x[0-9a-fA-F]+', call['caller_rva']) or
                    not 0 <= int(call['caller_rva'], 16) < call['caller_size']):
                return verdict('FAIL', 'unknown caller or incomplete API invocation')
            if api in ('keybd_event', 'mouse_event'):
                return verdict('FAIL', 'void input API invocation cannot prove denial')
            if type(value) is not int or not -0x80000000 <= value <= 0xffffffff:
                return verdict('FAIL', 'missing/invalid API return')
            if api in ('connect', 'WSAConnect'):
                if (call['family'] != 2 or type(call['family']) is not int or
                        call['address'] != '127.0.0.1' or type(call['port']) is not int or
                        call['port'] != pinned_port):
                    return verdict('FAIL', 'connect attempted outside pinned loopback endpoint')
            elif api in ('ShellExecuteA', 'ShellExecuteW'):
                if not 0 <= value <= 32:
                    return verdict('FAIL', 'ShellExecute success')
            elif value != 0:
                return verdict('FAIL', 'process creation or interaction API success')
        if obs['source'] != 'product' or not resumed or not window_complete or 'incomplete_reason' in obs:
            return verdict('NOT_RUN', 'product observation window not completed')
        return verdict('PASS', 'all API hooks installed; no external endpoint/process/input success')
    except (KeyError, TypeError, ValueError, AttributeError):
        return verdict('FAIL', 'missing/malformed API evidence')


class ApiCollector:
    """Separate script in the existing guarded session, installed before Resume."""
    def __init__(self, config, result):
        self.root = Path(config['controller_report']).parent
        self.result = result
        self.port = config.get('protocol_port', 20000)
        self.binding = dict(run_id=self.root.name, manifest_sha256=config['manifest_sha256'],
            config_sha256=hashlib.sha256((self.root / 'controller-config.json').read_bytes()).hexdigest(),
            target=result['target'])
        self.script = None
        self.errors = []
        self.saved = False
        self.last_observation = None

    def on_message(self, message, data):
        # Normal data is synchronous RPC. Async errors must never disappear.
        if message.get('type') == 'error':
            self.errors.append(str(message))

    def install(self, session):
        self.script = session.create_script(Path(__file__).with_name('qa_api_x86.js').read_text())
        self.script.on('message', self.on_message)
        self.script.load()
        obs = self.script.exports_sync.apistatus()
        self.last_observation = obs
        if judge_api(obs, self.port, False, False)['status'] != 'NOT_RUN' or self.errors:
            raise RuntimeError('API collector not ready')

    def finish(self, target_exited_early=False):
        if self.saved:
            return
        try:
            if self.script is None:
                raise RuntimeError('API script not installed')
            obs = self.script.exports_sync.apistatus()
            obs = dict(obs, errors=[*obs['errors'], *self.errors])
        except Exception as error:
            if target_exited_early and self.last_observation is not None:
                # A signalled retained target + incomplete window permits a
                # partial receipt, never evidence of complete-window coverage.
                obs = dict(self.last_observation, errors=[*self.last_observation['errors'], *self.errors],
                           incomplete_reason='target exited before completed observation window')
            else:
                obs = dict(source='product', ready=False, hooks=[], calls=[], errors=[str(error), *self.errors])
        path = self.root / 'evidence/api.json'
        with path.open('x', encoding='utf-8') as stream:
            json.dump(dict(self.binding, observation=obs), stream, indent=2)
        self.result['api_evidence'] = str(path)
        self.result['product_api_observation'] = obs
        self.saved = True
