// Browser file access is initiated only by the editor's buttons. Author: Peppone Choi (peppone-choi).
(() => {
    const $ = id => document.getElementById(id);
    const core = LoghTranslationEditor;
    let data, rows = [], selected, handle, baseline, filename;
    let busy = false;
    const dirty = () => rows.some(row => row.draft !== row.value);
    const message = text => { $('status').textContent = text; };
    function update() {
        const invalid = rows.some(row => {try {core.validate(row.value, row.draft); return false;} catch {return true;} });
        $('save').disabled = busy || !handle || !dirty() || invalid;
        $('download').disabled = busy || !data || invalid;
        $('open').disabled = busy;
        $('text').disabled = busy || !selected;
    }
    function select(row) {
        selected = row;
        $('key').textContent = row.id;
        $('text').value = row.draft;
        $('tokens').textContent = '유지할 변수·서식: ' + ((row.value.match(/\$[^$]*\$|%(?:[-+ #0]*)(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|ll|[hlLwIz])?[diuoxXfFeEgGaAcCsSpn%]/g) || []).join(' · ') || '없음');
        edit(); results();
    }
    function results() {
        const query = $('search').value.toLocaleLowerCase();
        const found = rows.filter(row => (row.id + '\n' + row.draft).toLocaleLowerCase().includes(query));
        $('count').textContent = `${found.length}개 일치 / 전체 ${rows.length}개` + (found.length > 200 ? ' · 처음 200개 표시, 검색어로 좁히세요.' : '');
        $('results').replaceChildren(...found.slice(0, 200).map(row => {
            const button = document.createElement('button');
            button.textContent = (row.draft !== row.value ? '● ' : '') + row.draft.replace(/\s+/g, ' ').slice(0, 80);
            const key = document.createElement('small'); key.textContent = row.id; button.append(key);
            button.setAttribute('aria-pressed', String(row === selected));
            button.onclick = () => select(row);
            return button;
        }));
    }
    function edit() {
        if (!selected) return;
        selected.draft = $('text').value;
        $('preview').textContent = selected.draft;
        try {core.validate(selected.value, selected.draft); $('error').textContent = '';}
        catch (error) {$('error').textContent = error.message;}
        update();
    }
    async function load(file, nextHandle) {
        if (dirty() && !confirm('저장하지 않은 수정이 있습니다. 다른 파일을 여시겠습니까?')) return;
        const text = await file.text();
        const parsed = JSON.parse(text.replace(/^\uFEFF/, ''));
        const parsedRows = core.entries(parsed);
        data = parsed; rows = parsedRows; handle = nextHandle; baseline = text; filename = file.name;
        selected = undefined; $('search').value = '';
        message(`${filename} · ${rows.length}개 문구` + (handle ? '' : ' · 복사본 다운로드로 저장하세요.'));
        select(rows[0]);
    }
    const report = error => {if (error.name !== 'AbortError') message(error.message);};
    $('open').onclick = async () => {
        try {
            if (!window.showOpenFilePicker) {$('file').click(); return;}
            const [nextHandle] = await window.showOpenFilePicker({types: [{description: '한국어 문구', accept: {'application/json': ['.json']}}]});
            await load(await nextHandle.getFile(), nextHandle);
        } catch (error) {report(error);}
    };
    $('file').onchange = async event => {
        try {if (event.target.files[0]) await load(event.target.files[0], undefined);} catch (error) {report(error);}
        event.target.value = '';
    };
    $('save').onclick = async () => {
        let stream;
        try {
            busy = true; update();
            const output = core.serialize(data, rows);
            stream = await handle.createWritable();
            if (await (await handle.getFile()).text() !== baseline) throw Error('다른 곳에서 파일이 바뀌었습니다. 복사본을 내려받아 변경을 보존하고 파일을 다시 여세요.');
            await stream.write(output); await stream.close(); stream = undefined;
            baseline = output; data = JSON.parse(output); rows.forEach(row => {row.value = row.draft;});
            message(`${filename} 저장 완료 · 게임 반영에는 빌드가 필요합니다.`); results();
        } catch (error) {if (stream) await stream.abort().catch(() => {}); report(error);}
        finally {busy = false; update();}
    };
    $('download').onclick = () => {
        try {
            const url = URL.createObjectURL(new Blob([core.serialize(data, rows)], {type: 'application/json;charset=utf-8'}));
            const link = document.createElement('a'); link.href = url; link.download = filename; link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            message('복사본 다운로드를 요청했습니다. 수정 중인 원본의 저장 상태는 유지됩니다.');
        } catch (error) {report(error);}
    };
    $('search').oninput = results;
    $('text').oninput = edit;
    $('text').onchange = results;
    window.addEventListener('beforeunload', event => {if (dirty()) {event.preventDefault(); event.returnValue = '';}});
})();
