"""Owned D3D9 raw collector and pure rendering judgment; no Win32/Pillow imports."""
import hashlib
import json
import math
from pathlib import Path
import threading


def raw_layout(meta, raw):
    try:
        w, h, pitch, fmt = (meta[k] for k in ('width', 'height', 'pitch', 'format'))
        return (all(type(v) is int for v in (w, h, pitch, fmt)) and fmt in (21, 22) and
                1 <= w <= 4096 and 1 <= h <= 4096 and w * 4 <= pitch <= 32768 and len(raw) == pitch * h)
    except (KeyError, TypeError):
        return False


def frame_stddev(meta, raw):
    """Max RGB population stddev of an explicit 160x90 bilinear resize (BGRX).

    Ignore X/alpha and row padding. No image decoder or unpinned site dependency.
    Invalid/unsupported images cannot establish a valid frame.
    """
    try:
        w, h, pitch = (meta[k] for k in ('width', 'height', 'pitch'))
        if (any(type(v) is not int for v in (w, h, pitch, meta['format'])) or
                meta['format'] not in (21, 22) or not 400 <= w <= 4096 or
                not 300 <= h <= 4096 or not w * 4 <= pitch <= 32768 or
                len(raw) != pitch * h):
            return None
        sums, squares = [0.0] * 3, [0.0] * 3
        xs = []
        for x in range(160):
            px = (x + 0.5) * w / 160 - 0.5
            left = max(0, min(w - 1, int(px)))
            xs.append((left * 4, min(w - 1, left + 1) * 4, px - left))
        for y in range(90):
            py = (y + 0.5) * h / 90 - 0.5
            top = max(0, min(h - 1, int(py)))
            fy = py - top
            a, b = top * pitch, min(h - 1, top + 1) * pitch
            for left, right, fx in xs:
                for c in range(3):
                    v = ((raw[a + left + c] * (1 - fx) + raw[a + right + c] * fx) * (1 - fy) +
                         (raw[b + left + c] * (1 - fx) + raw[b + right + c] * fx) * fy)
                    sums[c] += v
                    squares[c] += v * v
        n = 160 * 90
        return max(math.sqrt(max(0, squares[c] / n - (sums[c] / n) ** 2)) for c in range(3))
    except (KeyError, TypeError, ValueError, IndexError):
        return None


def judge_render(obs, frames):
    """Pure judgment over parsed metadata and raw bytes; no filesystem/runtime."""
    def verdict(status, reason):
        return dict(status=status, reason=reason)
    try:
        if (not isinstance(obs['errors'], list) or obs['errors'] or
                any(type(obs[k]) is not int or obs[k] != 0 for k in ('device_failures', 'present_failures'))):
            return verdict('FAIL', 'device/Present failure or script exception')
        if (obs['ready'] is not True or obs['finished'] is not True or
                type(obs['present_count']) is not int or obs['present_count'] < 0):
            return verdict('FAIL', 'incomplete render collector')
        if obs['source'] != 'product':
            return verdict('NOT_RUN', 'not product evidence')
        valid = False
        for meta, raw in frames:
            if (meta['interface'] not in ('D3D9', 'D3D9Ex') or
                    type(meta['present_hr']) is not int or not 0 <= meta['present_hr'] < 0x80000000 or
                    type(meta['frame_no']) is not int or not 1 <= meta['frame_no'] <= obs['present_count']):
                return verdict('FAIL', 'invalid Present metadata')
            if not raw_layout(meta, raw):
                return verdict('FAIL', 'malformed raw layout')
            if hashlib.sha256(raw).hexdigest() != meta['raw_sha256']:
                return verdict('FAIL', 'frame raw hash mismatch')
            deviation = frame_stddev(meta, raw)
            if deviation is not None and deviation > 3:
                valid = True
        if obs['present_count'] == 0:
            if frames:
                return verdict('FAIL', 'frame without Present')
            return verdict('NOT_RUN', 'Present/D3D9 path not reached')
        return verdict('PASS' if valid else 'NOT_RUN',
                       'valid owned backbuffer' if valid else 'no valid frame')
    except (KeyError, TypeError, ValueError, AttributeError):
        return verdict('FAIL', 'missing/malformed render evidence')


