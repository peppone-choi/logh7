// Owned target D3D9/9Ex backbuffer readback. Observation only; no UI mutation.
'use strict';
if (Process.arch !== 'ia32' || Process.mainModule.name.toLowerCase() !== 'g7mtclient.exe')
  throw new Error('Only pinned original x86 LOGH is supported');
const state = {source: 'product', ready: false, finished: false, present_count: 0,
               present_failures: 0, device_failures: 0, errors: []};
const hooks = new Set(), devices = new Map();
let busy = false;
let firstFrame = null, lastFrame = null;
function validFrame(frame) {
  const m = frame.meta;
  if (m.width < 400 || m.height < 300) return false;
  const bytes = new Uint8Array(frame.bytes), sums = [0,0,0], squares = [0,0,0];
  for (let y = 0; y < 90; y++) {
    const py = (y + 0.5) * m.height / 90 - 0.5, top = Math.floor(py), fy = py - top;
    for (let x = 0; x < 160; x++) {
      const px = (x + 0.5) * m.width / 160 - 0.5, left = Math.floor(px), fx = px - left;
      const a = top * m.pitch + left * 4, b = a + m.pitch;
      for (let c = 0; c < 3; c++) {
        const v = (bytes[a+c]*(1-fx) + bytes[a+4+c]*fx)*(1-fy) +
                  (bytes[b+c]*(1-fx) + bytes[b+4+c]*fx)*fy;
        sums[c] += v; squares[c] += v*v;
      }
    }
  }
  return squares.some((s,c) => Math.sqrt(Math.max(0,s/14400-(sums[c]/14400)**2)) > 3);
}
function error(e) {
  if (state.errors.length < 32) state.errors.push(String(e));
}
function method(object, index, result, args) {
  return new NativeFunction(object.readPointer().add(index * 4).readPointer(), result, args, 'stdcall');
}
function attach(address, callbacks) {
  const key = address.toString();
  if (!hooks.has(key)) { Interceptor.attach(address, callbacks); hooks.add(key); }
}
function release(object) {
  if (!object.isNull()) method(object, 2, 'uint', ['pointer'])(object);
}
function readback(device, kind, frameNo) {
  const sourceOut = Memory.alloc(4), targetOut = Memory.alloc(4);
  sourceOut.writePointer(ptr(0)); targetOut.writePointer(ptr(0));
  let source = ptr(0), target = ptr(0), locked = false;
  function ok(hr, name) { if (hr < 0) throw new Error(name + ' HRESULT ' + hr); }
  try {
    ok(method(device, 18, 'int', ['pointer','uint','uint','uint','pointer'])(device,0,0,0,sourceOut), 'GetBackBuffer');
    source = sourceOut.readPointer();
    const desc = Memory.alloc(32);
    ok(method(source,12,'int',['pointer','pointer'])(source,desc), 'GetDesc');
    const format = desc.readU32(), width = desc.add(24).readU32(), height = desc.add(28).readU32();
    if (![21,22].includes(format) || width < 1 || height < 1 || width > 4096 || height > 4096) return null;
    // D3DPOOL_SYSTEMMEM=2; source is read, never changed. Multisample resolve
    // is intentionally unsupported: failed readback is an explicit error.
    ok(method(device,36,'int',['pointer','uint','uint','uint','uint','pointer','pointer'])(device,width,height,format,2,targetOut,ptr(0)), 'CreateOffscreenPlainSurface');
    target = targetOut.readPointer();
    ok(method(device,32,'int',['pointer','pointer','pointer'])(device,source,target), 'GetRenderTargetData');
    const rect = Memory.alloc(8);
    ok(method(target,13,'int',['pointer','pointer','pointer','uint'])(target,rect,ptr(0),0x10), 'LockRect');
    locked = true;
    const pitch = rect.readS32();
    if (pitch < width * 4 || pitch > 32768) throw new Error('Invalid pitch');
    return {meta: {type:'render-frame', format, width, height, pitch,
                   interface:kind, frame_no:frameNo}, bytes:rect.add(4).readPointer().readByteArray(pitch * height)};
  } finally {
    if (locked) method(target,14,'int',['pointer'])(target);
    release(target); release(source);
  }
}
function hookPresent(device, kind, index) {
  devices.set(device.toString(), kind);
  attach(device.readPointer().add(index * 4).readPointer(), {
    onEnter(args) {
      this.tracked = !state.finished && devices.has(args[0].toString());
      if (!this.tracked) return;
      this.number = ++state.present_count;
      // Some Ex implementations forward through Present. Count every return,
      // but do not recursively copy the same backbuffer.
      if (busy) return;
      busy = true;
      this.ownsBusy = true;
      try { this.frame = readback(args[0], devices.get(args[0].toString()), this.number); }
      catch (e) { error(e); }
    },
    onLeave(ret) {
      if (!this.tracked) return;
      try {
        const hr = ret.toInt32();
        if (hr < 0) state.present_failures++;
        if (this.ownsBusy) lastFrame = this.frame || null;
        if (this.frame) {
          this.frame.meta.present_hr = hr;
          if (hr >= 0 && firstFrame === null && validFrame(this.frame)) firstFrame = this.frame;
        }
      } catch (e) { error(e); }
      finally { if (this.ownsBusy) busy = false; }
    }
  });
}
function hookFactory(object, kind) {
  const index = kind === 'D3D9Ex' ? 20 : 16;
  attach(object.readPointer().add(index * 4).readPointer(), {
    onEnter(args) { this.out = args[kind === 'D3D9Ex' ? 7 : 6]; },
    onLeave(ret) {
      if (state.finished) return;
      try {
        if (ret.toInt32() < 0) { state.device_failures++; return; }
        const device = this.out.readPointer();
        hookPresent(device, kind, 17);
        if (kind === 'D3D9Ex') hookPresent(device, kind, 121);
      } catch (e) { error(e); }
    }
  });
  // Ex factories also expose the base CreateDevice entry point.
  if (kind === 'D3D9Ex') hookFactory(object, 'D3D9');
}
Process.attachModuleObserver({onAdded(module) {
  if (module.name.toLowerCase() !== 'd3d9.dll') return;
  for (const name of ['Direct3DCreate9','Direct3DCreate9Ex']) {
    const address = module.findExportByName(name);
    if (!address) continue;
    const ex = name.endsWith('Ex');
    try {
      attach(address, {
        onEnter(args) { if (ex) this.out = args[1]; },
        onLeave(ret) {
          if (state.finished) return;
          try {
            if (ex ? ret.toInt32() < 0 : ret.isNull()) { state.device_failures++; return; }
            hookFactory(ex ? this.out.readPointer() : ret, ex ? 'D3D9Ex' : 'D3D9');
          } catch (e) { error(e); }
        }
      });
    } catch (e) { error(e); }
  }
}});
state.ready = true;
rpc.exports = {
  renderstatus() { return state; },
  async finishrender() {
    // Called before target stop; Frida's ordered message queue is drained by
    // the controller's render-finished event before raw-file hashing/cleanup.
    state.finished = true;
    for (let i = 0; busy && i < 100; i++) await new Promise(resolve => setTimeout(resolve, 10));
    if (busy) throw new Error('Present capture still in flight');
    // At most two raw messages per run; never queue a large buffer per Present.
    if (firstFrame !== null) send(Object.assign({slot:lastFrame === firstFrame ? 'both' : 'first'},firstFrame.meta), firstFrame.bytes);
    if (lastFrame !== null && lastFrame !== firstFrame) send(Object.assign({slot:'last'},lastFrame.meta), lastFrame.bytes);
    firstFrame = lastFrame = null;
    send({type:'render-finished', observation:state});
  }
};
