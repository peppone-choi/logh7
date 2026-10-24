'use strict';
// Local x86 client QA: all DirectSound objects below are memory-only objects.
// Never call a native audio factory, open a device, or change host/session volume.
if (Process.arch !== 'ia32') throw new Error('Silent DirectSound requires x86');
const keep = [], replaced = new Set(), buffers = new Map();
const audit = {ready:false, failed:null, mode:'memory-only-directsound',
  native_audio_factories_called:0, devices:0, buffers:0, plays:0, blocked:{}, installed:[]};
const terminate = new NativeFunction(Process.getModuleByName('kernel32.dll')
  .getExportByName('TerminateProcess'), 'int', ['pointer','uint'], 'stdcall');
function fatal(error) {
  audit.ready=false; audit.failed=String(error);
  send({type:'audio-fatal', error:audit.failed}); terminate(ptr(-1),0xA002);
}
function callback(fn,args) {
  const value=new NativeCallback(function(...a) {
    try { return fn(...a); } catch(e) { fatal(e); return -2147467259; }
  },'int',args,'stdcall'); keep.push(value); return value;
}
function object(methods) {
  const table=Memory.alloc(methods.length*4), value=Memory.alloc(4);
  methods.forEach(([fn,args],i)=>table.add(i*4).writePointer(callback(fn,args)));
  value.writePointer(table); keep.push(table,value); return value;
}
const P='pointer', U='uint', I='int', fail=()=>-2147467263, ok=()=>0;
const iid=p=>Array.from(new Uint8Array(p.readByteArray(16))).map(x=>x.toString(16).padStart(2,'0')).join('');
const unknown='0000000000000000c000000000000046';
function query(value,allowed) {
  return (self,id,out)=>{
    if(out.isNull())return -2147467261;
    out.writePointer(ptr(0));
    if(id.isNull()||!allowed.has(iid(id)))return -2147467262;
    out.writePointer(value());return 0;
  };
}
function format(source) {
  const value=Memory.alloc(18); value.writeByteArray(new Uint8Array(18)); keep.push(value);
  if(!source.isNull())value.writeByteArray(source.readByteArray(18));
  else {value.writeU16(1);value.add(2).writeU16(2);value.add(4).writeU32(44100);
    value.add(8).writeU32(176400);value.add(12).writeU16(4);value.add(14).writeU16(16);}
  return value;
}
function makeBuffer(desc,duplicate) {
  const flags=duplicate?duplicate.flags:desc.add(4).readU32();
  const length=duplicate?duplicate.length:(desc.add(8).readU32()||4096);
  if(length>32*1024*1024||buffers.size>=256)throw new Error('Silent buffer limit');
  const pcm=Memory.alloc(length), fmt=duplicate?format(duplicate.fmt):format(desc.add(16).readPointer());
  keep.push(pcm);
  const b={flags,length,pcm,fmt,position:0,started:0,playing:false,loop:false,volume:0,pan:0,
    frequency:fmt.add(4).readU32()||44100};
  function position() {
    if(!b.playing)return b.position;
    const align=Math.max(1,b.fmt.add(12).readU16());
    const offset=b.position+Math.floor((Date.now()-b.started)*b.frequency/1000)*align;
    if(!b.loop&&offset>=length){b.playing=false;b.position=0;return 0;}
    return offset%length;
  }
  let value;
  const allowed=new Set([unknown,'85fa9a278149ce11a5210020af0be560','49a425682475824d920f50e36ab3ab1e']);
  value=object([
    [query(()=>value,allowed),[P,P,P]], [()=>2,[P]], [()=>1,[P]],
    [(self,out)=>{if(out.isNull()||out.readU32()<20)return -2147024809;
      out.add(4).writeU32(flags);out.add(8).writeU32(length);out.add(12).writeU32(0);out.add(16).writeU32(0);return 0;},[P,P]],
    [(self,play,write)=>{const at=position();if(!play.isNull())play.writeU32(at);
      if(!write.isNull())write.writeU32((at+Math.max(4,Math.floor(b.frequency*0.015)*b.fmt.add(12).readU16()))%length);return 0;},[P,P,P]],
    [(self,out,size,written)=>{if(!written.isNull())written.writeU32(18);
      if(!out.isNull()){if(size<18)return -2147024809;out.writeByteArray(b.fmt.readByteArray(18));}return 0;},[P,P,U,P]],
    [(self,out)=>{out.writeS32(b.volume);return 0;},[P,P]],
    [(self,out)=>{out.writeS32(b.pan);return 0;},[P,P]],
    [(self,out)=>{out.writeU32(b.frequency);return 0;},[P,P]],
    [(self,out)=>{position();out.writeU32(b.playing?(1|(b.loop?4:0)):0);return 0;},[P,P]],
    [fail,[P,P,P]],
    [(self,offset,size,one,n1,two,n2,lockFlags)=>{
      if(one.isNull()||n1.isNull())return -2147024809;
      if(lockFlags&1)offset=position();
      if(lockFlags&2){offset=0;size=length;}
      if(offset>=length||size>length)return -2147024809;
      const first=Math.min(size,length-offset),second=size-first;
      if(second&&(two.isNull()||n2.isNull()))return -2147024809;
      one.writePointer(pcm.add(offset));n1.writeU32(first);
      if(!two.isNull())two.writePointer(second?pcm:ptr(0));if(!n2.isNull())n2.writeU32(second);return 0;
    },[P,U,U,P,P,P,P,U]],
    [(self,reserved,priority,playFlags)=>{b.position=position();b.started=Date.now();b.playing=true;b.loop=!!(playFlags&1);audit.plays++;return 0;},[P,U,U,U]],
    [(self,at)=>{if(at>=length)return -2147024809;b.position=at;b.started=Date.now();return 0;},[P,U]],
    [(self,f)=>{b.fmt.writeByteArray(f.readByteArray(18));b.frequency=b.fmt.add(4).readU32();return 0;},[P,P]],
    [(self,v)=>{b.volume=v;return 0;},[P,I]],[(self,v)=>{b.pan=v;return 0;},[P,I]],
    [(self,v)=>{b.position=position();b.frequency=v||b.fmt.add(4).readU32();b.started=Date.now();return 0;},[P,U]],
    [()=>{b.position=position();b.playing=false;return 0;},[P]],
    [ok,[P,P,U,P,U]], [ok,[P]], [fail,[P,U,P,P]], [fail,[P,U,U,P]], [fail,[P,P,U,P,P]]
  ]);
  buffers.set(value.toString(),b);audit.buffers++;return value;
}
function makeDevice() {
  let value;
  value=object([
    [query(()=>value,new Set([unknown,'83fa9a278149ce11a5210020af0be560','937e0ac595f334489ef67fa99de50966'])),[P,P,P]],
    [()=>2,[P]],[()=>1,[P]],
    [(self,desc,out,outer)=>{if(!outer.isNull()||desc.isNull()||out.isNull())return -2147024809;
      out.writePointer(makeBuffer(desc));return 0;},[P,P,P,P]],
    [(self,out)=>{const size=out.readU32();if(size<96)return -2147024809;
      out.writeByteArray(new Uint8Array(96));out.writeU32(96);out.add(4).writeU32(15);
      out.add(8).writeU32(100);out.add(12).writeU32(200000);out.add(16).writeU32(1);return 0;},[P,P]],
    [(self,original,out)=>{const b=buffers.get(original.toString());if(!b)return -2147024809;
      out.writePointer(makeBuffer(ptr(0),b));return 0;},[P,P,P]],
    [ok,[P,P,U]],[ok,[P]],[(self,out)=>{out.writeU32(4);return 0;},[P,P]],
    [ok,[P,U]],[ok,[P,P]],[(self,out)=>{out.writeU32(1);return 0;},[P,P]]
  ]);
  audit.devices++;return value;
}
function replace(module,name,args,fn) {
  const address=module.findExportByName(name);if(!address)return;
  const key=address.toString();if(replaced.has(key))return;
  Interceptor.replace(address,callback(fn,args));replaced.add(key);audit.installed.push(name);
}
function deny(module,name,args,result) {
  replace(module,name,args,()=>{audit.blocked[name]=(audit.blocked[name]||0)+1;return result;});
}
function install(m) {
  const name=m.name.toLowerCase();
  if(name==='dsound.dll') {
    for(const api of ['DirectSoundCreate','DirectSoundCreate8']) {
      if(!m.findExportByName(api))throw new Error('Missing '+api);
      replace(m,api,[P,P,P],(guid,out,outer)=>{if(out.isNull()||!outer.isNull())return -2147024809;out.writePointer(makeDevice());return 0;});
    }
    deny(m,'DirectSoundFullDuplexCreate',Array(10).fill(P),-2147024891);
    deny(m,'DllGetClassObject',[P,P,P],-2147024891);
  }
  if(name==='winmm.dll'||name==='winmmbase.dll') {
    for(const [api,args,result] of [
      ['waveOutOpen',[P,U,P,P,P,U],8],['waveOutWrite',[P,P,U],8],
      ['PlaySoundA',[P,P,U],1],['PlaySoundW',[P,P,U],1],
      ['sndPlaySoundA',[P,U],1],['sndPlaySoundW',[P,U],1],
      ['mciSendStringA',[P,P,U,P],1],['mciSendStringW',[P,P,U,P],1],
      ['mciSendCommandA',[U,U,U,P],1],['mciSendCommandW',[U,U,U,P],1],
      ['midiOutShortMsg',[P,U],8],['midiOutLongMsg',[P,P,U],8],['midiStreamOut',[P,P,U],8]])deny(m,api,args,result);
  }
  if(name==='ole32.dll'||name==='combase.dll') {
    deny(m,'CoCreateInstance',[P,P,U,P,P],-2147024891);
    deny(m,'CoCreateInstanceEx',[P,P,U,P,U,P],-2147024891);
    deny(m,'CoGetClassObject',[P,U,P,P,P],-2147024891);
    deny(m,'CoGetObject',[P,P,P,P],-2147024891);
  }
  if(name==='user32.dll')deny(m,'MessageBeep',[U],1);
  if(name==='kernel32.dll'||name==='kernelbase.dll') {
    deny(m,'Beep',[U,U],1);
    for(const api of ['CreateProcessA','CreateProcessW'])deny(m,api,Array(10).fill(P),0);
  }
  if(/^(xaudio|xact|openal|ksuser|mfplat|mfplay|audioses)/.test(name))throw new Error('Unexpected output module '+name);
  Interceptor.flush();
}
const observer=Process.attachModuleObserver({onAdded(m){try{install(m);}catch(e){fatal(e);}}});
Interceptor.flush();audit.ready=!audit.failed;
rpc.exports={audiostatus(){return audit;}};
send({type:'audio-ready',...audit});
