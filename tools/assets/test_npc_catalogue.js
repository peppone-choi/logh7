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
console.log('NPC JSON validation passed: null/zero, provenance, ranges, duplicate faces, factions.');
