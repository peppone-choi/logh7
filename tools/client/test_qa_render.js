// Linux-only synthetic Frida/vtable harness. Does not load a native DLL/game.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const hooks = new Map(), memory = new Map(), native = new Map(), messages = [];
let next = 10000, observer;
class Pointer {
  constructor(value) { this.value = value; }
  add(n) { return new Pointer(this.value + n); }
  readPointer() { assert(memory.has(this.value), 'missing pointer ' + this.value); return memory.get(this.value); }
  writePointer(v) { memory.set(this.value, v); }
  readU32() { return memory.get(this.value) >>> 0; }
  readS32() { return memory.get(this.value) | 0; }
  toString() { return String(this.value); }
  toInt32() { return this.value | 0; }
  isNull() { return this.value === 0; }
  readByteArray(n) { assert.equal(n, pixels.byteLength); return pixels.slice(0); }
}
const ptr = n => new Pointer(n);
const alloc = n => { const p = ptr(next); next += n + 16; return p; };
function object(methods) {
  const out = alloc(4), table = alloc(600);
  out.writePointer(table);
  for (const [i, fn] of Object.entries(methods)) {
    const address = alloc(4);
    table.add(Number(i) * 4).writePointer(address);
    native.set(address.toString(), fn);
  }
  return out;
}
function invoke(address, args, hr) {
  const callback = hooks.get(address.toString());
  assert(callback, 'missing hook ' + address);
  const context = {};
  if (callback.onEnter) callback.onEnter.call(context, args);
  if (callback.onLeave) callback.onLeave.call(context, ptr(hr));
}
const pixels = new ArrayBuffer(400 * 300 * 4), values = new Uint8Array(pixels);
for (let y = 0; y < 300; y++) for (let x = 0; x < 400; x++)
  for (let c = 0; c < 3; c++) values[(y * 400 + x) * 4 + c] = Math.floor(x * 255 / 400);
let unlocks = 0, releases = 0;
const source = object({2: () => ++releases, 12: (_, desc) => {
  memory.set(desc.value, 22); memory.set(desc.value + 24, 400); memory.set(desc.value + 28, 300); return 0;
}});
const target = object({2: () => ++releases, 13: (_, rect) => {
  memory.set(rect.value, 1600); rect.add(4).writePointer(ptr(999)); return 0;
}, 14: () => { unlocks++; return 0; }});
const device = object({17: () => 0, 121: () => 0,
  18: (_,a,b,c,out) => { out.writePointer(source); return 0; },
  36: (_,w,h,fmt,pool,out) => { assert.equal(pool, 2); out.writePointer(target); return 0; },
  32: (_,src,dst) => { assert.equal(src.value, source.value); assert.equal(dst.value, target.value); return 0; }
});
const factory = object({16: () => 0, 20: () => 0});
const create9 = alloc(4), createEx = alloc(4);
const context = vm.createContext({Process: {arch:'ia32',mainModule:{name:'G7MTClient.exe'},
  attachModuleObserver(o) { observer = o; }}, ptr,
  Memory: {alloc}, Interceptor: {attach(address, callbacks) { hooks.set(address.toString(), callbacks); }},
  NativeFunction: function(address) { const fn = native.get(address.toString()); assert(fn); return fn; },
  send(meta, bytes) { messages.push({meta:JSON.parse(JSON.stringify(meta)), bytes}); }, rpc:{exports:{}},
  setTimeout, Uint8Array, Map, Set, Error, Math, Promise});
vm.runInContext(fs.readFileSync(__dirname + '/qa_render_x86.js', 'utf8'), context);
observer.onAdded({name:'d3d9.dll', findExportByName(name) { return name.endsWith('Ex') ? createEx : create9; }});
const out = alloc(4); out.writePointer(factory);
invoke(createEx, [ptr(0), out], 0);
const deviceOut = alloc(4); deviceOut.writePointer(device);
invoke(factory.readPointer().add(20 * 4).readPointer(), [null,null,null,null,null,null,null,deviceOut], 0);
const present = device.readPointer().add(121 * 4).readPointer();
invoke(present, [device], 0);
assert.equal(messages.length, 0, 'raw frames must not queue per Present');
values.fill(0); // Last frame flat; first valid must still survive.
invoke(present, [device], 0);
assert.equal(unlocks, 2); assert.equal(releases, 4);
(async () => {
  await context.rpc.exports.finishrender();
  assert.equal(messages.length, 3);
  assert.equal(messages[0].meta.frame_no, 1);
  assert.equal(messages[1].meta.frame_no, 2);
  assert.equal(messages[0].meta.interface, 'D3D9Ex');
  assert.equal(messages[2].meta.observation.present_count, 2);
  assert.equal(messages[2].meta.observation.errors.length, 0);
  // Base D3D9 path, failed Present and failed device creation are independently observable.
  vm.runInContext('state.finished=false; state.present_count=0;', context);
  invoke(create9, [], factory.value);
  invoke(factory.readPointer().add(16 * 4).readPointer(), [null,null,null,null,null,null,deviceOut], 0);
  invoke(device.readPointer().add(17 * 4).readPointer(), [device], -2005530520);
  invoke(factory.readPointer().add(16 * 4).readPointer(), [null,null,null,null,null,null,deviceOut], -1);
  await context.rpc.exports.finishrender();
  assert.equal(messages.at(-2).meta.interface, 'D3D9');
  assert.equal(messages.at(-1).meta.observation.present_failures, 1);
  assert.equal(messages.at(-1).meta.observation.device_failures, 1);
  // Readback failure must be explicit and release any retained source.
  vm.runInContext('state.finished=false;', context);
  native.set(device.readPointer().add(32 * 4).readPointer().toString(), () => -1);
  invoke(device.readPointer().add(17 * 4).readPointer(), [device], 0);
  await context.rpc.exports.finishrender();
  assert.match(messages.at(-1).meta.observation.errors[0], /GetRenderTargetData/);
  console.log('synthetic D3D9/Ex readback, first-last/drain, HRESULT and exception: OK');
})().catch(e => { console.error(e); process.exitCode = 1; });
