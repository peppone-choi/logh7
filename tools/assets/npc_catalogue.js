/* Offline NPC worksheet. Author: Peppone Choi. No network or server writes. */
'use strict';
(() => {
  const abilities = ['leadership', 'politics', 'administration', 'intelligence', 'command', 'mobility', 'attack', 'defense'];
  const labels = ['통솔', '정치', '운영', '정보', '지휘', '기동', '공격', '방어'];
  const factions = {empire: '제국', alliance: '동맹', other: '기타'};
  const blankNameParts = () => ({surname: null, given_name: null, prefix: '', order: 'unknown', status: 'pending', source: '', note: ''});
  function migrateNameParts(doc) {
    const result = structuredClone(doc);
    if (!Array.isArray(result?.characters)) return result;
    for (const c of result.characters) {
      const n = c?.name_parts;
      if (!n || typeof n !== 'object' || Array.isArray(n) || Object.hasOwn(n, 'prefix')) continue;
      n.prefix = '';
      if (typeof n.surname === 'string' && n.surname.startsWith('폰 ') && n.surname.slice(2).trim()) {
        n.prefix = '폰'; n.surname = n.surname.slice(2);
        if (n.note === '폰은 성 쪽에 보존한 편집 규칙. 원작 wire 입력 표기는 미확인.') n.note = '폰은 표시용 접두사로 별도 보존하며 시스템 성·이름에서 제외한다.';
      }
    }
    return result;
  }
  function nameText(n) {
    const family = [n.prefix ?? '', n.surname];
    const parts = n.order === 'family_first' ? [...family, n.given_name] : n.order === 'given_first' ? [n.given_name, ...family] : [];
    return parts.filter(Boolean).join(' ');
  }
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
      if (Object.hasOwn(c, 'name_parts')) {
        const n = c.name_parts;
        check(n && ['unknown', 'given_first', 'family_first'].includes(n.order) && ['pending', 'reviewed'].includes(n.status), '성·이름의 순서와 검토 상태를 확인하세요.');
        check(!Object.hasOwn(n, 'prefix') || typeof n.prefix === 'string' && n.prefix.length <= 40 && !/[\u0000\ud800-\udfff]/.test(n.prefix), '표시용 접두사는 40자 이내 BMP 문자여야 합니다.');
        check(!Object.hasOwn(n, 'aliases') || Array.isArray(n.aliases) && n.aliases.length <= 20 && n.aliases.every(a => typeof a === 'string' && a.trim() && a.length <= 200 && !/[\u0000\ud800-\udfff]/.test(a)), '옛 이름·다른 표기는 200자 이내 문자 목록이어야 합니다.');
        check(['surname', 'given_name'].every(k => n[k] === null || typeof n[k] === 'string' && n[k].trim().length && n[k].length <= 12 && !/[\u0000\ud800-\udfff]/.test(n[k])), '성·이름은 빈 값 또는 각 12자 이내 BMP 문자여야 합니다.');
        check(!Object.hasOwn(n, 'prefix') || !(n.surname ?? '').startsWith('폰 '), '폰은 시스템 성에서 빼고 표시용 접두사에 입력하세요.');
        check(['source', 'note'].every(k => typeof n[k] === 'string' && n[k].length <= 2000), '성·이름 분리 출처를 확인하세요.');
        check(n.status !== 'reviewed' || n.surname && n.given_name && n.order !== 'unknown' && n.source.trim(), '분리 검토 완료에는 성·이름·표시 순서·출처가 필요합니다.');
      }
      if (Object.hasOwn(c, 'name_translation')) {
        const t = c.name_translation;
        check(t && ['adopted', 'pending'].includes(t.status) && ['japanese', 'korean', 'source', 'note'].every(k => typeof t[k] === 'string' && t[k].length <= 4000), '한글 이름의 표기 출처를 확인하세요.');
      }
      if (Object.hasOwn(c, 'wiki')) {
        const w = c.wiki;
        check(w && ['page', 'mirror', 'section'].every(k => typeof w[k] === 'string' && w[k].length <= 2000) && w.page.startsWith('https://namu.wiki/w/') && w.mirror.startsWith('https://namu.moe/w/'), '위키 출처 주소를 확인하세요.');
        check(!Object.hasOwn(w, 'reference_name') || typeof w.reference_name === 'string' && w.reference_name.length <= 200, '참고 인물 이름을 확인하세요.');
      }
      check(Array.isArray(c.posts) && c.posts.length <= 100, '직책 출처를 확인하세요.');
      for (const p of c.posts) check(p && ['post', 'unit', 'department', 'source'].every(k => typeof p[k] === 'string' && p[k].length <= 1000), '직책 출처를 확인하세요.');
      check(c.face_id === null || Number.isInteger(c.face_id) && faces.has(c.face_id), '확인된 얼굴 ID가 아닙니다.');
      if (c.face_id !== null) {
        check(faces.get(c.face_id).faction === c.faction, '얼굴과 인물의 진영이 다릅니다.');
        check(!assigned.has(c.face_id), '같은 얼굴을 여러 인물에 배정할 수 없습니다.');
        assigned.add(c.face_id);
      }
      if (Object.hasOwn(c, 'face_comparison')) {
        const m = c.face_comparison;
        check(m && Number.isInteger(m.face_id) && m.face_id === c.face_id && m.edition === 'OVA' && m.confidence === 'high', '얼굴이 바뀌었거나 OVA 대조 형식이 잘못됐습니다.');
        check(['source', 'image_url', 'image_label', 'edition_caption', 'features'].every(k => typeof m[k] === 'string' && m[k].length <= 2000) && m.source.startsWith('https://namu.wiki/w/') && m.image_url.startsWith('https://i.namu.wiki/i/') && m.edition_caption === 'OVA' && m.features.trim(), 'OVA 대조 이미지와 판본·특징 근거를 확인하세요.');
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
  if (typeof document === 'undefined') { module.exports = {validate, migrateNameParts, nameText}; return; }
  const {portraits, catalogue: initial, references = {}} = JSON.parse(document.getElementById('payload').textContent);
  let catalogue = validate(migrateNameParts(initial), portraits), selected = null, currentFace = null;
  let dirty = false, formDirty = false;
  const $ = id => document.getElementById(id);
  const text = (tag, value, cls) => { const el = document.createElement(tag); el.textContent = value; if (cls) el.className = cls; return el; };
  const picture = p => { const img = document.createElement('img'); img.src = p.image; img.alt = `얼굴 ${p.face_id ?? p.key}`; img.loading = 'lazy'; return img; };
  const face = id => portraits.find(p => p.face_id === id && id !== null);
  const person = () => catalogue.characters.find(c => c.key === selected);
  const referenceFor = c => c.wiki ? references[c.wiki.reference_name ?? c.name] : undefined;
  function message(value = '') { $('error').textContent = value; }
  function status() {
    const mapped = catalogue.characters.filter(c => c.face_id !== null).length;
    const withReference = catalogue.characters.filter(c => referenceFor(c)?.images.length).length;
    const separated = catalogue.characters.filter(c => c.name_parts?.status === 'reviewed').length;
    $('status').textContent = `${catalogue.characters.length}명 · 참고 이미지 ${withReference}명 · 얼굴 연결 ${mapped}명 · 성명 분리 ${separated}명 · ${dirty || formDirty ? '저장하지 않은 변경 있음' : '불러온 상태'}`;
  }
  abilities.forEach((key, i) => {
    const box = document.createElement('div'), label = text('label', labels[i]); label.htmlFor = key;
    const input = document.createElement('input'); Object.assign(input, {id: key, type: 'number', min: '0', max: '65535', step: '1', placeholder: '미확인'});
    box.append(label, input); $('stats').append(box);
  });
  function list() {
    const query = $('search').value.toLocaleLowerCase(), faction = $('faction-filter').value, origin = $('roster-filter').value, ref = $('reference-filter').value, match = $('match-filter').value;
    $('people').replaceChildren();
    const rows = catalogue.characters.filter(c => (!faction || faction === c.faction) && (!origin || (origin === 'manual' ? !!c.manual_holder : !c.manual_holder)) &&
      (!ref || (ref === 'yes' ? !!referenceFor(c)?.images.length : !referenceFor(c)?.images.length)) &&
      (!match || (match === 'ova' ? !!c.face_comparison : c.face_id === null)) &&
      `${c.name} ${c.manual_holder} ${c.name_parts?.surname ?? ''} ${c.name_parts?.given_name ?? ''} ${(c.name_parts?.aliases ?? []).join(' ')} ${c.posts.map(p => p.post + p.unit).join(' ')}`.toLocaleLowerCase().includes(query));
    $('count').textContent = `${rows.length}명`;
    for (const c of rows) {
      const button = text('button', '', `person${selected === c.key ? ' active' : ''}`), p = face(c.face_id);
      button.dataset.key = c.key;
      const reference = referenceFor(c)?.images[0];
      if (p) button.append(picture(p));
      else if (reference) { const img = document.createElement('img'); img.src = reference.path; img.alt = `${c.name} 위키 참고`; img.loading = 'lazy'; button.append(img); }
      else button.append(text('span', '?', 'emptyface'));
      const body = document.createElement('span'); body.append(text('span', c.name || '(이름 없음)'), text('small', `${factions[c.faction]} · ${c.manual_holder ? '초기 담당자' : '추가 후보'} · ${c.face_id === null ? '얼굴 미지정' : 'ID ' + c.face_id}${c.face_comparison ? ' · OVA 대조' : ''}${c.name_translation?.status === 'pending' ? ' · 표기 확인 대기' : ''}`));
      button.append(body); button.onclick = () => choose(c.key); $('people').append(button);
    }
    status();
  }
  function preview() {
    const p = face(currentFace);
    $('portrait-preview').style.display = p ? 'block' : 'none';
    if (p) $('portrait-preview').src = p.image;
    $('face-label').textContent = p ? `얼굴 ID ${p.face_id} / ${p.group}:${p.index}` : '얼굴 미지정';
    const comparison = person()?.face_comparison;
    $('face-review').textContent = comparison?.face_id === currentFace ? `OVA 육안 대조 · 높은 확신\n${comparison.features}` : '';
  }
  function referenceImages(c) {
    $('references').replaceChildren(); $('reference-large').hidden = true;
    $('reference-large').removeAttribute('src'); $('reference-label').textContent = '';
    $('wiki-source').hidden = !c.wiki;
    if (c.wiki) $('wiki-source').href = c.wiki.page;
    const reference = referenceFor(c), images = reference?.images ?? [];
    const preferred = images.find(i => i.image_url === c.face_comparison?.image_url) ?? images[0];
    $('reference-note').textContent = images.length ? '다른 인물·작품·시기의 이미지가 섞일 수 있습니다. 게임 얼굴은 육안으로 선택하세요.' : reference?.status === 'unavailable' ? '문서 수집 실패 · 출처 문서에서 직접 확인하세요. 이름은 계속 관리할 수 있습니다.' : '로컬 참고 이미지 없음 · 출처 문서에서 확인하거나 이미지 없이 이름만 관리할 수 있습니다.';
    function select(item, button) {
      $('reference-large').src = item.path; $('reference-large').hidden = false;
      $('reference-label').textContent = `${item.image_url === c.face_comparison?.image_url ? 'OVA 확인 · 대조에 사용한 그림 / ' : '판본 미분류 / '}${item.label}`;
      for (const b of $('references').children) b.classList.toggle('active', b === button);
    }
    for (const item of images) {
      const button = text('button', '', 'reference'); button.type = 'button';
      const img = document.createElement('img'); img.src = item.path; img.alt = item.label; img.loading = 'lazy';
      button.append(img); button.title = `${item.label}\n${reference.page}\n${item.image_url}`;
      button.onclick = () => select(item, button); $('references').append(button);
      if (preferred === item) select(item, button);
    }
  }
  function namePartsPreview() {
    const surname = $('surname').value, given = $('given-name').value, order = $('name-order').value;
    const combined = nameText({surname, given_name: given, prefix: $('name-prefix').value, order});
    $('name-parts-note').textContent = `성 ${surname.length}/12 · 이름 ${given.length}/12 · ${order !== 'unknown' ? '표시명 조합: ' + combined : '표시 순서 미확인'}${person()?.name_parts?.note ? ' · ' + person().name_parts.note : ''}`;
    $('name-aliases').textContent = person()?.name_parts?.aliases?.length ? '옛 이름·다른 표기: ' + person().name_parts.aliases.join(' / ') : '';
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
    const n = c.name_parts ?? blankNameParts();
    $('surname').value = n.surname ?? ''; $('given-name').value = n.given_name ?? '';
    $('name-prefix').value = n.prefix ?? '';
    $('name-order').value = n.order; $('name-parts-status').value = n.status; $('name-parts-source').value = n.source;
    namePartsPreview();
    for (const k of abilities) $(k).value = c.abilities[k] ?? '';
    for (const k of ['face', 'abilities']) { $(k + '-status').value = c[k + '_evidence'].status; $(k + '-source').value = c[k + '_evidence'].source; }
    $('holder').textContent = c.manual_holder ? `매뉴얼: ${c.manual_holder}` : c.wiki ? '추가 인물 후보 · VII 등장·초기 배치 미확인' : '직접 추가한 인물';
    referenceImages(c);
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
    next.name_parts = {...(next.name_parts ?? blankNameParts()), surname: $('surname').value || null, given_name: $('given-name').value || null, prefix: $('name-prefix').value,
      order: $('name-order').value, status: $('name-parts-status').value, source: $('name-parts-source').value};
    next.face_id = currentFace;
    if (next.face_comparison?.face_id !== currentFace) delete next.face_comparison;
    for (const k of abilities) next.abilities[k] = $(k).value === '' ? null : Number($(k).value);
    for (const k of ['face', 'abilities']) next[k + '_evidence'] = {status: $(k + '-status').value, source: $(k + '-source').value};
    const revised = {...catalogue, characters: catalogue.characters.map(c => c.key === selected ? next : c)};
    try { validate(revised, portraits); catalogue = revised; dirty = true; formDirty = false; message(); list(); gallery(); return true; }
    catch (error) { message(error.message); return false; }
  }
  function choose(key) { if (!apply()) return; selected = key; message(); show(); }
  $('editor').oninput = () => { formDirty = true; namePartsPreview(); status(); };
  $('name-from-parts').onclick = () => {
    const order = $('name-order').value;
    if (order === 'unknown') { message('이름과 성의 표시 순서를 먼저 선택하세요.'); return; }
    $('name').value = nameText({surname: $('surname').value, given_name: $('given-name').value, prefix: $('name-prefix').value, order}); formDirty = true; namePartsPreview(); status();
  };
  $('editor').onsubmit = event => { event.preventDefault(); if (apply()) show(); };
  $('faction').onchange = () => {
    if (face(currentFace)?.faction !== $('faction').value) { currentFace = null; $('face-status').value = 'unknown'; $('face-source').value = ''; }
    // A draft faction is reflected in the gallery before applying the form.
    formDirty = true; preview();
    gallery(); status();
  };
  $('clear-face').onclick = () => { currentFace = null; $('face-status').value = 'unknown'; $('face-source').value = ''; formDirty = true; preview(); gallery(); status(); };
  $('search').oninput = list; $('faction-filter').onchange = list; $('roster-filter').onchange = list; $('reference-filter').onchange = list; $('match-filter').onchange = list; $('face-filter').onchange = gallery;
  $('add').onclick = () => {
    if (!apply()) return;
    let i = 1; while (catalogue.characters.some(c => c.key === `custom-${i}`)) i++;
    selected = `custom-${i}`;
    catalogue.characters.push({key: selected, faction: $('faction-filter').value || 'empire', name: '새 인물', manual_holder: '', posts: [], face_id: null,
      face_evidence: {status: 'unknown', source: ''}, abilities: Object.fromEntries(abilities.map(k => [k, null])), abilities_evidence: {status: 'unknown', source: ''}, rank: '', location: '', ai_profile: '', notes: ''});
    dirty = true; $('search').value = ''; $('roster-filter').value = ''; $('reference-filter').value = ''; $('match-filter').value = ''; show();
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
      const incoming = validate(migrateNameParts(JSON.parse((await file.text()).replace(/^\uFEFF/, ''))), portraits);
      if ((dirty || formDirty) && !window.confirm('저장하지 않은 변경을 버리고 JSON을 불러올까요?')) return;
      catalogue = incoming; selected = catalogue.characters[0]?.key ?? null; dirty = false; formDirty = false; message(); show();
    } catch (error) { message(error.message); }
  };
  window.addEventListener('beforeunload', event => { if (dirty || formDirty) { event.preventDefault(); event.returnValue = ''; } });
  selected = catalogue.characters.find(c => c.face_comparison)?.key ?? catalogue.characters[0]?.key ?? null; show();
})();
