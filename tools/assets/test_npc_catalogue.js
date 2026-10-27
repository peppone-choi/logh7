/* Import validation protects reviewed assignments and unknown values. Author: 최병호. */
'use strict';
const assert = require('node:assert/strict');
const {validate} = require('./npc_catalogue.js');
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
console.log('NPC JSON validation passed: null/zero, provenance, ranges, duplicate faces, factions.');
