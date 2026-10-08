// Loaded with capture-frame.js only for the owned original client's UI test.
let testKey = 0;
let testMouse = null;
rpc.exports.setkey = function(value) {
  if(![0,9,13].includes(value))throw new Error('Only release, Tab and Enter are supported');
  testKey = value;
};
Process.attachModuleObserver({onAdded(module) {
  if(module.name.toLowerCase() !== 'user32.dll')return;
  const clientToScreen=new NativeFunction(module.getExportByName('ClientToScreen'),'int',['pointer','pointer'],'stdcall');
  const clientRect=new NativeFunction(module.getExportByName('GetClientRect'),'int',['pointer','pointer'],'stdcall');
  const post=new NativeFunction(module.getExportByName('PostMessageW'),'int',['pointer','uint','uint','int'],'stdcall');
  rpc.exports.setmouse=function(x,y,down) {
    if(gameWindow.isNull())throw new Error('Owned game window is not ready');
    const rect=Memory.alloc(16);
    if(!clientRect(gameWindow,rect) || !Number.isInteger(x) || !Number.isInteger(y) || x<0 || y<0 || x>=rect.add(8).readS32() || y>=rect.add(12).readS32())
      throw new Error('Virtual mouse must remain within the owned game window');
    testMouse={x,y};testKey=down?1:0;
    const position=(y<<16)|x;
    if(!post(gameWindow,0x200,down?1:0,position) || !post(gameWindow,down?0x201:0x202,down?1:0,position))
      throw new Error('Cannot queue owned mouse message');
  };
  Interceptor.attach(module.getExportByName('GetCursorPos'),{
    onEnter(args){this.point=args[0];},onLeave(ret){
      if(testMouse && !this.point.isNull()) {
        this.point.writeS32(testMouse.x);this.point.add(4).writeS32(testMouse.y);
        ret.replace(clientToScreen(gameWindow,this.point));
      }
    }
  });
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

// This client polls DirectInput for mouse buttons in addition to cursor position.
// Supply virtual state only to its system mouse device; no host input is injected.
const mouseDevices=new Set(),mouseHooks=new Set();
function hookMouseDevice(device) {
  mouseDevices.add(device.toString());
  for(const slot of [7,25]) {
    const address=device.readPointer().add(slot*4).readPointer(),key='ready:'+address;
    if(mouseHooks.has(key))continue;mouseHooks.add(key);
    Interceptor.attach(address,{onEnter(args){this.owned=mouseDevices.has(args[0].toString());},onLeave(ret){if(this.owned)ret.replace(0);}});
  }
  const address=device.readPointer().add(9*4).readPointer(),key='state:'+address;
  if(mouseHooks.has(key))return;mouseHooks.add(key);
  Interceptor.attach(address,{onEnter(args){this.owned=mouseDevices.has(args[0].toString());this.size=args[1].toUInt32();this.output=args[2];},onLeave(ret){
    if(!this.owned || this.output.isNull() || ![16,20].includes(this.size))return;
    this.output.writeByteArray(new Uint8Array(this.size));
    this.output.add(12).writeU8(testKey===1?0x80:0);ret.replace(0);
  }});
  send({type:'capture-status',event:'Owned DirectInput mouse state hooked'});
}
Interceptor.attach(Process.mainModule.base.add(0x26b044).readPointer(),{onEnter(args){this.output=args[3];},onLeave(ret){
  if(ret.toInt32()!==0)return;
  const factory=this.output.readPointer(),address=factory.readPointer().add(3*4).readPointer(),key='factory:'+address;
  if(mouseHooks.has(key))return;mouseHooks.add(key);
  Interceptor.attach(address,{onEnter(args){this.mouse=args[1].readU32()===0x6f1d2b60;this.output=args[2];},onLeave(ret){
    if(this.mouse && ret.toInt32()===0)hookMouseDevice(this.output.readPointer());
  }});
}});
