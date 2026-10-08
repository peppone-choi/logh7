// Explicit opt-in compatibility patch for the owned, hash-checked CD client.
// Its lobby initializer hard-codes both creation buttons to disabled.
// Keep the original files intact; change only these two in-memory immediates.
for(const [rva,stackOffset] of [[0x11ab3a,0x15],[0x11ab3f,0x16]]) {
  const address=Process.mainModule.base.add(rva);
  const expected=[0xc6,0x44,0x24,stackOffset,0x00];
  if(!expected.every((byte,index)=>address.add(index).readU8()===byte))
    throw new Error('Unexpected CD client creation-menu instruction');
  Memory.patchCode(address.add(4),1,code=>code.writeU8(1));
}
send({type:'capture-status',event:'CD creation menus enabled in owned process memory'});