def check_render(root):
    """Bound evidence reader; delegates all rendering criteria to the pure judge."""
    try:
        root = Path(root)
        envelope = json.loads((root / 'render.json').read_text())
        if not isinstance(envelope['frames'], dict) or set(envelope['frames']) - {'first', 'last'}:
            raise ValueError('Unknown frame slots')
        if 'first' in envelope['frames'] and 'last' not in envelope['frames']:
            raise ValueError('Last frame unavailable after a valid first frame')
        frames = []
        for label, expected in envelope['frames'].items():
            meta = json.loads((root / ('render-' + label + '.json')).read_text())
            for key in ('run_id', 'manifest_sha256', 'config_sha256', 'target'):
                if meta[key] != envelope[key]:
                    raise ValueError('Frame binding mismatch')
            if meta != expected:
                raise ValueError('Frame metadata mismatch')
            frames.append((meta, (root / ('render-' + label + '.bgrx')).read_bytes()))
        return judge_render(envelope['observation'], frames)
    except (OSError, KeyError, TypeError, ValueError, AttributeError):
        return dict(status='FAIL', reason='missing/malformed render evidence')


class RenderCollector:
    """Same-session separate script. Two bounded raw slots, never a frame archive."""
    def __init__(self, config, result):
        self.root = Path(config['controller_report']).parent
        self.result = result
        self.binding = dict(run_id=self.root.name, manifest_sha256=config['manifest_sha256'],
                            config_sha256=hashlib.sha256((self.root / 'controller-config.json').read_bytes()).hexdigest(),
                            target=result['target'])
        self.frames = {}
        self.errors = []
        self.finished = threading.Event()
        self.lock = threading.Lock()
        self.observation: dict | None = None
        self.script = None
        self.saved = False

    def install(self, session):
        self.script = session.create_script(Path(__file__).with_name('qa_render_x86.js').read_text())
        self.script.on('message', self.on_message)
        self.script.load()
        if self.script.exports_sync.renderstatus()['ready'] is not True or self.errors:
            raise RuntimeError('Render collector not ready')

    def on_message(self, message, data):
        with self.lock:
            try:
                payload = message.get('payload', {})
                if message.get('type') == 'error':
                    self.errors.append(str(message))
                elif payload.get('type') == 'render-frame':
                    meta = {k: payload[k] for k in ('format', 'width', 'height', 'pitch', 'interface', 'present_hr', 'frame_no')}
                    if data is None or len(data) > 32768 * 4096:
                        raise ValueError('Invalid raw frame length')
                    deviation = frame_stddev(meta, data)
                    # Unsupported format/size frames have no raw payload in JS.
                    # Only copy validated layouts to disk, even if spatially flat.
                    if not raw_layout(meta, data):
                        return
                    meta.update(self.binding, raw_sha256=hashlib.sha256(data).hexdigest())
                    slot = payload['slot']
                    labels = {'first': ['first'], 'last': ['last'], 'both': ['first', 'last']}.get(slot)
                    if labels is None:
                        raise ValueError('Unknown frame slot')
                    if 'first' in labels and (deviation is None or deviation <= 3):
                        raise ValueError('First slot is not valid')
                    for label in labels:
                        path = self.root / 'evidence' / ('render-' + label)
                        path.with_suffix('.bgrx').write_bytes(data)
                        path.with_suffix('.json').write_text(json.dumps(meta, indent=2))
                        self.frames[label] = dict(meta)
                elif payload.get('type') == 'render-finished':
                    self.observation = payload['observation']
                    self.finished.set()
            except BaseException as error:
                self.errors.append(str(error))

    def finish(self):
        if self.saved:
            return
        try:
            if self.script is None:
                raise RuntimeError('Render script not installed')
            self.script.exports_sync.finishrender()
            if not self.finished.wait(3):
                raise RuntimeError('Render message drain timed out')
        except Exception as error:
            self.errors.append(str(error))
        with self.lock:
            obs = self.observation or dict(source='product', ready=False, finished=False,
                                           present_count=0, present_failures=0, device_failures=0, errors=[])
            obs = dict(obs, errors=[*obs.get('errors', []), *self.errors])
            envelope = dict(self.binding, observation=obs, frames=self.frames)
            path = self.root / 'evidence/render.json'
            with path.open('x', encoding='utf-8') as stream:
                json.dump(envelope, stream, indent=2)
            self.result['render_evidence'] = str(path)
            self.saved = True
