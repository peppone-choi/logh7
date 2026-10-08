// Loaded with capture-frame.js only for the owned original client's UI test.
let testKey = 0;
rpc.exports.setkey = function(value) {
  if(![0,9,13].includes(value))throw new Error('Only release, Tab and Enter are supported');
  testKey = value;
};
Process.attachModuleObserver({onAdded(module) {
  if(module.name.toLowerCase() !== 'user32.dll')return;
  for(const name of ['GetAsyncKeyState','GetKeyState']) {
    Interceptor.attach(module.getExportByName(name),{
      onEnter(args){this.key=args[0].toUInt32();},
      onLeave(ret){ret.replace(testKey!==0 && this.key===testKey ? ptr(0x8000) : ptr(0));}
    });
  }
}});
// Observe the actual server-error UI callback; do not create an error dialog ourselves.
Interceptor.attach(Process.mainModule.base.add(0x11c930),{
  onEnter(args){send({type:'login-error-ui',code:args[0].toUInt32()});}
});
// Keep only the most recent rendered image so the final dialog is captured.
setInterval(()=>rpc.exports.recapture(),2000);
