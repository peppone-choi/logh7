// Offline translation editing; source bindings and metadata stay intact. Author: 최병호.
const LoghTranslationEditor = (() => {
    const printf = /%(?:[-+ #0]*)(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|ll|[hlLwIz])?[diuoxXfFeEgGaAcCsSpn%]/g;
    function validate(before, after) {
        if (typeof after !== 'string' || after.includes('\0') || (!after && before)) throw Error('빈 문구 또는 NUL 문자는 저장할 수 없습니다.');
        const tokens = text => JSON.stringify((text.match(/\$[^$]*\$/g) || []).sort());
        if (tokens(before) !== tokens(after) || before.split('$').length !== after.split('$').length) throw Error('$변수$의 이름과 개수를 유지하세요.');
        if (JSON.stringify(before.match(printf) || []) !== JSON.stringify(after.match(printf) || [])) throw Error('%s, %d 등의 서식과 순서를 유지하세요.');
        if (/[\u3040-\u30ff\u3400-\u9fff\uff66-\uff9f\ue000-\uf8ff]/u.test(after)) throw Error('일본어·한자 또는 내부 전용 글리프가 남아 있습니다.');
    }
    function entries(document) {
        if (document.schema !== 1) throw Error('지원하는 번역 카탈로그가 아닙니다.');
        const result = [];
        function add(path, value) {
            if (typeof value === 'string') result.push({id: path.join('/'), path, value, draft: value});
        }
        for (const [group, values] of Object.entries(document.groups || {})) {
            if (!Array.isArray(values)) throw Error('그룹 형식이 올바르지 않습니다.');
            values.forEach((value, index) => add(['groups', group, index], value));
        }
        for (const [key, value] of Object.entries(document.entries || {})) add(['entries', key], value);
        for (const [table, data] of Object.entries(document.files || {})) {
            for (const [key, value] of Object.entries(data.entries || {})) add(['files', table, 'entries', key], value);
        }
        if (!result.length) throw Error('메시지·상수·EXE·Windows UI 번역 JSON을 선택하세요. NPC·이미지 자료는 별도입니다.');
        return result;
    }
    function serialize(document, rows) {
        const updated = JSON.parse(JSON.stringify(document));
        for (const row of rows) {
            validate(row.value, row.draft);
            let target = updated;
            for (const part of row.path.slice(0, -1)) target = target[part];
            target[row.path[row.path.length - 1]] = row.draft;
        }
        return JSON.stringify(updated, null, 2) + '\n';
    }
    return {entries, validate, serialize};
})();
if (typeof module !== 'undefined') module.exports = LoghTranslationEditor;
