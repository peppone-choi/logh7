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
// Reuse the resident attachment for controlled IME commits (run-271 method).
// Operate only in this process's input window thread; never use global input.
let koreanCommit=null,koreanCommitHooks=false;
function installKoreanCommitHooks() {
  if(koreanCommitHooks)return;
  const user=Process.getModuleByName('user32.dll'),imm=Process.getModuleByName('imm32.dll');
  const thread=new NativeFunction(user.getExportByName('GetWindowThreadProcessId'),'uint',['pointer','pointer'],'stdcall');
  const post=new NativeFunction(user.getExportByName('PostMessageW'),'int',['pointer','uint','uint','int'],'stdcall');
  const setOpen=new NativeFunction(imm.getExportByName('ImmSetOpenStatus'),'int',['pointer','int'],'stdcall');
  const setString=new NativeFunction(imm.getExportByName('ImmSetCompositionStringW'),'int',['pointer','uint','pointer','uint','pointer','uint'],'stdcall');
  const notify=new NativeFunction(imm.getExportByName('ImmNotifyIME'),'int',['pointer','uint','uint','uint'],'stdcall');
  const peek=new NativeFunction(user.getExportByName('PeekMessageW'),'int',['pointer','pointer','uint','uint','uint'],'stdcall');
  Interceptor.attach(imm.getExportByName('ImmGetCompositionStringA'),{
    onEnter(a){this.result=a[1].toUInt32()===0x800;this.buffer=a[2];},
    onLeave(ret){if(koreanCommit && this.result && !this.buffer.isNull() && ret.toInt32()>0)++koreanCommit.calls;}
  });
  for(const name of ['PeekMessageA','PeekMessageW'])Interceptor.attach(user.getExportByName(name),{onLeave(){
    if(!koreanCommit?.pending)return;
    const hwnd=ptr(0x2214650).readPointer(),pid=Memory.alloc(4);
    if(hwnd.isNull() || thread(hwnd,pid)!==Process.getCurrentThreadId() || pid.readU32()!==Process.id)return;
    const state=koreanCommit;state.pending=false;
    try {
      const context=ptr(0x221464c).readPointer();if(context.isNull())throw new Error('No owned IME context');
      const wide=Memory.allocUtf16String(state.text);
      state.open=setOpen(context,1);state.set=setString(context,9,wide,state.text.length*2,ptr(0),0);
      if(!state.open || !state.set)throw new Error('Owned IME did not accept the composition');
      state.complete=notify(context,0x15,1,0);
      if(!state.complete)throw new Error('Owned IME did not complete the composition');
      const m=Memory.alloc(48);state.echoes=0;
      while(peek(m,hwnd,0x102,0x102,1))++state.echoes;
      state.posted=post(hwnd,0x10f,0,0x800);
    } catch(e) {state.error=String(e);}
  }});
  koreanCommitHooks=true;
}
rpc.exports.commitkorean=function(text) {
  if(typeof text!=='string' || text.length<1 || text.length>511 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/.test(text))
    throw new Error('Invalid bounded IME result');
  if(koreanCommit?.pending)throw new Error('An owned IME commit is already pending');
  installKoreanCommitHooks();koreanCommit={text,pending:true,calls:0};
};
rpc.exports.koreancommitstate=function(){return koreanCommit;};
function tacticalMenuState() {
  try {
  const ctx=ptr(0x2215e2c).readPointer();if(ctx.isNull())return null;
  const manager=ctx.add(12).readPointer();if(manager.isNull())return null;
  const menu=manager.add(0xd4).readPointer();if(menu.isNull())return null;
  const slot=menu.add(0x1138).readS32();
  let x=0,y=0,current=menu;
  for(let i=0;i<8 && !current.isNull();++i) {
    x+=current.add(12).readS32();y+=current.add(16).readS32();
    const parent=current.add(8).readS32();if(parent<0 || parent>=1024)break;
    current=manager.add(4+parent*4).readPointer();
  }
  const widget=slot>=0 && slot<2048 ? manager.add(0x28ca2c+slot*0xf18) : ptr(0);
  return {initialized:ctx.add(4).readU32(),allowed:manager.add(0x3a0).readU8(),menuActive:menu.add(5).readU8(),slot,
    widget:widget.isNull()?null:{exists:widget.add(8).readU8(),enabled:widget.add(0x15).readU8(),visible:widget.add(0x18).readU8(),
      bounds:[x+widget.add(0x20).readS32(),y+widget.add(0x24).readS32(),widget.add(0x2c).readS32(),widget.add(0x30).readS32()]}};
  } catch(e) {return {error:String(e)};}
}
let fortressInputBudget=0;
const fortressInputEvents=[];
function fortressInputArmed() {
  if(fortressInputBudget<=0 || ptr(0x77a8f8).readU32()!==5 || ptr(0x22166a9).readU8()!==8)return false;
  const ctx=ptr(0x2215e2c).readPointer();return !ctx.isNull() && ctx.add(4).readU32()===9;
}
function fortressInputEdge() {
  return ptr(0x22142b0).readU32()!==0 && ptr(0x22142b0).readU32()!==ptr(0x22142b4).readU32();
}
function recordFortressInput(row) {
  if(fortressInputBudget<=0)return;
  --fortressInputBudget;row.at=Date.now();fortressInputEvents.push(row);
  if(fortressInputEvents.length>32)fortressInputEvents.shift();
}
const fortressInputParents=new Map();
let fortressBlockersInstalled=false;
function installFortressBlockers() {
  if(fortressBlockersInstalled)return;
  const entries=[[0x5015f0,[0x83,0xec,0x20,0x53,0x8b,0x5c,0x24,0x2c]],
    [0x50c180,[0x83,0xec,8,0x53,0x55,0x56,0x57,0x89]],
    [0x501d60,[0x83,0xec,0x1c,0x53,0x55,0x56,0x8d,0x44]],
    [0x4ec2e0,[0x8b,0x44,0x24,0x0c,0x8b,0x4c,0x24,8]],
    [0x4c7e60,[0x8b,0x44,0x24,8,0x53,0x55,0x56,0x8b,0x30]],
    [0x505b50,[0x51,0x8b,0x54,0x24,8,0x8a,0x42,8]]];
  if(!entries.every(([a,b])=>b.every((v,i)=>ptr(a).add(i).readU8()===v)))return;
  fortressBlockersInstalled=true;
  Interceptor.attach(ptr(0x505b50),{
    onEnter(args) {
      if(fortressInputBudget<=0 || this.returnAddress.toUInt32()!==0x51046c || ptr(0x77a8f8).readU32()!==5)return;
      recordFortressInput({site:'0x505b50',row:args[2].toInt32(),column:args[3].toInt32(),
        text:args[1].isNull()?null:args[1].readUtf16String(128).split('\0')[0]});
    }
  });
  Interceptor.attach(ptr(0x4c7e60),{
    onEnter(args) {
      const caller=this.returnAddress.toUInt32(),ctx=ptr(0x2215e2c).readPointer();
      if(fortressInputBudget<=0 || caller<0x51032c || caller>0x51058e || ctx.isNull() ||
          ![0x12,0x13].includes(ctx.add(4).readU32()) || ptr(0x77a8f8).readU32()!==5)return;
      this.cursor=args[1];this.row={site:'0x4c7e60',caller:'0x'+caller.toString(16),
        table:args[0].toString(),cursor:this.cursor.readS32(),mode:args[2].toUInt32()&255,base:args[3].toUInt32()};
    },
    onLeave(ret) {
      if(!this.row)return;
      this.row.next=this.cursor.readS32();this.row.unit=ret.isNull()?0:ret.add(4).readU32();recordFortressInput(this.row);
    }
  });
  Interceptor.attach(ptr(0x5015f0),{
    onEnter(args) {
      const caller=this.returnAddress.toUInt32();
      if(![0x50f640,0x50f5ee].includes(caller) || !fortressInputArmed() || !fortressInputEdge())return;
      this.thread=this.threadId;this.previous=fortressInputParents.get(this.thread);
      this.row={site:'0x5015f0',caller:'0x'+caller.toString(16),event:args[0].toUInt32(),peek:args[3].toUInt32(),
        menu:this.context.ecx.readU32(),widget:args[1].toString(),
        widgetFlags:args[1].isNull()?null:[args[1].add(8).readU8(),args[1].add(0x15).readU8(),args[1].add(0x18).readU8()],
        down:ptr(0x22142b0).readU32(),previous:ptr(0x22142b4).readU32(),
        current:[ptr(0x22143dc).readS32(),ptr(0x22143e0).readS32()],
        pressed:[ptr(0x2214408).readS32(),ptr(0x221440c).readS32()],overlap:[]};
      this.output=args[2];fortressInputParents.set(this.thread,this.row);
    },
    onLeave(ret) {
      if(!this.row)return;
      this.row.result=ret.toUInt32()&255;
      this.row.coordinates=this.output.isNull()?null:[0,4,8,12].map(i=>this.output.add(i).readS32());
      fortressInputParents.set(this.thread,this.previous);recordFortressInput(this.row);
    }
  });
  for(const address of [0x50c180,0x501d60])Interceptor.attach(ptr(address),{
    onEnter(args) {
      this.parent=fortressInputParents.get(this.threadId);if(!this.parent)return;
      const point=p=>p.isNull()?null:[p.readS32(),p.add(4).readS32()];
      this.row={site:'0x'+address.toString(16),current:point(args[3]),left:point(args[2]),right:point(args[1])};
    },
    onLeave(ret) {
      if(this.row && this.parent.overlap.length<8){this.row.blocked=ret.toUInt32()&255;this.parent.overlap.push(this.row);}
    }
  });
  Interceptor.attach(ptr(0x4ec2e0),{
    onEnter(args) {
      if(this.returnAddress.toUInt32()!==0x50f60f || !fortressInputArmed() || !fortressInputEdge())return;
      this.output=args[2];this.row={site:'0x4ec2e0',screen:[args[0].toInt32(),args[1].toInt32()],
        origin:[0,4,8].map(i=>ptr(0x2215530).add(i).readFloat())};
    },
    onLeave() {
      if(!this.row)return;this.row.target=[0,4,8].map(i=>this.output.add(i).readFloat());recordFortressInput(this.row);
    }
  });
}
rpc.exports.fortressstate = function(id) {
  if(!Number.isInteger(id) || id<1 || id>65535)throw new Error('Invalid owned fortress ID');
  const world=ptr(0x7ccffc).readPointer();
  if(world.isNull() || !world.add(0x126718).readU8())return {base:null,tactical:false};
  const lookup=new NativeFunction(ptr(0x4c7cd0),'pointer',['pointer','uint','uint8','uint8','uint8','uint8'],'mscdecl');
  const base=lookup(world.add(0x126718),id,0,255,255,255);
  if(!base.isNull())installFortressBlockers();
  fortressInputBudget=32;
  const modeOf=new NativeFunction(ptr(0x4b5d50),'uint8',['pointer'],'thiscall');
  const baseOf=new NativeFunction(ptr(0x4b5e20),'uint',['pointer'],'thiscall');
  const ships=[];
  for(let i=0;i<600 && ships.length<32;++i) {
    const unit=world.add(0x12671c+i*0x9ec);
    if(!unit.readU8() || unit.add(8).readU8()!==1)continue;
    ships.push({id:unit.add(4).readU32(),kind:unit.add(0x8bc).readU16(),
      normal:unit.add(0x8d4).readU32(),alive:unit.add(0x8d8).readU32(),supplies:unit.add(0x950).readU32(),
      morale:unit.add(0x954).readU8(),mode:unit.add(0x974).readU8(),base:unit.add(0x978).readU32(),
      subtype:unit.add(9).readU8(),effectiveMode:modeOf(unit),effectiveBase:baseOf(unit),
      specName:world.add(0x2c1a78+unit.add(0x8bc).readU16()*0x2a8+12).readUtf16String(32).split('\0')[0],
      pose:[0x14,0x18,0x1c].map(i=>unit.add(i).readFloat())});
  }
  const display=ptr(0x22293fc).readPointer();
  let supportList=null;
  try {
    const ctx=ptr(0x2215e2c).readPointer(),manager=ctx.isNull()?ptr(0):ctx.add(12).readPointer();
    const sheet=manager.isNull()?ptr(0):manager.add(4+57*4).readPointer();
    if(!sheet.isNull()) {
      const list=new NativeFunction(ptr(0x502780),'pointer',['pointer','int','int'],'thiscall')(sheet,4,0);
      supportList={exists:!list.isNull()};
      if(!list.isNull()) {
        supportList.flags=[list.add(8).readU8(),list.add(0x15).readU8(),list.add(0x18).readU8()];
        supportList.bounds=[0x20,0x24,0x2c,0x30].map(i=>list.add(i).readS32());
        if(supportList.flags[0])supportList.rowHeight=new NativeFunction(ptr(0x5056e0),'int',['pointer','pointer'],'thiscall')(sheet,list);
        const index=sheet.add(0x932c).readS32(),pool=sheet.add(0x30).readPointer();
        if(index>=0 && index<100000 && !pool.isNull()) {
          const slot=pool.add(0xbc3fa8+index*4);
          supportList.firstCellHandle=slot.readU32();
          const cell=new NativeFunction(ptr(0x4eae30),'pointer',['pointer'],'mscdecl')(slot);
          const length=new NativeFunction(ptr(0x4eae10),'uint',['pointer'],'mscdecl')(slot);
          if(!cell.isNull() && length<=2048)supportList.firstCell=cell.readUtf16String(length);
        }
      }
    }
  } catch(e) {supportList={...supportList,error:String(e)};}
  return {base:base.isNull()?null:{id:base.add(4).readU32(),type:base.add(8).readU8(),owner:base.add(0x8c8).readU32(),
    pose:[0x14,0x18,0x1c].map(i=>base.add(i).readFloat()),
    kind:base.add(0x8bc).readU16(),charge:[base.add(0x5bc).readU32(),base.add(0x5c0).readU32()]},
    projection:{viewport:display.isNull()?null:[display.add(0x2a4c8).readU32(),display.add(0x2a4cc).readU32()],
      scale:[ptr(0x2229970).readFloat(),ptr(0x2229984).readFloat()],matrix:Array.from({length:16},(_,i)=>ptr(0x2229674+i*4).readFloat())},
    ships,supportList,input:fortressInputEvents.slice(),aimBase:ptr(0x2216674).readU32(),command:ptr(0x22166a9).readU8(),
    tab:ptr(0x77a8f8).readU32(),mode:ptr(0x2216694).readU32(),
    fortCount:ptr(0x221666c).readU32(),fortId:ptr(0x2215528).readU32(),selected:ptr(0x2216664).readU32(),menu:tacticalMenuState()};
};
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
