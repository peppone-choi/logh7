"""Initial-login loopback recorder. No crypto, response synthesis or server launch.

Wire framing is length:BE16 (excluding itself), type:BE16, opaque body;
initial login has no clear_header (docs/protocol/session-login.md).
"""
import hashlib
import json
import math
from pathlib import Path
import select
import socket
import threading
import time

MAX_BYTES = 16 * 1024 * 1024
MAX_EVENTS = 16384
MAX_FRAMES = 4096
RAW_NAMES = {'c2s': 'protocol-c2s.bin', 's2c': 'protocol-s2c.bin'}


def port_number(port):
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError('Port must be 1024..65535')
    return port


def classify(c2s, s2c, events, *, upstream_port, connections, errors):
    """Pure stream/timeline assertion; completion order, not TCP recv boundaries.

    Valid partial handshakes are NOT_RUN. Corruption/errors override partial or
    complete observations, including when no upstream was configured.
    """
    frames = []
    def verdict(status, reason):
        return dict(status=status, reason=reason, frames=frames)
    if errors or type(connections) is not int or connections > 1 or connections < 0:
        return verdict('FAIL', 'Recorder error or additional connection: ' + repr(errors))
    if upstream_port is not None:
        port_number(upstream_port)
    streams = dict(c2s=c2s, s2c=s2c)
    offsets = dict(c2s=0, s2c=0)
    buffers = dict(c2s=bytearray(), s2c=bytearray())
    stage, previous_time = 0, -1.0
    expected = [('c2s', 0x34), ('s2c', 0x35), ('c2s', 0x36)]
    if len(c2s) + len(s2c) > MAX_BYTES or len(events) > MAX_EVENTS:
        return verdict('FAIL', 'Capture limit exceeded')
    for event in events:
        direction, offset, size, stamp = (event.get(k) for k in ('dir', 'offset', 'len', 't_mono'))
        if (direction not in streams or type(offset) is not int or type(size) is not int or size <= 0
                or offset != offsets[direction] or offset + size > len(streams[direction])
                or type(stamp) not in (float, int) or not math.isfinite(stamp) or stamp < previous_time):
            return verdict('FAIL', 'Malformed timeline/offset/time')
        previous_time = stamp
        offsets[direction] += size
        buf = buffers[direction]
        buf.extend(streams[direction][offset:offset + size])
        while len(buf) >= 2:
            length = int.from_bytes(buf[:2], 'big')
            if length < 2:
                return verdict('FAIL', 'Frame length excludes required type')
            if len(buf) < length + 2:
                break
            kind = int.from_bytes(buf[2:4], 'big')
            del buf[:length + 2]
            if len(frames) >= MAX_FRAMES:
                return verdict('FAIL', 'Frame count limit exceeded')
            frames.append(dict(dir=direction, type=kind, length=length))
            if stage < 3:
                if (direction, kind) != expected[stage]:
                    return verdict('FAIL', 'Handshake order/type violation')
                stage += 1
            elif kind == 0x30:
                if direction == 'c2s':
                    stage = 4
            else:
                return verdict('FAIL', 'Unexpected post-handshake outer type')
    if any(offsets[d] != len(streams[d]) or buffers[d] for d in streams):
        return verdict('FAIL', 'Truncated frame or unindexed bytes')
    if connections == 0 and (events or c2s or s2c):
        return verdict('FAIL', 'Bytes without a connection')
    if upstream_port is None:
        if s2c or stage > 1:
            return verdict('FAIL', 'Response bytes without upstream')
        return verdict('NOT_RUN', 'partial: first 0x34; no upstream' if stage else 'No upstream/connection')
    if stage == 4 and connections == 1:
        return verdict('PASS', 'Complete 0x34 -> 0x35 -> 0x36 -> C2S outer 0x30')
    return verdict('NOT_RUN', 'partial: first 0x34' if stage == 1 else 'Incomplete initial-login exchange')


