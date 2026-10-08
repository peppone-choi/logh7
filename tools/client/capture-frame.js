// Virtual focus queries and D3D8 backbuffer readback in the owned client only.
// No desktop switching, global input, or host foreground activation.
if(Process.arch!=='ia32' || Process.mainModule.name.toLowerCase()!=='g7mtclient.exe')
  throw new Error('Only the owned original 32-bit LOGH7 client is supported');
let done = false;
rpc.exports.recapture = function() { done = false; };
let gameWindow = ptr(0);
let frameNotified = false;
const failures = new Set();
function reportFailure(name, hr) {
  if(!failures.has(name)){failures.add(name);send({type:'capture-status',event:name+': '+hr.toString(16)});}
}
function method(object, index, result, args) {
  return new NativeFunction(object.readPointer().add(index * Process.pointerSize).readPointer(), result, args, 'stdcall');
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
    const end=device.readPointer().add(35*4).readPointer();
    send({type:'capture-status',event:'D3D8 device hooked'});
    Interceptor.attach(end,{onEnter(args){this.device=args[0];},onLeave(){
      if(!frameNotified){send({type:'capture-status',event:'D3D8 EndScene reached'});frameNotified=true;}
      try{capture8(this.device);}catch(e){send({type:'capture-error',message:String(e)});done=true;}
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
