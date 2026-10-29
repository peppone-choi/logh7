// Virtual focus queries and D3D8 backbuffer readback in the owned client only.
// No desktop switching, global input, or host foreground activation.
if(Process.arch!=='ia32' || Process.mainModule.name.toLowerCase()!=='g7mtclient.exe')
  throw new Error('Only the owned original 32-bit LOGH7 client is supported');
Process.setExceptionHandler(function(details) {
  // Caught C++ system exceptions are not evidence of a client crash.
  if(['access-violation','arithmetic','illegal-instruction','abort'].includes(details.type))
    flushGraphicsTrace('client-exception');
  if(['access-violation','arithmetic','illegal-instruction','abort'].includes(details.type))
    send({type:'client-exception', exception:details.type,
        address:details.address.toString(), memory:details.memory, registers:details.context,
        backtrace:Thread.backtrace(details.context,Backtracer.FUZZY).map(p=>p.toString())});
  return false;
});
let done = false;
rpc.exports.recapture = function() { done = false; };
// Opt-in, bounded observation of the owned renderer. No return value, resource,
// shader, declaration or draw argument is replaced by this diagnostic.
let graphicsTrace = false;
let graphicsRows = [];
let graphicsBudget = 0;
const graphicsStartup = [];
for(const [address,prefix] of [[0x5dd390,[0xe8,0x3b,0xff,0xff,0xff,0xe8,0xd6,0x0f]],
    [0x5e83f0,[0x83,0xec,0x18,0x53,0x56,0x57,0xb9,0xff]],
    [0x5e8990,[0xa1,0,0x94,0x22,0x02,0x57,0x6a,0x02]]]) {
  const entry=ptr(address);
  if(prefix.every((v,i)=>entry.add(i).readU8()===v))Interceptor.attach(entry,{
    onEnter(){if(graphicsStartup.length<128)graphicsStartup.push({event:'shader-init.enter',address:entry.toString()});},
    onLeave(ret){if(graphicsStartup.length<128)graphicsStartup.push({event:'shader-init.leave',address:entry.toString(),
      result:ret.toInt32(),shaders:[0,1].map(i=>ptr(0x323aa74+i*4).readU32())});}
  });
}
for(const [address,prefix,isCompiler] of [[0x5a8dc7,[0x55,0x8b,0xec,0x81,0xec,0xe8,0x03,0],true],
    [0x5e8220,[0x51,0xa1,0xc4,0xba,0x23,0x03,0x53,0x33],false]]) {
  const entry=ptr(address);
  if(prefix.every((v,i)=>entry.add(i).readU8()===v))Interceptor.attach(entry,{
    onEnter(args){this.out=args[isCompiler ? 4 : 2];this.source=args[isCompiler ? 0 : 1].toString();},
    onLeave(ret){if(graphicsStartup.length<128)graphicsStartup.push({event:isCompiler ? 'shader-assembler' : 'shader-create-helper',
      source:this.source,hr:ret.toInt32(),output:this.out.isNull() ? null : this.out.readU32()});}
  });
}
function graphicsEvent(event) {
  if(!graphicsTrace || graphicsBudget<=0)return;
  graphicsRows.push(event);
  if(graphicsRows.length>96)graphicsRows.shift();
}
function flushGraphicsTrace(reason) {
  if(graphicsTrace && graphicsBudget>0 && graphicsRows.length) {
    --graphicsBudget;send({type:'render-trace',kind:'graphics',reason,events:graphicsRows});
  }
  graphicsRows=[];
}
rpc.exports.tracegraphics = function() {
  graphicsRows=graphicsStartup.slice();graphicsBudget=32;graphicsTrace=true;
  flushGraphicsTrace('startup');
};
function hookGraphicsTrace(device8) {
  const wrapper=device8.add(12).readPointer(),native=wrapper.add(4).readPointer();
  if(Process.findModuleByAddress(wrapper.readPointer().readPointer())?.name.toLowerCase()!=='dxwrapper.dll' ||
     Process.findModuleByAddress(native.readPointer().readPointer())?.name.toLowerCase()!=='d3d9.dll')return;
  Interceptor.attach(device8.readPointer().add(75*4).readPointer(),{
    onEnter(args){this.match=args[0].equals(device8);if(this.match){this.out=args[3];this.program=args[2];}},
    onLeave(ret){if(this.match && graphicsStartup.length<128) {
      try {graphicsStartup.push({event:'D3D8.CreateVertexShader',hr:ret.toInt32(),
        handle:this.out.isNull() ? null : this.out.readU32(),
        version:this.program.isNull() ? null : this.program.readU32()});}catch(e) {
        graphicsStartup.push({event:'shader-creation-observation-error',message:String(e)});
      }
    }}
  });
  const extraPass=ptr(0x5f34f0),prefix=[0x81,0xec,0x90,0,0,0,0xa1,0,0x94,0x22,0x02,0x8b,0x94,0x24,0xa4,0];
  if(prefix.every((v,i)=>extraPass.add(i).readU8()===v))Interceptor.attach(extraPass,{onEnter(args){
    if(!graphicsTrace || graphicsBudget<=0)return;
    try {
      const mesh=args[0];
      graphicsEvent({event:'extra-pass.mesh',mesh:mesh.toString(),vb:mesh.readPointer().toString(),
        ib:mesh.add(0x28).readPointer().toString(),vertices:mesh.add(0x2c).readU32(),
        indices:mesh.add(0x30).readU32(),stride:mesh.add(0x3c).readU32(),variant:args[6].toUInt32(),
        shaders:[0,1,2,3].map(i=>ptr(0x323aa74+i*4).readU32())});
    } catch(e) {graphicsEvent({event:'mesh-observation-error',message:String(e)});}
  }});
  for(const [object,slot,name] of [[device8,76,'D3D8.SetVertexShader'],[native,87,'D3D9.SetVertexDeclaration'],
      [native,92,'D3D9.SetVertexShader']]) {
    Interceptor.attach(object.readPointer().add(slot*4).readPointer(),{
      onEnter(args){this.match=graphicsTrace && graphicsBudget>0 && args[0].equals(object);if(this.match)this.value=args[1].toString();},
      onLeave(ret){if(this.match){graphicsEvent({event:name,value:this.value,hr:ret.toInt32()});
        if(ret.toInt32()<0)flushGraphicsTrace('failed-setter');}}
    });
  }
  Interceptor.attach(device8.readPointer().add(71*4).readPointer(),{onEnter(args){
    if(!graphicsTrace || graphicsBudget<=0 || !args[0].equals(device8) || !this.returnAddress.equals(ptr(0x5f366a)))return;
    const out=Memory.alloc(4);out.writePointer(ptr(0));
    let declaration=ptr(0);
    try {
      const hr=method(native,88,'int',['pointer','pointer'])(native,out);
      declaration=out.readPointer();
      graphicsEvent({event:'D3D8.DrawIndexedPrimitive.extra-pass',caller:this.returnAddress.toString(),
        declaration:declaration.toString(),declarationHr:hr,args:[1,2,3,4,5].map(i=>args[i].toUInt32())});
      flushGraphicsTrace('before-extra-pass');
    } catch(e) {graphicsEvent({event:'diagnostic-error',message:String(e)});flushGraphicsTrace('observation-error');}
    finally {if(!declaration.isNull())method(declaration,2,'uint',['pointer'])(declaration);}
  }});
}
setInterval(()=>rpc.exports.recapture(),2000);
let gameWindow = ptr(0);
let frameNotified = false;
let quadTraceFrames = 0;
let quadTraceRows = [];
const quadTraceSeen = new Set();
rpc.exports.tracequads = function() {
  if(gameWindow.isNull() || quadTraceFrames)throw new Error('Owned renderer must be ready and idle');
  quadTraceRows=[];quadTraceSeen.clear();quadTraceFrames=3;
};
// A bounded, read-only observation in the existing capture session. Separate
// temporary Frida sessions can disrupt the resident capture/audio guards.
function hookQuadTrace(device) {
  Interceptor.attach(device.readPointer().add(72*4).readPointer(),{onEnter(args){
    if(!quadTraceFrames || !args[0].equals(device) || quadTraceRows.length>=64)return;
    if(args[1].toUInt32()!==6 || args[2].toUInt32()!==2 || args[4].toUInt32()!==28 || args[3].isNull())return;
    const vertices=[0,1,2,3].map(i=>{
      const p=args[3].add(i*28);return [p.readFloat(),p.add(4).readFloat(),p.add(20).readFloat(),p.add(24).readFloat()];
    });
    if(!vertices.flat().every(Number.isFinite))return;
    const xs=vertices.map(v=>v[0]),ys=vertices.map(v=>v[1]);
    const bounds=[Math.min(...xs),Math.min(...ys),Math.max(...xs),Math.max(...ys)];
    if(bounds[2]-bounds[0]<100 || bounds[3]-bounds[1]<100)return;
    const caller=this.returnAddress.toString(),key=JSON.stringify([bounds,caller]);
    if(quadTraceSeen.has(key))return;quadTraceSeen.add(key);
    quadTraceRows.push({bounds,vertices,caller});
  }});
}
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
    hookQuadTrace(device);
    hookGraphicsTrace(device);
    try{fixWindowedReset(device);}catch(e){send({type:'capture-error',message:String(e)});}
    const present=device.readPointer().add(15*4).readPointer();
    send({type:'capture-status',event:'D3D8 device hooked'});
    Interceptor.attach(present,{onEnter(args){
      if(quadTraceFrames && args[0].equals(device) && --quadTraceFrames===0)
        send({type:'render-trace',quads:quadTraceRows});
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
