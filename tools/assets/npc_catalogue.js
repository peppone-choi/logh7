/* Offline NPC worksheet. Author: 최병호. No network or server writes. */
'use strict';
(() => {
  const abilities = ['leadership', 'politics', 'administration', 'intelligence', 'command', 'mobility', 'attack', 'defense'];
  const labels = ['통솔', '정치', '운영', '정보', '지휘', '기동', '공격', '방어'];
  const factions = {empire: '제국', alliance: '동맹', other: '기타'};
  function validate(doc, portraits) {
    const check = (ok, message) => { if (!ok) throw Error(message); };
    check(doc && doc.schema === 1 && Array.isArray(doc.characters) && doc.characters.length <= 2000, 'schema=1의 인물 목록이 필요합니다.');
    const keys = new Set(), assigned = new Set();
    const faces = new Map(portraits.filter(p => p.face_id !== null).map(p => [p.face_id, p]));
    for (const c of doc.characters) {
      check(c && typeof c.key === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(c.key) && !keys.has(c.key), '인물 식별자가 중복되거나 잘못됐습니다.');
      keys.add(c.key);
      check(Object.hasOwn(factions, c.faction), '진영이 잘못됐습니다.');
      for (const k of ['name', 'manual_holder', 'rank', 'location', 'ai_profile', 'notes']) check(typeof c[k] === 'string' && c[k].length <= 4000, `${k} 필드를 확인하세요.`);
      if (Object.hasOwn(c, 'name_translation')) {
        const t = c.name_translation;
        check(t && ['adopted', 'pending'].includes(t.status) && ['japanese', 'korean', 'source', 'note'].every(k => typeof t[k] === 'string' && t[k].length <= 4000), '한글 이름의 표기 출처를 확인하세요.');
      }
      check(Array.isArray(c.posts) && c.posts.length <= 100, '직책 출처를 확인하세요.');
      for (const p of c.posts) check(p && ['post', 'unit', 'department', 'source'].every(k => typeof p[k] === 'string' && p[k].length <= 1000), '직책 출처를 확인하세요.');
      check(c.face_id === null || Number.isInteger(c.face_id) && faces.has(c.face_id), '확인된 얼굴 ID가 아닙니다.');
      if (c.face_id !== null) {
        check(faces.get(c.face_id).faction === c.faction, '얼굴과 인물의 진영이 다릅니다.');
        check(!assigned.has(c.face_id), '같은 얼굴을 여러 인물에 배정할 수 없습니다.');
        assigned.add(c.face_id);
      }
      check(c.abilities && Object.keys(c.abilities).sort().join() === [...abilities].sort().join(), '능력 8종이 필요합니다.');
      check(abilities.every(k => c.abilities[k] === null || Number.isInteger(c.abilities[k]) && c.abilities[k] >= 0 && c.abilities[k] <= 65535), '능력은 빈 값 또는 0..65535 정수여야 합니다.');
      for (const [key, populated] of [['face_evidence', c.face_id !== null], ['abilities_evidence', abilities.some(k => c.abilities[k] !== null)]]) {
        const e = c[key];
        check(e && ['unknown', 'guess', 'manual', 'client'].includes(e.status) && typeof e.source === 'string' && e.source.length <= 4000, '근거 형식을 확인하세요.');
        check(!populated || e.status !== 'unknown' && e.source.trim(), '지정한 얼굴·능력치에는 근거 분류와 출처가 필요합니다.');
      }
    }
    return doc;
  }
  if (typeof document === 'undefined') { module.exports = {validate}; return; }
  const {portraits, catalogue: initial} = JSON.parse(document.getElementById('payload').textContent);
  let catalogue = validate(initial, portraits), selected = null, currentFace = null;
  let dirty = false, formDirty = false;
  const $ = id => document.getElementById(id);
  const text = (tag, value, cls) => { const el = document.createElement(tag); el.textContent = value; if (cls) el.className = cls; return el; };
  const picture = p => { const img = document.createElement('img'); img.src = p.image; img.alt = `얼굴 ${p.face_id ?? p.key}`; img.loading = 'lazy'; return img; };
  const face = id => portraits.find(p => p.face_id === id && id !== null);
  const person = () => catalogue.characters.find(c => c.key === selected);
  function message(value = '') { $('error').textContent = value; }
  function status() {
    const mapped = catalogue.characters.filter(c => c.face_id !== null).length;
    $('status').textContent = `${catalogue.characters.length}명 · 얼굴 연결 ${mapped}명 · ${dirty || formDirty ? '저장하지 않은 변경 있음' : '불러온 상태'}`;
  }
  abilities.forEach((key, i) => {
    const box = document.createElement('div'), label = text('label', labels[i]); label.htmlFor = key;
    const input = document.createElement('input'); Object.assign(input, {id: key, type: 'number', min: '0', max: '65535', step: '1', placeholder: '미확인'});
    box.append(label, input); $('stats').append(box);
  });
  function list() {
    const query = $('search').value.toLocaleLowerCase(), faction = $('faction-filter').value;
    $('people').replaceChildren();
    const rows = catalogue.characters.filter(c => (!faction || faction === c.faction) &&
      `${c.name} ${c.manual_holder} ${c.posts.map(p => p.post + p.unit).join(' ')}`.toLocaleLowerCase().includes(query));
    $('count').textContent = `${rows.length}명`;
    for (const c of rows) {
      const button = text('button', '', `person${selected === c.key ? ' active' : ''}`), p = face(c.face_id);
      button.dataset.key = c.key;
      button.append(p ? picture(p) : text('span', '?', 'emptyface'));
      const body = document.createElement('span'); body.append(text('span', c.name || '(이름 없음)'), text('small', `${factions[c.faction]} · ${c.face_id === null ? '얼굴 미지정' : 'ID ' + c.face_id}${c.name_translation?.status === 'pending' ? ' · 표기 확인 대기' : ''}`));
      button.append(body); button.onclick = () => choose(c.key); $('people').append(button);
    }
    status();
  }
  function preview() {
    const p = face(currentFace);
    $('portrait-preview').style.display = p ? 'block' : 'none';
    if (p) $('portrait-preview').src = p.image;
    $('face-label').textContent = p ? `얼굴 ID ${p.face_id} / ${p.group}:${p.index}` : '얼굴 미지정';
  }
  function gallery() {
    const filter = $('face-filter').value;
    const faction = filter === 'current' && selected ? $('faction').value : filter === 'current' ? null : filter;
    $('gallery').replaceChildren();
    for (const p of portraits.filter(p => !faction || faction === 'all' || faction === p.faction)) {
      const owner = catalogue.characters.find(c => c.face_id !== null && c.face_id === p.face_id && c.key !== selected);
      const button = text('button', '', `portrait${p.face_id !== null && currentFace === p.face_id ? ' selected' : ''}${owner ? ' used' : ''}`);
      button.type = 'button'; button.dataset.face = String(p.face_id);
      button.append(picture(p), text('span', p.face_id === null ? `${p.key} / 공용` : String(p.face_id)));
      button.title = owner ? `배정됨: ${owner.name}` : `${p.group}.tcf / 슬롯 ${p.index}${p.face_id === null ? ' · 고유 얼굴 ID 미지정' : ''}`;
      button.disabled = !selected || p.face_id === null || !!owner || p.faction !== $('faction').value;
      button.onclick = () => {
        currentFace = p.face_id; $('face-status').value = 'guess'; $('face-source').value = '얼굴 육안 대조 — 확인 필요';
        formDirty = true; preview(); gallery(); status();
      };
      $('gallery').append(button);
    }
  }
  function show() {
    const c = person(); $('editor').hidden = !c;
    if (!c) { currentFace = null; gallery(); list(); return; }
    currentFace = c.face_id;
    for (const k of ['name', 'faction', 'rank', 'location', 'ai_profile', 'notes']) $(k).value = c[k];
    for (const k of abilities) $(k).value = c.abilities[k] ?? '';
    for (const k of ['face', 'abilities']) { $(k + '-status').value = c[k + '_evidence'].status; $(k + '-source').value = c[k + '_evidence'].source; }
    $('holder').textContent = c.manual_holder ? `매뉴얼: ${c.manual_holder}` : '직접 추가한 인물';
    const translation = c.name_translation;
    $('name-note').textContent = translation ? `초기 한글 표기: ${translation.korean} · ${translation.status === 'pending' ? '확인 대기 / ' + translation.note : '용어집·나무위키 기준'} · 출처: ${translation.source}` : '';
    $('posts').textContent = c.posts.map(p => `${p.department} / ${p.unit} ${p.post} · ${p.source}`).join('\n');
    formDirty = false; preview(); list(); gallery();
  }
  function apply() {
    if (!selected || !formDirty) return true;
    if (!$('editor').reportValidity()) return false;
    const next = structuredClone(person());
    for (const k of ['name', 'faction', 'rank', 'location', 'ai_profile', 'notes']) next[k] = $(k).value;
    next.face_id = currentFace;
    for (const k of abilities) next.abilities[k] = $(k).value === '' ? null : Number($(k).value);
    for (const k of ['face', 'abilities']) next[k + '_evidence'] = {status: $(k + '-status').value, source: $(k + '-source').value};
    const revised = {...catalogue, characters: catalogue.characters.map(c => c.key === selected ? next : c)};
    try { validate(revised, portraits); catalogue = revised; dirty = true; formDirty = false; message(); list(); gallery(); return true; }
    catch (error) { message(error.message); return false; }
  }
  function choose(key) { if (!apply()) return; selected = key; message(); show(); }
  $('editor').oninput = () => { formDirty = true; status(); };
  $('editor').onsubmit = event => { event.preventDefault(); if (apply()) show(); };
  $('faction').onchange = () => {
    if (face(currentFace)?.faction !== $('faction').value) { currentFace = null; $('face-status').value = 'unknown'; $('face-source').value = ''; }
    // A draft faction is reflected in the gallery before applying the form.
    formDirty = true; preview();
    gallery(); status();
  };
  $('clear-face').onclick = () => { currentFace = null; $('face-status').value = 'unknown'; $('face-source').value = ''; formDirty = true; preview(); gallery(); status(); };
  $('search').oninput = list; $('faction-filter').onchange = list; $('face-filter').onchange = gallery;
  $('add').onclick = () => {
    if (!apply()) return;
    let i = 1; while (catalogue.characters.some(c => c.key === `custom-${i}`)) i++;
    selected = `custom-${i}`;
    catalogue.characters.push({key: selected, faction: $('faction-filter').value || 'empire', name: '새 인물', manual_holder: '', posts: [], face_id: null,
      face_evidence: {status: 'unknown', source: ''}, abilities: Object.fromEntries(abilities.map(k => [k, null])), abilities_evidence: {status: 'unknown', source: ''}, rank: '', location: '', ai_profile: '', notes: ''});
    dirty = true; $('search').value = ''; show();
  };
  $('delete').onclick = () => {
    if (!person() || !window.confirm(`${person().name} 인물을 이 편집 초안에서 삭제할까요?`)) return;
    catalogue.characters = catalogue.characters.filter(c => c.key !== selected); selected = catalogue.characters[0]?.key ?? null; dirty = true; formDirty = false; show();
  };
  $('export').onclick = () => {
    if (!apply()) return;
    try {
      validate(catalogue, portraits);
      const url = URL.createObjectURL(new Blob([JSON.stringify(catalogue, null, 2) + '\n'], {type: 'application/json'}));
      const a = document.createElement('a'); a.href = url; a.download = 'npc-catalogue.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      // Browsers do not expose download completion; retain the unsaved indicator.
      message(); $('status').textContent = 'JSON 다운로드 요청됨 · 파일이 저장됐는지 확인하세요.';
    } catch (error) { message(error.message); }
  };
  $('import').onclick = () => $('file').click();
  $('file').onchange = async () => {
    const file = $('file').files[0]; $('file').value = ''; if (!file) return;
    try {
      if (file.size > 8 * 1024 * 1024) throw Error('JSON 파일은 8 MiB 이하여야 합니다.');
      const incoming = validate(JSON.parse((await file.text()).replace(/^\uFEFF/, '')), portraits);
      if ((dirty || formDirty) && !window.confirm('저장하지 않은 변경을 버리고 JSON을 불러올까요?')) return;
      catalogue = incoming; selected = catalogue.characters[0]?.key ?? null; dirty = false; formDirty = false; message(); show();
    } catch (error) { message(error.message); }
  };
  window.addEventListener('beforeunload', event => { if (dirty || formDirty) { event.preventDefault(); event.returnValue = ''; } });
  selected = catalogue.characters[0]?.key ?? null; show();
})();
