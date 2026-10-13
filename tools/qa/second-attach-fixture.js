// Preparation payload only. The future driver must prove owned PID/creation,
// inactive desktop, outer5/inner1 and owned TEMP before attaching this script.
// Never load capture-frame.js into a harmless fixture: it uses game offsets.
if (Process.arch !== 'ia32' ||
    Process.mainModule.name.toLowerCase() !== 'harmless-second-attach.exe') {
  throw new Error('Only the explicitly owned harmless x86 fixture is supported');
}
let calls = 0;
rpc.exports = {
  marker() { return {arch: Process.arch, pid: Process.id, calls: ++calls}; },
  injectedexception() { throw new Error('synthetic second attach exception'); }
};
send({type: 'second-attach-fixture-ready', arch: Process.arch, pid: Process.id});
