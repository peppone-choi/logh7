'use strict';
// Owned x86 QA process only. No endpoint/session volume writes.
// COM activation is deliberately denied, not emulated: no audio COM server
// (or shell broker) is allowed to escape the process-local output policy.
const callbacks = [], replaced = new Set(), attached = new Set();
const audit = {ready:false, failed:null, arch:Process.arch, blocked:{}, modules:[],
  policy:'deny-playback-and-COM-activation; deny-child-creation', version:2,
  installed:[], directsound:[], device_objects:0, buffer_objects:0};
const terminate = new NativeFunction(Process.getModuleByName('kernel32.dll')
  .getExportByName('TerminateProcess'), 'int', ['pointer','uint'], 'stdcall');
function fatal(e) {
  audit.ready=false; audit.failed=String(e); send({type:'audio-fatal',error:String(e)});
  terminate(ptr(-1), 0xA001);
}
function count(name) { audit.blocked[name]=(audit.blocked[name]||0)+1; }
function deny(address, name, args, result) {
  const key=address.toString();
  if(replaced.has(key)) return;
  const cb=new NativeCallback(function(){ count(name); return result; }, 'int', args, 'stdcall');
  callbacks.push(cb); Interceptor.replace(address, cb); replaced.add(key); audit.installed.push(name);
}
function hook(address,name,events) {
  const key=name+':'+address;
  if(attached.has(key))return;
  Interceptor.attach(address,events); attached.add(key);
}
function slot(obj,n) {
  if(obj.isNull())throw new Error('null object');
  return obj.readPointer().add(n*4).readPointer();
}
const qiRoles=new Map(), qiAddresses=new Set();
const bufferIids=new Set(['85fa9a278149ce11a5210020af0be560',
                         '49a425682475824d920f50e36ab3ab1e']);
