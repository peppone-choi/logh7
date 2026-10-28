// Loaded with capture-frame.js only for the owned original client's UI test.
let testKey = 0;
let testMouse = null;
let testMouseDelta = {x:0,y:0};
rpc.exports.setkey = function(value) {
  if(![0,8,9,13,27,67,88].includes(value))throw new Error('Only release, Backspace, Tab, Enter, Escape and tactical C/X are supported');
  testKey = value;
};
Process.attachModuleObserver({onAdded(module) {
  if(module.name.toLowerCase() !== 'user32.dll')return;
  const clientToScreen=new NativeFunction(module.getExportByName('ClientToScreen'),'int',['pointer','pointer'],'stdcall');
  const clientRect=new NativeFunction(module.getExportByName('GetClientRect'),'int',['pointer','pointer'],'stdcall');
  const post=new NativeFunction(module.getExportByName('PostMessageW'),'int',['pointer','uint','uint','int'],'stdcall');
  function mousePosition(x,y) {
    if(gameWindow.isNull())throw new Error('Owned game window is not ready');
    const rect=Memory.alloc(16);
    if(!clientRect(gameWindow,rect) || !Number.isInteger(x) || !Number.isInteger(y) || x<0 || y<0 || x>=rect.add(8).readS32() || y>=rect.add(12).readS32())
      throw new Error('Virtual mouse must remain within the owned game window');
    testMouse={x,y};
    return (y<<16)|x;
  }
  rpc.exports.movemouse=function(x,y) {
    if(testKey===2 && testMouse) {
      testMouseDelta.x+=x-testMouse.x;testMouseDelta.y+=y-testMouse.y;
    }
    const position=mousePosition(x,y);
    if(!post(gameWindow,0x200,[1,2].includes(testKey)?testKey:0,position))throw new Error('Cannot queue owned mouse movement');
  };
  rpc.exports.setmouse=function(x,y,down,button=1) {
    if(button==null)button=1; // Frida supplies a null binary argument to legacy three-argument calls.
    if(![1,2].includes(button))throw new Error('Only owned left/right mouse buttons are supported');
    const position=mousePosition(x,y);testKey=down?button:0;testMouseDelta={x:0,y:0};
    const message=button===1?(down?0x201:0x202):(down?0x204:0x205);
    if(!post(gameWindow,0x200,down?button:0,position) || !post(gameWindow,message,down?button:0,position))
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
  // The galaxy view detects mouse edges through GetKeyboardState, whereas
  // the GUI also handles window messages. Supply the same owned virtual keys
  // to both paths; never read or inject the host desktop's input.
  Interceptor.attach(module.getExportByName('GetKeyboardState'),{
    onEnter(args){this.keys=args[0];},
    onLeave(ret){
      if(this.keys.isNull())return;
      this.keys.writeByteArray(new Uint8Array(256));
      if(testKey!==0)this.keys.add(testKey).writeU8(0x80);
      ret.replace(1);
    }
  });
}});
// Observe the actual server-error UI callback; do not create an error dialog ourselves.
Interceptor.attach(Process.mainModule.base.add(0x11c930),{
  onEnter(args){send({type:'login-error-ui',code:args[0].toUInt32()});}
});
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
    this.output.writeS32(testMouseDelta.x);this.output.add(4).writeS32(testMouseDelta.y);
    testMouseDelta={x:0,y:0};
    this.output.add(12).writeU8(testKey===1?0x80:0);
    this.output.add(13).writeU8(testKey===2?0x80:0);ret.replace(0);
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
