// Passive export observers in the owned target. Never invoke or alter an API.
'use strict';
if (Process.arch !== 'ia32' || Process.mainModule.name.toLowerCase() !== 'g7mtclient.exe')
  throw new Error('Only pinned original x86 LOGH is supported');
const state = {source:'product', ready:false, hooks:[], calls:[], errors:[]};
const specs = [
  ['ws2_32.dll', 'connect', 'network'], ['ws2_32.dll', 'WSAConnect', 'network'],
  ['kernel32.dll', 'CreateProcessA', 'bool'], ['kernel32.dll', 'CreateProcessW', 'bool'],
  ['advapi32.dll', 'CreateProcessAsUserA', 'bool'], ['advapi32.dll', 'CreateProcessAsUserW', 'bool'],
  ['advapi32.dll', 'CreateProcessWithLogonW', 'bool'], ['advapi32.dll', 'CreateProcessWithTokenW', 'bool'],
  ['shell32.dll', 'ShellExecuteA', 'shell'], ['shell32.dll', 'ShellExecuteW', 'shell'],
  ['shell32.dll', 'ShellExecuteExA', 'bool'], ['shell32.dll', 'ShellExecuteExW', 'bool'],
  ['user32.dll', 'SwitchDesktop', 'bool'], ['user32.dll', 'SetForegroundWindow', 'bool'],
  ['user32.dll', 'BlockInput', 'bool'], ['user32.dll', 'SendInput', 'uint'],
  ['user32.dll', 'keybd_event', 'void'], ['user32.dll', 'mouse_event', 'void'],
  ['user32.dll', 'SetCursorPos', 'bool']
];
function error(e) {
  if (state.errors.length < 32) state.errors.push(String(e));
}
function endpoint(args) {
  const p = args[1], length = args[2].toInt32();
  if (p.isNull() || length < 2) throw new Error('Invalid sockaddr');
  const family = p.readU16();
  let address = null, port = null;
  if (family === 2 && length >= 16) {
    port = p.add(2).readU8() * 256 + p.add(3).readU8();
    address = [4,5,6,7].map(i => p.add(i).readU8()).join('.');
  } else if (family === 23 && length >= 28) {
    port = p.add(2).readU8() * 256 + p.add(3).readU8();
    address = Array.from({length:8}, (_, i) =>
      (p.add(8+i*2).readU8()*256+p.add(9+i*2).readU8()).toString(16)).join(':');
  }
  return {family:family, address:address, port:port};
}
for (const [module, api, kind] of specs) {
  const record = {module:module, api:api, address:null, installed:false};
  state.hooks.push(record);
  try {
    // Missing modules/exports fail closed; never load extra target modules.
    const address = Process.getModuleByName(module).getExportByName(api);
    record.address = address.toString();
    Interceptor.attach(address, {
      onEnter(args) {
        this.call = null;
        try {
          if (state.calls.length >= 8192) throw new Error('API observation overflow');
          const m = Process.findModuleByAddress(this.returnAddress);
          const call = {api:api, caller_module:m ? m.name : null,
            caller_rva:m ? this.returnAddress.sub(m.base).toString() : null,
            caller_size:m ? m.size : null, completed:false, return_value:null};
          state.calls.push(call);
          this.call = call;
          if (kind === 'network') Object.assign(call, endpoint(args));
        } catch (e) { error(e); }
      },
      onLeave(retval) {
        try {
          if (this.call !== null) {
            // void has no defined return: never interpret arbitrary EAX as denial.
            this.call.return_value = kind === 'void' ? null :
              (kind === 'uint' || kind === 'shell' ? retval.toUInt32() : retval.toInt32());
            this.call.completed = true;
          }
        } catch (e) { error(e); }
      }
    });
    record.installed = true;
  } catch (e) { error(api + ': ' + e); }
}
Interceptor.flush();
state.ready = state.hooks.every(h => h.installed) && state.errors.length === 0;
rpc.exports = {apistatus() { return JSON.parse(JSON.stringify(state)); }};
