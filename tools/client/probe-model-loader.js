// Author: 최병호. Appended to capture-frame.js in the owned CD client only.
// Original loader calls stay on its render thread; no serialized heap pointers are dumped.
if (Process.arch !== 'ia32' || Process.mainModule.name.toLowerCase() !== 'g7mtclient.exe'
    || !Process.mainModule.base.equals(ptr('0x00400000')))
  throw new Error('Unexpected CD client profile');
const loaderObject = Memory.alloc(16);
loaderObject.writeByteArray(new Uint8Array(16));
const loadModel = new NativeFunction(ptr('0x004d15f0'), 'int', ['pointer', 'pointer'], 'thiscall');
const freeModel = new NativeFunction(ptr('0x004d16e0'), 'void', ['pointer'], 'thiscall');
let probeIndex = 0, probeFailed = false;
function dumpModelRecords(modelName, records, count, group) {
  if (count > 100 || (count && records.isNull())) throw new Error('Unexpected record count');
  for (let i = 0; i < count; i++) {
    const record = records.add(i * 36);
    const vertices = record.readPointer(), nv = record.add(4).readU32();
    const indices = record.add(8).readPointer(), ni = record.add(12).readU32();
    const flags = record.add(20).readU32(), stride = record.add(28).readU32();
    if (nv < 3 || nv > 50000 || ni < 3 || ni > 200000 || ni % 3
        || stride < 12 || stride > 128 || vertices.isNull() || indices.isNull())
      throw new Error('Unexpected geometry bounds');
    const name = modelName + '-' + group + '-' + i;
    send({type: 'analysis-model', name: name + '-vertices', model: modelName,
          group, i, nv, ni, stride, flags}, vertices.readByteArray(nv * stride));
    send({type: 'analysis-model', name: name + '-indices'}, indices.readByteArray(ni * 2));
  }
}
const modelNormalCapture = capture8;
capture8 = function(device) {
  modelNormalCapture(device);
  if (!done || probeFailed || probeIndex >= probeModels.length) return;
  const modelName = probeModels[probeIndex++];
  const path = Memory.allocUtf8String('../data/model/Planets/' + modelName + '.mdx');
  try {
    const result = loadModel(loaderObject, path), model = loaderObject.readPointer();
    if (!result || model.isNull()) throw new Error('Original loader failed');
    const streams = model.add(48).readPointer(), count = model.add(52).readU32();
    if (streams.isNull() || count < 1 || count > 20) throw new Error('Unexpected mesh count');
    for (let mesh = 0; mesh < count; mesh++) {
      const stream = streams.add(mesh * 44);
      dumpModelRecords(modelName, stream.readPointer(), stream.add(4).readU32(), mesh + '-primary');
      for (const category of [1, 2]) {
        const lists = stream.add(category * 8).readPointer(), n = stream.add(category * 8 + 4).readU32();
        if (n > 100 || (n && lists.isNull())) throw new Error('Unexpected list count');
        for (let j = 0; j < n; j++) {
          const list = lists.add(j * 8);
          dumpModelRecords(modelName, list.readPointer(), list.add(4).readU32(), mesh + '-' + category + '-' + j);
        }
      }
    }
    freeModel(loaderObject);
    loaderObject.writeByteArray(new Uint8Array(16));
    send({type: 'analysis-model', name: modelName + '-loaded', result, count});
  } catch (error) {
    // Failed-load ownership is unknown: stop instead of freeing a partial object.
    probeFailed = true;
    send({type: 'analysis-model', name: modelName + '-error', message: String(error)});
  }
};
