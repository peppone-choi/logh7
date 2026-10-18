// Synthetic JS API callbacks only. No Windows DLL, game, or fixture execution.
'use strict';
const fs = require('fs'), vm = require('vm'), assert = require('assert');
class P {
  constructor(n, bytes=null, offset=0) { this.n=n; this.bytes=bytes; this.offset=offset; }
  toString() { return '0x'+(this.n>>>0).toString(16); }
  toInt32() { return this.n|0; }
  toUInt32() { return this.n>>>0; }
  isNull() { return this.n===0; }
  sub(p) { return new P(this.n-p.n); }
  add(n) { return new P(this.n+n, this.bytes, this.offset+n); }
  readU8() { if (!this.bytes) throw Error('unreadable'); return this.bytes[this.offset]; }
  readU16() { return this.readU8()+256*this.add(1).readU8(); }
}
const source=fs.readFileSync(__dirname+'/qa_api_x86.js','utf8');
function setup(missing=null) {
  const callbacks = new Map(); let count=0;
  const context = {Process:{arch:'ia32',mainModule:{name:'G7MTClient.exe'},
    getModuleByName(module) { return {getExportByName(api) {
      if (api===missing) throw Error('missing export');
      const p=new P(0x1000+16*count++); p.api=api; return p;
    }}; },
    findModuleByAddress() { return {name:'G7MTClient.exe',base:new P(0x400000),size:0x10000}; }},
    Interceptor:{attach(p, cb) { callbacks.set(p.api,cb); },flush() {}}, rpc:{}};
  vm.createContext(context); vm.runInContext(source,context);
  const status=()=>JSON.parse(JSON.stringify(context.rpc.exports.apistatus()));
  function enter(api,args=[]) {
    const self={returnAddress:new P(0x400123)};
    callbacks.get(api).onEnter.call(self,args); return self;
  }
  function leave(api,self,value) {
    const ret=new P(value), before=ret.n;
    callbacks.get(api).onLeave.call(self,ret);
    assert.equal(ret.n,before,'return was mutated');
  }
  function invoke(api,value,args=[]) { const self=enter(api,args); leave(api,self,value); }
  return {status,invoke,enter,leave,callbacks};
}
const s=setup();
assert.equal(s.status().ready,true);
assert.equal(s.status().hooks.length,19);
assert(s.status().hooks.every(h=>h.installed && h.address!=='0x0'));
const bytes=Buffer.from([2,0,0x4e,0x20,127,0,0,1,0,0,0,0,0,0,0,0]);
const args=[new P(42),new P(0x5000,bytes),new P(16)];
const original=Buffer.from(bytes);
s.invoke('connect',-1,args); s.invoke('WSAConnect',0,args);
assert.deepEqual(bytes,original,'sockaddr was mutated');
for(const api of s.callbacks.keys()) if(!['connect','WSAConnect'].includes(api)) s.invoke(api,1);
let calls=s.status().calls;
assert.equal(calls[0].address,'127.0.0.1'); assert.equal(calls[0].port,20000);
assert.equal(calls[0].return_value,-1); assert.equal(calls[1].return_value,0);
assert(calls.every(c=>c.caller_module==='G7MTClient.exe' && c.caller_rva==='0x123' && c.completed));
assert.equal(calls.find(c=>c.api==='keybd_event').return_value,null);
assert.equal(calls.find(c=>c.api==='mouse_event').return_value,null);
const pending=s.enter('SendInput');
assert.equal(s.status().calls.at(-1).completed,false);
s.leave('SendInput',pending,0);
const prior=s.status(); prior.calls[0].return_value=999;
assert.equal(s.status().calls[0].return_value,-1,'RPC was not a detached snapshot');
const bad=setup('WSAConnect');
assert.equal(bad.status().ready,false); assert(bad.status().errors.length);
s.invoke('connect',-1,[new P(42),new P(0),new P(1)]);
assert(s.status().errors.some(e=>e.includes('sockaddr')));
const ipv6=Buffer.alloc(28); ipv6[0]=23; ipv6[2]=0x4e; ipv6[3]=0x20; ipv6[23]=1;
s.invoke('connect',-1,[new P(42),new P(0x6000,ipv6),new P(28)]);
assert.equal(s.status().calls.at(-1).family,23);
assert.equal(s.status().calls.at(-1).address,'0:0:0:0:0:0:0:1');
const overflow=setup();
for(let i=0;i<8193;i++) overflow.invoke('SendInput',0);
assert.equal(overflow.status().calls.length,8192);
assert(overflow.status().errors.some(e=>e.includes('overflow')));
console.log('OK: synthetic 19 hooks, endpoint/caller/returns, pending, missing, void, overflow; inputs/returns unchanged');
