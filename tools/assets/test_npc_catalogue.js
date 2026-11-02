/* Import validation protects reviewed assignments and unknown values. Author: Peppone Choi. */
'use strict';
const assert = require('node:assert/strict');
const {validate, migrateNameParts, nameText} = require('./npc_catalogue.js');
const keys = ['leadership', 'politics', 'administration', 'intelligence', 'command', 'mobility', 'attack', 'defense'];
const row = {key: 'one', faction: 'empire', name: 'Test', manual_holder: '', posts: [], face_id: null,
  face_evidence: {status: 'unknown', source: ''}, abilities: Object.fromEntries(keys.map(k => [k, null])),
  abilities_evidence: {status: 'unknown', source: ''}, rank: '', location: '', ai_profile: '', notes: ''};
const faces = [{face_id: 1, faction: 'empire'}];
const doc = {schema: 1, characters: [row]};
validate(doc, faces);
row.abilities.attack = 0;
assert.throws(() => validate(doc, faces));
row.abilities_evidence = {status: 'guess', source: 'Local policy'};
validate(doc, faces);
for (const value of [-1, 65536, true, 1.5, '80']) {
  const bad = structuredClone(doc); bad.characters[0].abilities.attack = value;
  assert.throws(() => validate(bad, faces));
}
row.face_id = 1; row.face_evidence = {status: 'client', source: 'Observed'};
validate(doc, faces);
const duplicate = {...structuredClone(row), key: 'two'};
assert.throws(() => validate({schema: 1, characters: [row, duplicate]}, faces));
row.faction = 'alliance';
assert.throws(() => validate(doc, faces));
row.faction = 'empire';
row.face_comparison = {face_id: 1, edition: 'OVA', confidence: 'high', edition_caption: 'OVA', source: 'https://namu.wiki/w/Test', image_url: 'https://i.namu.wiki/i/test.webp', image_label: 'Test', features: 'Distinctive visual features'};
validate(doc, faces);
for (const [field, value] of [['face_id', 2], ['edition', 'DNT'], ['edition_caption', 'DNT'], ['image_url', 'javascript:alert(1)']]) {
  const bad = structuredClone(doc); bad.characters[0].face_comparison[field] = value;
  assert.throws(() => validate(bad, faces));
}
row.name_parts = {surname: 'A'.repeat(12), given_name: null, order: 'unknown', status: 'pending', source: '', note: ''};
validate(doc, faces);
for (const value of ['A'.repeat(13), '\u0000', '\u{1f600}', '   ', '']) {
  const bad = structuredClone(doc); bad.characters[0].name_parts.surname = value;
  assert.throws(() => validate(bad, faces));
}
row.name_parts.status = 'reviewed';
assert.throws(() => validate(doc, faces));
row.name_parts = {surname: '폰 로엔그람', given_name: '라인하르트', order: 'given_first', status: 'reviewed', source: '검토', note: '직접 남긴 메모'};
const migrated = migrateNameParts(doc), n = migrated.characters[0].name_parts;
assert.equal(n.surname, '로엔그람'); assert.equal(n.prefix, '폰'); assert.equal(n.note, '직접 남긴 메모');
assert.equal(nameText(n), '라인하르트 폰 로엔그람');
assert.equal(nameText({surname:'양', given_name:'웬리', prefix:'', order:'family_first'}), '양 웬리');
assert.equal(row.name_parts.surname, '폰 로엔그람'); assert.equal(Object.hasOwn(row.name_parts,'prefix'),false);
assert.deepEqual(migrateNameParts(migrated),migrated); validate(migrated,faces);
n.aliases = ['라인하르트 폰 뮈젤', '라인하르트 폰 뮤젤']; validate(migrated, faces);
assert.deepEqual(migrateNameParts(migrated).characters[0].name_parts.aliases, n.aliases);
for (const invalid of ['not a list', ['\u0000'], ['A'.repeat(201)], ['\u{1f600}'], [' '], Array(21).fill('A')]) {
  const bad = structuredClone(migrated); bad.characters[0].name_parts.aliases = invalid;
  assert.throws(() => validate(bad, faces));
}
n.surname = '폰 로엔그람'; assert.throws(()=>validate(migrated,faces));
console.log('NPC JSON validation passed: null/zero, provenance, ranges, duplicate faces, factions.');
