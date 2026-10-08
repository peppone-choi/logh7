// Virtual focus queries and D3D8 backbuffer readback in the owned client only.
// No desktop switching, global input, or host foreground activation.
if(Process.arch!=='ia32' || Process.mainModule.name.toLowerCase()!=='g7mtclient.exe')
  throw new Error('Only the owned original 32-bit LOGH7 client is supported');
Process.setExceptionHandler(function(details) {
  if(details.type==='access-violation')send({type:'client-exception', exception:details.type,
        address:details.address.toString(), memory:details.memory, registers:details.context,
        backtrace:Thread.backtrace(details.context,Backtracer.FUZZY).map(p=>p.toString())});
  return false;
});
let done = false;
rpc.exports.recapture = function() { done = false; };
setInterval(()=>rpc.exports.recapture(),2000);
let gameWindow = ptr(0);
let frameNotified = false;
const failures = new Set();
function reportFailure(name, hr) {
  if(!failures.has(name)){failures.add(name);send({type:'capture-status',event:name+': '+hr.toString(16)});}
}
function method(object, index, result, args) {
  return new NativeFunction(object.readPointer().add(index * Process.pointerSize).readPointer(), result, args, 'stdcall');
}
const resetHooks = new Map();
function fixWindowedReset(device8) {
  // Pinned dxwrapper v1.8.8600.25: d3d8to9 device -> wrapper -> native device.
  // Validate both vtables before using the wrapper's private object layout.
  const wrapper = device8.add(12).readPointer();
  if(Process.findModuleByAddress(wrapper.readPointer().readPointer())?.name.toLowerCase()!=='dxwrapper.dll')return;
  const native = wrapper.add(4).readPointer();
  if(Process.findModuleByAddress(native.readPointer().readPointer())?.name.toLowerCase()!=='d3d9.dll')
    throw new Error('Unexpected native D3D9 device');
  const iid=Memory.alloc(16),output=Memory.alloc(4);
  iid.writeByteArray([0xce,0x10,0x8b,0xb1,0x49,0x26,0x5a,0x40,0x87,0x0f,0x95,0xf7,0x77,0xd4,0x31,0x3a]);
  output.writePointer(ptr(0));
  if(method(native,0,'int',['pointer','pointer','pointer'])(native,iid,output)!==0)
    throw new Error('Expected a D3D9Ex device');
  const ex=output.readPointer(),address=ex.readPointer().add(132*4).readPointer();
  let devices=resetHooks.get(address.toString());
  if(!devices) {
    devices=new Set();resetHooks.set(address.toString(),devices);
    Interceptor.attach(address,{onEnter(args){
      if(devices.has(args[0].toString()) && !args[1].isNull() && args[1].add(32).readU32()!==0) {
        // ResetEx requires NULL fullscreen display mode for a windowed device.
        args[2]=ptr(0);
      }
    }});
  }
  devices.add(ex.toString());
  method(ex,2,'uint',['pointer'])(ex); // Balance QueryInterface; the owned game holds the device.
  send({type:'capture-status',event:'Owned windowed ResetEx fix installed'});
}
function capture8(device) {
  if(done)return;
  const sourceOut=Memory.alloc(4),targetOut=Memory.alloc(4);
  sourceOut.writePointer(ptr(0));targetOut.writePointer(ptr(0));
  let source=ptr(0),target=ptr(0),locked=false;
  try {
    let hr=method(device,16,'int',['pointer','uint','uint','pointer'])(device,0,0,sourceOut);
    if(hr<0){reportFailure('GetBackBuffer',hr);return;}
    source=sourceOut.readPointer();const desc=Memory.alloc(32);
    hr=method(source,8,'int',['pointer','pointer'])(source,desc);
    if(hr<0){reportFailure('GetDesc',hr);return;}
    const format=desc.readU32(),width=desc.add(24).readU32(),height=desc.add(28).readU32();
    if((format!==21 && format!==22)||width<400||height<300||width>4096||height>4096){reportFailure('Backbuffer format/size',format);return;}
    hr=method(device,27,'int',['pointer','uint','uint','uint','pointer'])(device,width,height,format,targetOut);
    if(hr<0){reportFailure('CreateImageSurface',hr);return;}
    target=targetOut.readPointer();
    hr=method(device,28,'int',['pointer','pointer','pointer','uint','pointer','pointer'])(device,source,ptr(0),0,target,ptr(0));
    if(hr<0){reportFailure('CopyRects',hr);return;}
    const rect=Memory.alloc(8);
    hr=method(target,9,'int',['pointer','pointer','pointer','uint'])(target,rect,ptr(0),0x10);
    if(hr<0){reportFailure('LockRect',hr);return;}
    locked=true;const pitch=rect.readS32(),pixels=rect.add(4).readPointer();
    if(pitch<width*4 || pitch>32768)throw new Error('Invalid backbuffer pitch');
    const bytes=pixels.readByteArray(pitch*height),values=new Uint8Array(bytes),colours=new Set();
    for(let y=0;y<height;y+=Math.max(1,Math.floor(height/30)))for(let x=0;x<width;x+=Math.max(1,Math.floor(width/40))){
      const i=y*pitch+x*4;colours.add((values[i]<<16)|(values[i+1]<<8)|values[i+2]);
    }
    if(colours.size<32){reportFailure('Flat render frame',colours.size);return;}
    send({type:'render-frame',width,height,pitch,format},bytes);done=true;
  } finally {
    if(locked)method(target,10,'int',['pointer'])(target);
    for(const object of [target,source])if(!object.isNull())method(object,2,'uint',['pointer'])(object);
  }
}
function hook8(object) {
  const create=object.readPointer().add(15*4).readPointer();
  Interceptor.attach(create,{onEnter(args){this.out=args[6];gameWindow=args[3];},onLeave(ret){
    if(ret.toInt32()!==0)return;
    const device=this.out.readPointer();
    try{fixWindowedReset(device);}catch(e){send({type:'capture-error',message:String(e)});}
    const present=device.readPointer().add(15*4).readPointer();
    send({type:'capture-status',event:'D3D8 device hooked'});
    Interceptor.attach(present,{onEnter(args){
      if(!frameNotified){send({type:'capture-status',event:'D3D8 Present reached'});frameNotified=true;}
      try{capture8(args[0]);}catch(e){send({type:'capture-error',message:String(e)});done=true;}
    }});
  }});
}
// The original G7MTClient's Direct3DCreate8 import slot; executable is copied unchanged.
const importSlot=Process.mainModule.base.add(0x26b70c);
Interceptor.attach(importSlot.readPointer(),{onLeave(ret){if(!ret.isNull())hook8(ret);}});
Process.attachModuleObserver({onAdded(module){
  if(module.name.toLowerCase()==='user32.dll') {
    const ancestor=new NativeFunction(module.getExportByName('GetAncestor'),'pointer',['pointer','uint'],'stdcall');
    for(const name of ['GetForegroundWindow','GetActiveWindow','GetFocus']) {
      Interceptor.attach(module.getExportByName(name),{onLeave(ret){
        if(!gameWindow.isNull())ret.replace(name==='GetFocus'?gameWindow:ancestor(gameWindow,2));
      }});
    }
  }
}});
