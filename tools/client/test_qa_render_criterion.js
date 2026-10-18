// Synthetic raw inputs only; no Frida, DLL or product execution.
'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const context = vm.createContext({Process:{arch:'ia32', mainModule:{name:'G7MTClient.exe'},
  attachModuleObserver() {}}, rpc:{exports:{}}, Uint8Array});
vm.runInContext(fs.readFileSync(__dirname + '/qa_render_x86.js', 'utf8'), context);
const cases = JSON.parse(fs.readFileSync(0, 'utf8'));
const results = cases.map(item => {
  context.frame = {meta:item.meta, bytes:Uint8Array.from(fs.readFileSync(item.path)).buffer};
  const deviation = vm.runInContext('frameStddev(frame)', context);
  const valid = vm.runInContext('validFrame(frame)', context);
  assert(Math.abs(deviation - item.expected) < 0.05, item.name + ': Pillow mismatch ' + deviation);
  assert.equal(valid, item.expected > 3, item.name + ': threshold mismatch');
  return {name:item.name, deviation, valid};
});
console.log(JSON.stringify(results));