function iidHex(p) {
 return Array.from(new Uint8Array(p.readByteArray(16))).map(x=>x.toString(16).padStart(2,'0')).join('');
}
function qi(obj,role) {
 qiRoles.set(obj.toString(),role);
 const address=slot(obj,0), key=address.toString();
 if(qiAddresses.has(key))return;
 if(replaced.has(key))throw new Error('QI address already replaced');
 const original=new NativeFunction(address,'int',['pointer','pointer','pointer'],'stdcall');
 const cb=new NativeCallback(function(self,iid,out){
   try {
     if(out.isNull())return -2147467261;
     out.writePointer(ptr(0));
     if(qiRoles.get(self.toString())!=='buffer'||iid.isNull()||!bufferIids.has(iidHex(iid))) {
       count('unsupported.QueryInterface');return -2147467262;
     }
     const hr=original(self,iid,out);
     if(hr>=0){buffer(out.readPointer());Interceptor.flush();}
     else out.writePointer(ptr(0));
     return hr;
   } catch(e){fatal(e);return -2147467262;}
 },'int',['pointer','pointer','pointer'],'stdcall');
 callbacks.push(cb,original);Interceptor.replace(address,cb);qiAddresses.add(key);replaced.add(key);
}
function buffer(obj) {
 audit.buffer_objects++;
 deny(slot(obj,12),'IDirectSoundBuffer.Play',['pointer','uint','uint','uint'],0);
 qi(obj,'buffer');
}
function device(obj) {
  audit.device_objects++;
  hook(slot(obj,3),'CreateSoundBuffer',{
    onEnter(a){this.out=a[2];},
    onLeave(r){if(r.toInt32()>=0)try {buffer(this.out.readPointer());Interceptor.flush();}catch(e){fatal(e);}}
  });
  hook(slot(obj,5),'DuplicateSoundBuffer',{
    onEnter(a){this.out=a[2];},
    onLeave(r){if(r.toInt32()>=0)try {buffer(this.out.readPointer());Interceptor.flush();}catch(e){fatal(e);}}
  });
  // Reject other interfaces rather than let a new unobserved vtable escape.
  qi(obj,'device');
}
function audioClient(obj) {
  deny(slot(obj,10),'IAudioClient.Start',['pointer'],0);
  qi(obj,'client');
}
function immDevice(obj) {
  hook(slot(obj,3),'IMMDevice.Activate',{
    onEnter(a){this.iid=a[1].readByteArray(16);this.out=a[4];},
    onLeave(r){if(r.toInt32()>=0)try {
      const b=Array.from(new Uint8Array(this.iid)).map(x=>x.toString(16).padStart(2,'0')).join('');
      if(b!=='4cadb91cfadb324cb178c2f568a703b2') throw new Error('unsupported IMMDevice.Activate IID');
      audioClient(this.out.readPointer());Interceptor.flush();
    }catch(e){fatal(e);}}
  });
}
function exp(m,n){return m.findExportByName(n);}
function block(m,n,args,value) {const p=exp(m,n);if(p)deny(p,n,args,value);}
const comArgs={
 CoCreateInstance:['pointer','pointer','uint','pointer','pointer'],
 CoCreateInstanceEx:['pointer','pointer','uint','pointer','uint','pointer'],
 CoGetClassObject:['pointer','uint','pointer','pointer','pointer'],
 CoGetObject:['pointer','pointer','pointer','pointer'],
 CoGetInstanceFromFile:['pointer','pointer','pointer','uint','uint','pointer','uint','pointer'],
 CoGetInstanceFromIStorage:['pointer','pointer','pointer','uint','pointer','uint','pointer']
};
function install(m) {
  const name=m.name.toLowerCase();audit.modules.push(name);
  if(name==='dsound.dll') {
    for(const n of ['DirectSoundCreate','DirectSoundCreate8']) {
      const p=exp(m,n);if(!p)throw new Error('missing '+n);
      hook(p,n,{onEnter(a){count(n);this.out=a[1];},
        onLeave(r){audit.directsound.push({api:n,hresult:r.toInt32(),object:this.out.isNull()?'0x0':this.out.readPointer().toString()});
          if(r.toInt32()>=0)try {device(this.out.readPointer());Interceptor.flush();}catch(e){fatal(e);}}});
    }
    block(m,'DirectSoundFullDuplexCreate',Array(10).fill('pointer'),-2147024891);
    block(m,'DllGetClassObject',['pointer','pointer','pointer'],-2147024891);
  }
  if(name==='winmm.dll'||name==='winmmbase.dll') {
    for(const [n,a,v] of [
      ['waveOutWrite',['pointer','pointer','uint'],8],
      ['waveOutOpen',['pointer','uint','pointer','pointer','pointer','uint'],8],
      ['PlaySoundA',['pointer','pointer','uint'],1],['PlaySoundW',['pointer','pointer','uint'],1],
      ['sndPlaySoundA',['pointer','uint'],1],['sndPlaySoundW',['pointer','uint'],1],
      ['midiOutShortMsg',['pointer','uint'],8],['midiOutLongMsg',['pointer','pointer','uint'],8],
      ['midiStreamOut',['pointer','pointer','uint'],8],
      ['mciSendStringA',['pointer','pointer','uint','pointer'],1],
      ['mciSendStringW',['pointer','pointer','uint','pointer'],1],
      ['mciSendCommandA',['uint','uint','uint','pointer'],1],
      ['mciSendCommandW',['uint','uint','uint','pointer'],1]]) block(m,n,a,v);
  }
  if(name==='ole32.dll'||name==='combase.dll')
    for(const n in comArgs) block(m,n,comArgs[n],-2147024891);
  if(name==='kernel32.dll'||name==='kernelbase.dll') {
    block(m,'Beep',['uint','uint'],1);
    block(m,'WinExec',['pointer','uint'],0);
    for(const n of ['CreateProcessA','CreateProcessW'])
      block(m,n,Array(10).fill('pointer'),0);
  }
  if(name==='kernel32.dll'||name==='kernelbase.dll'||name==='advapi32.dll') {
    for(const n of ['CreateProcessAsUserA','CreateProcessAsUserW'])
      block(m,n,Array(11).fill('pointer'),0);
    for(const n of ['CreateProcessInternalA','CreateProcessInternalW'])
      block(m,n,Array(12).fill('pointer'),0);
    block(m,'CreateProcessWithLogonW',Array(11).fill('pointer'),0);
    block(m,'CreateProcessWithTokenW',Array(9).fill('pointer'),0);
  }
  if(name==='ntdll.dll') {
    block(m,'NtCreateUserProcess',Array(11).fill('pointer'),-1073741790);
    block(m,'NtCreateProcess',Array(8).fill('pointer'),-1073741790);
    block(m,'NtCreateProcessEx',Array(9).fill('pointer'),-1073741790);
  }
  if(name==='mmdevapi.dll') {
    const p=exp(m,'ActivateAudioInterfaceAsync');
    if(!p)throw new Error('missing ActivateAudioInterfaceAsync');
    deny(p,'ActivateAudioInterfaceAsync',Array(5).fill('pointer'),-2147024891);
  }
  if(name==='user32.dll') block(m,'MessageBeep',['uint'],1);
  if(name==='shell32.dll') {
    for(const n of ['ShellExecuteA','ShellExecuteW']) block(m,n,Array(6).fill('pointer'),0);
    for(const n of ['ShellExecuteExA','ShellExecuteExW']) block(m,n,['pointer'],0);
  }
  if(/^(xaudio|xact|openal|avrt|ksuser|mfplat|mfplay|audioses)/.test(name))
    throw new Error('unsupported output module '+name);
  Interceptor.flush();
}
if(Process.arch!=='ia32')throw new Error('x86 only');
const queryJob = new NativeFunction(Process.getModuleByName('kernel32.dll')
  .getExportByName('QueryInformationJobObject'), 'int',
  ['pointer','int','pointer','uint','pointer'], 'stdcall');
