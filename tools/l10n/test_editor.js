// Check editing against the real catalogues without touching game data. Author: 최병호.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const editor = require('./editor.js');
const root = path.resolve(__dirname, '../../l10n');

test('edit every supported catalogue shape without changing bindings or metadata', () => {
    for (const filename of ['constmsg.ko.json', 'messages.ko.json', 'exe-ui.ko.json', 'exe-wide-ui.ko.json', 'windows-ui.ko.json']) {
        const data = JSON.parse(fs.readFileSync(path.join(root, filename), 'utf8'));
        const rows = editor.entries(data);
        assert.ok(rows.length > 100);
        assert.deepEqual(JSON.parse(editor.serialize(data, rows)), data);
        const selected = rows.find(row => !row.value.includes('$') && !row.value.includes('%') && row.value);
        selected.draft = selected.value + ' 수정';
        const updated = JSON.parse(editor.serialize(data, rows));
        assert.equal(editor.entries(updated).find(row => row.id === selected.id).value, selected.draft);
        assert.equal(updated.source_sha256, data.source_sha256);
        assert.equal(editor.entries(data).find(row => row.id === selected.id).value, selected.value);
    }
});
test('reject variable loss, duplicate variables, printf changes and invalid text', () => {
    for (const [before, after] of [['$name$ %d', '$name$'], ['$a$$a$', '$a$'], ['%s %d', '%d %s'], ['본문', ''], ['본문', '문\0구'], ['본문', '日本語'], ['본문', '\ue000']]) {
        assert.throws(() => editor.validate(before, after));
    }
    assert.doesNotThrow(() => editor.validate('$a$ $b$ %02u', '$b$와 $a$ %02u'));
});
test('unsupported asset or roster documents cannot become translation files', () => {
    assert.throws(() => editor.entries({schema: 1, assets: []}));
    assert.throws(() => editor.entries({schema: 2, entries: {key: '문구'}}));
});