class LoopbackRecorder:
    """Supervisor-owned stdlib thread; binds before any target is resumed.

    Bounded captures and socket timeouts fail closed on overload/backpressure.
    Extra connections are rejected and recorded, never proxied. No SO_REUSEADDR.
    """
    def __init__(self, evidence, port, upstream_port=None, *, host='127.0.0.1'):
        if host != '127.0.0.1':
            raise ValueError('Only 127.0.0.1 may be bound')
        self.port = port_number(port)
        self.upstream_port = None if upstream_port is None else port_number(upstream_port)
        if self.port == self.upstream_port:
            raise ValueError('Upstream must not be the recorder port')
        self.evidence = Path(evidence)
        self.connections = 0
        self.errors = []
        self.events = []
        self.offsets = dict(c2s=0, s2c=0)
        self.stop_event = threading.Event()
        self.thread = None
        self.listener = None
        self.files = {}

    def start(self):
        try:
            self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
                self.listener.setsockopt(socket.SOL_SOCKET, getattr(socket, 'SO_EXCLUSIVEADDRUSE'), 1)
            self.listener.bind(('127.0.0.1', self.port))
            self.listener.listen(2)
            self.listener.settimeout(0.2)
            for direction, name in RAW_NAMES.items():
                self.files[direction] = (self.evidence / name).open('xb')
            self.timeline = (self.evidence / 'protocol.jsonl').open('x', encoding='utf-8')
            self.thread = threading.Thread(target=self._run, name='logh-loopback-recorder', daemon=True)
            self.thread.start()
            return self
        except BaseException:
            self._close()
            raise

    def _record(self, direction, data):
        if sum(self.offsets.values()) + len(data) > MAX_BYTES or len(self.events) >= MAX_EVENTS:
            raise RuntimeError('Capture limit exceeded')
        event = dict(t_mono=time.monotonic(), dir=direction,
                     offset=self.offsets[direction], len=len(data))
        self.files[direction].write(data)
        self.timeline.write(json.dumps(event) + '\n')
        self.events.append(event)
        self.offsets[direction] += len(data)

    def _run(self):
        client = upstream = None
        try:
            assert self.listener is not None
            while True:
                readers = [self.listener, *([client] if client else []), *([upstream] if upstream else [])]
                ready, _, _ = select.select(readers, [], [], 0 if self.stop_event.is_set() else 0.02)
                if self.stop_event.is_set() and not ready:
                    break  # Drain bytes already available before finalizing.
                for source in ready:
                    if source is self.listener:
                        connection, _ = self.listener.accept()
                        self.connections += 1
                        if self.connections != 1:
                            connection.close()
                            self.errors.append('Additional connection')
                            return  # Already FAIL; do not accept unbounded reconnects.
                        client = connection
                        client.settimeout(0.2)
                        if self.upstream_port is not None:
                            upstream = socket.create_connection(('127.0.0.1', self.upstream_port), timeout=0.2)
                        continue
                    data = source.recv(65536)
                    if not data:
                        if source is upstream:
                            self.errors.append('Upstream disconnected')
                        # Continue listening to detect reconnects; never accept a new session.
                        for sock in (client, upstream):
                            if sock:
                                sock.close()
                        client = upstream = None
                        break
                    direction = 'c2s' if source is client else 's2c'
                    self._record(direction, data)
                    destination = upstream if source is client else client
                    if destination is not None:
                        destination.sendall(data)  # Exactly received bytes, no response generator.
        except Exception as error:
            self.errors.append(type(error).__name__ + ': ' + str(error))
        finally:
            for sock in (client, upstream):
                if sock:
                    sock.close()

    def _close(self):
        if self.listener:
            self.listener.close()
        for stream in [*self.files.values(), getattr(self, 'timeline', None)]:
            if stream:
                stream.close()

    def finish(self, binding):
        self.stop_event.set()
        if self.thread:
            self.thread.join(2)
            if self.thread.is_alive():
                if self.listener:
                    self.listener.close()
                self.thread.join(0.5)
                if not self.thread.is_alive():
                    self._close()
                raise RuntimeError('Recorder did not stop; evidence incomplete')
        self._close()
        c2s, s2c = (self.evidence.joinpath(RAW_NAMES[d]).read_bytes() for d in ('c2s', 's2c'))
        result = classify(c2s, s2c, self.events, upstream_port=self.upstream_port,
                          connections=self.connections, errors=self.errors)
        # Binary companions are unmodified wire bytes. This envelope binds each
        # companion (including JSONL) by hash to the same run/config/target.
        captures = {name: hashlib.sha256((self.evidence / name).read_bytes()).hexdigest()
                    for name in (*RAW_NAMES.values(), 'protocol.jsonl')}
        envelope = dict(binding, port=self.port, upstream_port=self.upstream_port,
                        connections=self.connections, errors=self.errors, captures=captures, result=result)
        with (self.evidence / 'protocol.json').open('x', encoding='utf-8') as stream:
            json.dump(envelope, stream, indent=2)
        return result


def check_evidence(evidence, config):
    """Recompute verdict from companions; caller first validates same-run hashes."""
    root = Path(evidence)
    envelope = json.loads((root / 'protocol.json').read_text(encoding='utf-8'))
    if (envelope['port'] != config['protocol_port'] or
            envelope['upstream_port'] != config['upstream_port']):
        raise ValueError('Protocol ports/config mismatch')
    if config['target_args'][:2] != ['127.0.0.1', str(envelope['port'])]:
        raise ValueError('Protocol listener differs from pinned target arguments')
    port_number(envelope['port'])
    if envelope['port'] == envelope['upstream_port']:
        raise ValueError('Protocol upstream loops into recorder')
    if set(envelope['captures']) != {*RAW_NAMES.values(), 'protocol.jsonl'}:
        raise ValueError('Protocol capture inventory mismatch')
    data = {}
    for name, expected in envelope['captures'].items():
        path = root / name
        if path.stat().st_size > MAX_BYTES:
            raise ValueError('Protocol capture limit exceeded')
        data[name] = path.read_bytes()
        if hashlib.sha256(data[name]).hexdigest() != expected:
            raise ValueError('Protocol companion hash mismatch')
    events = [json.loads(line) for line in data['protocol.jsonl'].decode('utf-8').splitlines()]
    result = classify(data['protocol-c2s.bin'], data['protocol-s2c.bin'], events,
                      upstream_port=envelope['upstream_port'], connections=envelope['connections'],
                      errors=envelope['errors'])
    if result != envelope['result']:
        raise ValueError('Protocol stored/recomputed verdict mismatch')
    return result