function effectiveUi() {
  const flags=Memory.alloc(4);
  if(!queryJob(ptr(0),4,flags,4,ptr(0)))throw new Error('Immediate Job query failed');
  const value=flags.readU32();
  if(value!==255)throw new Error('Immediate Job UI restrictions !=255');
  return value;
}
audit.effective_ui_flags=effectiveUi();
if(typeof Process.attachModuleObserver!=='function')throw new Error('synchronous module observer unavailable');
const observer=Process.attachModuleObserver({
 onAdded(m){try{install(m);}catch(e){fatal(e);}},
 onRemoved(m){if(/^(dsound|winmm|winmmbase|ole32|combase)\.dll$/i.test(m.name))fatal('audio module unloaded');}
});
Interceptor.flush();
audit.ready=!audit.failed;
rpc.exports={
 audiostatus(){audit.effective_ui_flags=effectiveUi();return audit;},
 // Harmless fixture tests real x86 native callbacks/vtables; never game RPC.
 fixtureobjects(kind,address){
   if(!globalThis.AUDIO_FIXTURE)throw new Error('fixture RPC disabled');
   const p=ptr(address);if(kind==='buffer')buffer(p);else if(kind==='device')device(p);
   else if(kind==='client')audioClient(p);else if(kind==='imm')immDevice(p);
   else throw new Error('bad fixture kind');
   Interceptor.flush();return true;
 }
};
send({type:'audio-ready',...audit});

rpc.exports.audioselftest=function(){
 if(!globalThis.AUDIO_FIXTURE)throw new Error('fixture only');
 let originals=0;
 const retained=[];
 function cb(fn,args){const x=new NativeCallback(fn,'int',args,'stdcall');retained.push(x);return x;}
 function object(n){const v=Memory.alloc(n*4),o=Memory.alloc(4);o.writePointer(v);return {v:v,o:o};}
 function put(x,n,p){x.v.add(n*4).writePointer(p);}
 const b=object(13);
 put(b,12,cb(function(){originals++;return 42;},['pointer','uint','uint','uint']));
 put(b,0,cb(function(o,i,out){out.writePointer(b.o);return 0;},['pointer','pointer','pointer']));
 const d=object(6);
 put(d,0,cb(function(){return -1;},['pointer','pointer','pointer']));
 put(d,3,cb(function(o,desc,out,agg){out.writePointer(b.o);return 0;},['pointer','pointer','pointer','pointer']));
 put(d,5,cb(function(o,src,out){out.writePointer(b.o);return 0;},['pointer','pointer','pointer']));
 device(d.o);Interceptor.flush();
 const out=Memory.alloc(4);
 new NativeFunction(slot(d.o,3),'int',['pointer','pointer','pointer','pointer'],'stdcall')(d.o,ptr(0),out,ptr(0));
 const play=new NativeFunction(slot(out.readPointer(),12),'int',['pointer','uint','uint','uint'],'stdcall');
 const first=play(b.o,0,0,0),repeat=play(b.o,0,0,0);
 new NativeFunction(slot(d.o,5),'int',['pointer','pointer','pointer'],'stdcall')(d.o,b.o,out);
 const duplicate=play(out.readPointer(),0,0,0);
 const client=object(11);
 put(client,0,cb(function(){return -1;},['pointer','pointer','pointer']));
 put(client,10,cb(function(){originals++;return 42;},['pointer']));
 const imm=object(4);
 put(imm,3,cb(function(o,i,c,p,out){out.writePointer(client.o);return 0;},['pointer','pointer','uint','pointer','pointer']));
 immDevice(imm.o);Interceptor.flush();
 const iid=Memory.alloc(16);iid.writeByteArray([0x4c,0xad,0xb9,0x1c,0xfa,0xdb,0x32,0x4c,0xb1,0x78,0xc2,0xf5,0x68,0xa7,0x03,0xb2]);
 new NativeFunction(slot(imm.o,3),'int',['pointer','pointer','uint','pointer','pointer'],'stdcall')(imm.o,iid,23,ptr(0),out);
 const start=new NativeFunction(slot(out.readPointer(),10),'int',['pointer'],'stdcall')(client.o);
 // Retain the allocated vtables and callbacks while instrumentation remains installed.
 callbacks.push(...retained,b,d,client,imm,out,iid);
 return {first,repeat,duplicate,start,originals,arch:Process.arch};
};

rpc.exports.audiofixtureload=function(path) {
 if(!globalThis.AUDIO_FIXTURE)throw new Error('fixture only');
 const load=new NativeFunction(Process.getModuleByName('kernel32.dll').getExportByName('LoadLibraryW'),'pointer',['pointer'],'stdcall');
 return load(Memory.allocUtf16String(path)).toString();
};

// Negative IID checks use short vtables which must never be inspected as buffers.
rpc.exports.audionegativetest=function(){
 if(!globalThis.AUDIO_FIXTURE)throw new Error('fixture only');
 let originals=0;const keep=[];
 const v=Memory.alloc(13*4),o=Memory.alloc(4);o.writePointer(v);
 const play=new NativeCallback(()=>0,'int',['pointer','uint','uint','uint'],'stdcall');
 const short=Memory.alloc(4);short.writePointer(Memory.alloc(4*4));
 const original=new NativeCallback(function(self,iid,out){originals++;out.writePointer(bufferIids.has(iidHex(iid))?o:short);return 0;},
    'int',['pointer','pointer','pointer'],'stdcall');
 v.writePointer(original);v.add(12*4).writePointer(play);buffer(o);Interceptor.flush();
 const f=new NativeFunction(v.readPointer(),'int',['pointer','pointer','pointer'],'stdcall');
 const out=Memory.alloc(4),iid=Memory.alloc(16),results=[];
 for(const hex of ['0000000000000000c000000000000046','830721b0cd89d011af0800a0c925cd16',
                   '86fa9a278149ce11a5210020af0be560','30acef315c51d011a9aa00aa0061be93']){
   iid.writeByteArray(hex.match(/../g).map(x=>parseInt(x,16)));out.writePointer(short);
   const hr=f(o,iid,out);results.push({hr,null_output:out.readPointer().isNull()});
 }
 const allowed=[];
 for(const hex of bufferIids){
   iid.writeByteArray(hex.match(/../g).map(x=>parseInt(x,16)));
   const hr=f(o,iid,out);
   const p=new NativeFunction(slot(out.readPointer(),12),'int',['pointer','uint','uint','uint'],'stdcall');
   allowed.push({hr,play:p(out.readPointer(),0,0,0)});
 }
 out.writePointer(short);
 const nullIid=f(o,ptr(0),out),nullIidOutput=out.readPointer().isNull();
 const nullOut=f(o,iid,ptr(0));
 const d=Memory.alloc(4);d.writePointer(v);qi(d,'device');Interceptor.flush();
 out.writePointer(short);
 const shared=f(d,iid,out);
 callbacks.push(v,o,short,play,original,f,out,iid,d,...keep);
 return {results,originals,allowed,shared_hr:shared,shared_null:out.readPointer().isNull(),null_iid_hr:nullIid,null_iid_output:nullIidOutput,null_out_hr:nullOut};

};
rpc.exports.audiofixtureapidenials=function(){
 if(!globalThis.AUDIO_FIXTURE)throw new Error('fixture only');
 const load=new NativeFunction(Process.getModuleByName('kernel32.dll').getExportByName('LoadLibraryW'),'pointer',['pointer'],'stdcall');
 const specs={
 'advapi32.dll':[['CreateProcessAsUserA',11],['CreateProcessAsUserW',11],['CreateProcessWithLogonW',11],['CreateProcessWithTokenW',9]],
 'kernelbase.dll':[['CreateProcessInternalA',12],['CreateProcessInternalW',12]],
 'ntdll.dll':[['NtCreateProcess',8],['NtCreateProcessEx',9]],
 'mmdevapi.dll':[['ActivateAudioInterfaceAsync',5]]};
 const results=[];
 for(const name in specs){
   if(load(Memory.allocUtf16String(name)).isNull())throw new Error('fixture load failed '+name);
   const m=Process.getModuleByName(name);
   for(const [api,n] of specs[name]){
     const p=exp(m,api);if(!p){results.push({api,status:'NOT_EXPORTED'});continue;}
     if(!replaced.has(p.toString()))throw new Error('not blocked '+api);
     const hr=new NativeFunction(p,'int',Array(n).fill('pointer'),'stdcall')(...Array(n).fill(ptr(0)));
     results.push({api,status:'BLOCKED',hr});
   }
 }
 return results;
};
