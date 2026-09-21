'use strict';

const state = {
  project: null,
  selectedFlag: null,
  viewVersion: null,
  editing: false,
  draft: null,
  context: {},
  lastResults: null,
};

const $ = (id) => document.getElementById(id);

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: opts.body ? { 'Content-Type': 'application/json' } : {},
    ...opts,
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const msg = data && data.error ? data.error : ('HTTP ' + res.status);
    throw new Error(msg);
  }
  return data;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function displayValue(v) {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'boolean') return v ? 'on' : 'off';
  if (typeof v === 'string') return v;
  return JSON.stringify(v);
}

async function loadState() {
  state.project = await api('/api/state');
  if (!state.selectedFlag && location.hash) {
    const key = decodeURIComponent(location.hash.slice(1));
    if (state.project.flags[key]) {
      state.selectedFlag = key;
      state.viewVersion = state.project.flags[key].currentVersion;
    }
  }
  render();
}

function flags() { return Object.values(state.project.flags || {}); }
function contexts() { return Object.values(state.project.contexts || {}); }
function currentFlag() { return state.project.flags[state.selectedFlag]; }
function viewFV() {
  const f = currentFlag();
  const v = state.viewVersion || f.currentVersion;
  return f.versions[v];
}

/* ---------------- sidebar ---------------- */

function renderSidebar() {
  const fl = $('flagList');
  fl.innerHTML = '';
  flags().forEach((f) => {
    const div = document.createElement('div');
    div.className = 'list-item' + (f.key === state.selectedFlag ? ' active' : '');
    div.innerHTML = `<div class="row"><strong>${esc(f.name)}</strong><span>v${f.currentVersion}</span></div>
      <div class="k">${esc(f.key)}</div>`;
    div.onclick = () => { state.selectedFlag = f.key; state.viewVersion = f.currentVersion; state.editing = false; render(); };
    fl.appendChild(div);
  });

  const cl = $('contextList');
  cl.innerHTML = '';
  contexts().forEach((c) => {
    const div = document.createElement('div');
    div.className = 'list-item';
    div.innerHTML = `<div class="row"><span>${esc(c.name)}</span>
      <span class="del" title="删除">✕</span></div><div class="k">${esc(c.id)}</div>`;
    div.onclick = (e) => {
      if (e.target.classList.contains('del')) {
        if (confirm('删除保存的上下文 ' + c.name + '？')) {
          api('/api/contexts/' + encodeURIComponent(c.id), { method: 'DELETE' }).then(loadState);
        }
        e.stopPropagation();
        return;
      }
      $('contextInput').value = JSON.stringify(c.data, null, 2);
      $('useSavedCtxChk').checked = true;
      $('savedCtxSelect').value = c.id;
    };
    cl.appendChild(div);
  });

  const rl = $('recordList');
  rl.innerHTML = '';
  (state.project.records || []).slice().reverse().slice(0, 30).forEach((r) => {
    const div = document.createElement('div');
    div.className = 'list-item';
    div.innerHTML = `<div class="row"><span>${esc(r.flagKey)}</span>
      <span class="${r.value === true ? 'diff-same' : 'diff-changed'}">${esc(displayValue(r.value))}</span></div>
      <div class="k">v${r.version} · ${esc(r.contextName || r.createdAt.substring(0, 19).replace('T', ' '))}</div>`;
    div.onclick = () => openRecord(r.id);
    rl.appendChild(div);
  });
}

/* ---------------- flag config / editor ---------------- */

function renderFlagPanel() {
  if (!state.selectedFlag) {
    $('welcome').hidden = false; $('flagPanel').hidden = true; return;
  }
  $('welcome').hidden = true; $('flagPanel').hidden = false;
  const f = currentFlag();
  const fv = viewFV();
  $('flagName').textContent = f.name;
  $('flagKeyBadge').textContent = f.key;
  $('flagVerBadge').textContent = 'v' + fv.version + (fv.version === f.currentVersion ? '（当前）' : '（历史版本，只读）');
  $('flagMeta').textContent =
    `类型 ${fv.type} · salt ${fv.salt} · 身份字段 ${fv.stableIdentityField}` +
    (fv.prerequisiteKey ? ` · 前置 ${fv.prerequisiteKey}=${displayValue(fv.prerequisiteExpected)}` : '');

  const vs = $('versionSelect');
  vs.innerHTML = '';
  Object.keys(f.versions).map(Number).sort((a, b) => b - a).forEach((vn) => {
    const o = document.createElement('option');
    o.value = vn; o.textContent = '版本 v' + vn;
    vs.appendChild(o);
  });
  vs.value = String(fv.version);
  vs.onchange = () => { state.viewVersion = Number(vs.value); render(); };

  $('cmpA').innerHTML = vs.innerHTML; $('cmpB').innerHTML = vs.innerHTML;
  const nums = Object.keys(f.versions).map(Number).sort((a, b) => a - b);
  if (nums.length >= 2) { $('cmpA').value = String(nums[nums.length - 2]); $('cmpB').value = String(nums[nums.length - 1]); }

  const ctxSel = $('savedCtxSelect');
  ctxSel.innerHTML = '';
  contexts().forEach((c) => {
    const o = document.createElement('option');
    o.value = c.id; o.textContent = c.name;
    ctxSel.appendChild(o);
  });

  renderConfigView(f, fv);
  $('editorCard').hidden = !state.editing;
  $('configCard').hidden = state.editing;
  $('editBtn').disabled = fv.version !== f.currentVersion;
  $('editBtn').textContent = state.editing ? '正在编辑…' : '编辑并发布新版本';
  if (state.editing) renderEditor();
}

function renderConfigView(f, fv) {
  const box = $('configView');
  const prereq = fv.prerequisiteKey
    ? `${esc(fv.prerequisiteKey)} == ${esc(displayValue(fv.prerequisiteExpected))}` : '无';
  const sensitive = fv.sensitiveFields.length
    ? fv.sensitiveFields.map((s) => `<span class="tag-chip">${esc(s)}</span>`).join('') : '无';
  let rules = fv.rules.length ? '' : '<div class="muted">无规则，直接使用默认值</div>';
  fv.rules.forEach((r, i) => {
    let conds = r.conditions.length
      ? r.conditions.map((c) => `<code>${esc(c.field)}</code> ${esc(c.op)} <code>${esc(JSON.stringify(c.argument))}</code>`)
          .join('<br>且 ')
      : '<span class="muted">无条件（总是命中）</span>';
    let action;
    if (r.value !== null && r.value !== undefined) {
      action = `返回 <strong>${esc(displayValue(r.value))}</strong>`;
    } else {
      const total = r.rollout.reduce((s, x) => s + x.weightBp, 0);
      action = r.rollout.map((s) => `${esc(s.variant)} ${(s.weightBp / 100).toFixed(2)}%`).join('，') +
        (total < 10000 ? `（剩余 ${((10000 - total) / 100).toFixed(2)}% 落到后续规则/默认值）` : '');
    }
    rules += `<div class="rule-box"><div class="rule-handle"><span class="rid">#${i + 1} ${esc(r.id)}</span></div>
      <div class="rule-body"><div>${conds}</div><div class="muted">→ ${action}</div></div></div>`;
  });
  box.innerHTML = `
    <div class="kv">
      <div class="k">默认值</div><div><strong>${esc(displayValue(fv.default))}</strong></div>
      <div class="k">前置依赖</div><div>${prereq}</div>
      <div class="k">敏感字段</div><div>${sensitive}</div>
    </div>
    <h4>规则顺序</h4>${rules}`;
}

/* ---------------- draft editor ---------------- */

function startEdit() {
  const f = currentFlag();
  const fv = f.versions[f.currentVersion];
  state.draft = {
    type: fv.type,
    default: fv.default,
    salt: fv.salt,
    stableIdentityField: fv.stableIdentityField,
    prerequisiteKey: fv.prerequisiteKey || '',
    prerequisiteExpected: fv.prerequisiteExpected,
    sensitiveFields: fv.sensitiveFields.join(','),
    rules: fv.rules.map((r) => ({
      id: r.id,
      conditions: r.conditions.map((c) => ({ field: c.field, op: c.op, argument: JSON.stringify(c.argument) })),
      kind: r.value !== null && r.value !== undefined ? 'value' : 'rollout',
      value: r.value !== null && r.value !== undefined ? displayValue(r.value) : '',
      rollout: r.rollout ? r.rollout.map((s) => ({ variant: s.variant, weightBp: s.weightBp })) : [],
    })),
  };
  state.editing = true;
  render();
}

const OPS = ['eq', 'neq', 'contains', 'in', 'not_in', 'gt', 'gte', 'lt', 'lte', 'exists'];

function renderEditor() {
  const d = state.draft;
  $('edType').value = d.type;
  $('edDefault').value = d.type === 'boolean' ? (d.default ? 'true' : 'false') : d.default;
  $('edSalt').value = d.salt;
  $('edIdentity').value = d.stableIdentityField;
  const pk = $('edPrereqKey');
  pk.innerHTML = '<option value="">（无）</option>' +
    flags().filter((x) => x.key !== state.selectedFlag)
      .map((x) => `<option value="${esc(x.key)}">${esc(x.key)}</option>`).join('');
  pk.value = d.prerequisiteKey;
  $('edPrereqExpected').value = d.prerequisiteExpected == null ? '' : displayValue(d.prerequisiteExpected);
  $('edSensitive').value = d.sensitiveFields;

  ['edType', 'edDefault', 'edSalt', 'edIdentity', 'edPrereqKey', 'edPrereqExpected', 'edSensitive']
    .forEach((id) => {
      $(id).oninput = syncEditorFields;
      $(id).onchange = syncEditorFields;
    });

  const box = $('edRules');
  box.innerHTML = '';
  d.rules.forEach((rule, ri) => box.appendChild(ruleEl(rule, ri)));
}

function syncEditorFields() {
  const d = state.draft;
  d.type = $('edType').value;
  d.salt = $('edSalt').value.trim();
  d.stableIdentityField = $('edIdentity').value.trim();
  d.prerequisiteKey = $('edPrereqKey').value;
  d.sensitiveFields = $('edSensitive').value;
  d.defaultRaw = $('edDefault').value;
  d.prereqExpectedRaw = $('edPrereqExpected').value;
}

function ruleEl(rule, ri) {
  const el = document.createElement('div');
  el.className = 'rule-box';
  el.draggable = true;
  el.dataset.ri = ri;
  const condHtml = rule.conditions.map((c, ci) => `
    <div class="cond-row">
      <input value="${esc(c.field)}" placeholder="字段（支持 a.b）" data-k="field" data-ci="${ci}">
      <select data-k="op" data-ci="${ci}">${OPS.map((o) => `<option ${o === c.op ? 'selected' : ''}>${o}</option>`).join('')}</select>
      <input value="${esc(c.argument)}" placeholder="参数 JSON（exists 可留空）" data-k="argument" data-ci="${ci}">
      <button class="mini" data-act="delCond" data-ci="${ci}" title="删除条件">✕</button>
    </div>`).join('');

  let actionHtml;
  if (rule.kind === 'value') {
    actionHtml = `<div class="roll-row">
      <span class="muted">命中时返回</span>
      <input value="${esc(rule.value)}" data-k="value" placeholder="true/false 或变体文本">
      <span></span><span></span></div>`;
  } else {
    actionHtml = rule.rollout.map((s, si) => {
      const pct = s.weightBp / 100;
      return `<div class="roll-row">
        <input value="${esc(s.variant)}" data-k="variant" data-si="${si}" placeholder="on/off 或变体名">
        <input type="number" min="0" max="100" step="0.01" value="${pct}" data-k="pct" data-si="${si}" title="百分比">
        <div class="track"><div class="fill" style="width:${pct}%"></div></div>
        <button class="mini" data-act="delSlice" data-si="${si}">✕</button>
      </div>`;
    }).join('') + `<div class="row-actions"><button class="mini" data-act="addSlice" style="width:auto;padding:0 10px">＋ 分桶</button>
      <span class="muted">合计 ${(rule.rollout.reduce((a, s) => a + s.weightBp, 0) / 100).toFixed(2)}%</span></div>`;
  }

  el.innerHTML = `
    <div class="rule-handle"><span class="grip">⠿</span>
      <input value="${esc(rule.id)}" data-k="id" style="max-width:180px" placeholder="规则 id">
      <span class="muted">第 ${ri + 1} 条</span>
      <span style="flex:1"></span>
      <button class="mini" data-act="delRule" title="删除规则">✕</button>
    </div>
    <div class="rule-body">
      ${condHtml}
      <div class="row-actions"><button class="mini" data-act="addCond" style="width:auto;padding:0 10px">＋ 条件（AND）</button></div>
      <div class="row-actions">
        <label class="chk"><input type="radio" name="kind-${ri}" value="value" ${rule.kind === 'value' ? 'checked' : ''} data-act="kindValue"> 固定值</label>
        <label class="chk"><input type="radio" name="kind-${ri}" value="rollout" ${rule.kind === 'rollout' ? 'checked' : ''} data-act="kindRollout"> 百分比分流</label>
      </div>
      ${actionHtml}
    </div>`;

  el.addEventListener('dragstart', (e) => { el.classList.add('dragging'); e.dataTransfer.setData('text/ri', ri); });
  el.addEventListener('dragend', () => el.classList.remove('dragging'));
  el.addEventListener('dragover', (e) => { e.preventDefault(); el.classList.add('dragover'); });
  el.addEventListener('dragleave', () => el.classList.remove('dragover'));
  el.addEventListener('drop', (e) => {
    e.preventDefault();
    el.classList.remove('dragover');
    const from = Number(e.dataTransfer.getData('text/ri'));
    const to = ri;
    if (from === to) return;
    const arr = state.draft.rules;
    const [moved] = arr.splice(from, 1);
    arr.splice(to, 0, moved);
    renderEditor();
  });

  el.addEventListener('input', (e) => {
    const t = e.target;
    const k = t.dataset.k;
    if (!k) return;
    if (k === 'id') { rule.id = t.value; return; }
    if (k === 'value') { rule.value = t.value; return; }
    if (k === 'field' || k === 'op' || k === 'argument') {
      rule.conditions[Number(t.dataset.ci)][k] = t.value;
    }
    if (k === 'variant') { rule.rollout[Number(t.dataset.si)].variant = t.value; renderEditor(); }
    if (k === 'pct') { rule.rollout[Number(t.dataset.si)].weightBp = Math.round(Number(t.value) * 100); renderEditor(); }
  });

  el.addEventListener('click', (e) => {
    const act = e.target.dataset.act;
    if (!act) return;
    syncEditorFields();
    if (act === 'delRule') state.draft.rules.splice(ri, 1);
    if (act === 'addCond') rule.conditions.push({ field: '', op: 'eq', argument: 'null' });
    if (act === 'delCond') rule.conditions.splice(Number(e.target.dataset.ci), 1);
    if (act === 'kindValue') { rule.kind = 'value'; rule.value = rule.value || (state.draft.type === 'boolean' ? 'true' : 'variant'); }
    if (act === 'kindRollout') { rule.kind = 'rollout'; if (!rule.rollout.length) rule.rollout = [{ variant: state.draft.type === 'boolean' ? 'on' : 'full', weightBp: 1000 }]; }
    if (act === 'addSlice') rule.rollout.push({ variant: state.draft.type === 'boolean' ? 'off' : 'other', weightBp: 1000 });
    if (act === 'delSlice') rule.rollout.splice(Number(e.target.dataset.si), 1);
    renderEditor();
  });

  return el;
}

function parseScalar(raw, { boolOnly = false } = {}) {
  const s = raw.trim();
  if (s === 'true') return true;
  if (s === 'false') return false;
  if (boolOnly) throw new Error('布尔值只能是 true/false（不会把 "true" 字符串转成布尔）');
  // numbers stay JSON numbers, strings stay strings; quoted means string
  if ((s.startsWith('"') && s.endsWith('"'))) return JSON.parse(s);
  if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
  return s;
}

function buildDraftPayload() {
  syncEditorFields();
  const d = state.draft;
  const rules = d.rules.map((r) => {
    const base = {
      id: r.id.trim(),
      conditions: r.conditions.map((c) => ({
        field: c.field.trim(),
        op: c.op,
        argument: c.op === 'exists' ? null : JSON.parse(c.argument),
      })),
    };
    if (r.kind === 'value') {
      return { ...base, value: parseScalar(r.value, { boolOnly: d.type === 'boolean' }), rollout: null };
    }
    return {
      ...base, value: null,
      rollout: r.rollout.map((s) => ({
        variant: d.type === 'boolean'
          ? (parseScalar(s.variant, { boolOnly: true }) ? 'on' : 'off')
          : String(s.variant),
        weightBp: s.weightBp,
      })),
    };
  });
  const prereqExpected = d.prereqExpectedRaw.trim() === '' ? null
    : parseScalar(d.prereqExpectedRaw);
  return {
    type: d.type,
    default: parseScalar(d.defaultRaw || $('edDefault').value, { boolOnly: d.type === 'boolean' }),
    salt: d.salt || ('salt-' + Math.random().toString(36).slice(2, 8)),
    stableIdentityField: d.stableIdentityField,
    prerequisiteKey: d.prerequisiteKey || null,
    prerequisiteExpected: prereqExpected,
    sensitiveFields: d.sensitiveFields.split(',').map((s) => s.trim()).filter(Boolean),
    rules,
  };
}

async function publish() {
  let payload;
  try { payload = buildDraftPayload(); }
  catch (e) { $('editorError').hidden = false; $('editorError').textContent = '草稿错误：' + e.message; return; }
  try {
    await api(`/api/flags/${encodeURIComponent(state.selectedFlag)}/versions`, {
      method: 'POST', body: JSON.stringify(payload),
    });
    state.editing = false; state.draft = null;
    await loadState();
    const f = currentFlag(); state.viewVersion = f.currentVersion; render();
  } catch (e) {
    $('editorError').hidden = false; $('editorError').textContent = e.message;
  }
}

/* ---------------- evaluation & trace ---------------- */

function readContext() {
  const raw = $('contextInput').value.trim();
  if (!raw) return {};
  return JSON.parse(raw);
}

async function doEvaluate(batch = false, save = false) {
  $('evalError').hidden = true;
  let ctx;
  try { ctx = readContext(); }
  catch (e) { $('evalError').hidden = false; $('evalError').textContent = '上下文 JSON 解析失败：' + e.message; return; }

  const ctxId = $('useSavedCtxChk').checked ? $('savedCtxSelect').value : null;
  const ctxName = ctxId ? contexts().find((c) => c.id === ctxId)?.name : null;

  try {
    if (batch) {
      const keys = flags().map((f) => f.key);
      const data = await api('/api/evaluate-batch', {
        method: 'POST',
        body: JSON.stringify({
          flagKeys: keys, context: ctx, saveRecord: save,
          contextId: ctxId, contextName: ctxName,
        }),
      });
      renderBatchResults(data);
    } else {
      const data = await api(`/api/flags/${encodeURIComponent(state.selectedFlag)}/evaluate`, {
        method: 'POST',
        body: JSON.stringify({
          context: ctx, saveRecord: save,
          contextId: ctxId, contextName: ctxName,
        }),
      });
      renderSingleResult(data);
    }
    if (save) await loadState();
  } catch (e) {
    $('evalError').hidden = false; $('evalError').textContent = e.message;
  }
}

function valueClass(v) {
  if (v === true) return 'on';
  if (v === false) return 'off';
  return 'variant';
}

function renderSingleResult(data) {
  const bar = $('resultBar');
  bar.className = 'result-bar ' + valueClass(data.value);
  bar.innerHTML = `<span class="big">${esc(displayValue(data.value))}</span>
    <span class="muted">最终值（版本见轨迹顶部）</span>`;
  $('traceTree').innerHTML = '';
  $('traceTree').appendChild(traceNodeEl(data.trace, true));
}

function renderBatchResults(data) {
  const bar = $('resultBar');
  bar.className = 'result-bar';
  const snap = Object.entries(data.snapshotVersions)
    .map(([k, v]) => `<span class="tag-chip">${esc(k)}@v${v}</span>`).join(' ');
  bar.innerHTML = `<div><strong>同一次快照：</strong>${snap}</div>`;
  const tree = $('traceTree');
  tree.innerHTML = '';
  data.results.forEach((r) => {
    const wrap = document.createElement('div');
    wrap.className = 'tnode head';
    wrap.innerHTML = `<div class="line">
      <span class="big ${valueClass(r.value)}" style="font-weight:700">${esc(displayValue(r.value))}</span>
      <span class="step">flag</span><strong>${esc(r.flagKey)}</strong>
      <span class="muted">v${r.version}</span></div>`;
    const children = document.createElement('div');
    children.className = 'tchildren';
    children.appendChild(traceNodeEl(r.trace, true));
    wrap.appendChild(children);
    tree.appendChild(wrap);
  });
}

function detailSpans(detail) {
  const out = [];
  const order = ['reason', 'field', 'op', 'present', 'expected', 'argument', 'actual',
    'ruleId', 'variant', 'startBp', 'endBp', 'algorithm', 'bucketInput', 'bucketBp',
    'rolloutMatched', 'stableIdentityField', 'requiredFlag', 'requiredVersion',
    'result', 'version', 'flagKey'];
  const keys = [...order.filter((k) => k in detail), ...Object.keys(detail).filter((k) => !order.includes(k))];
  keys.forEach((k) => {
    if (k === 'reads') return;
    let v = detail[k];
    if (k === 'valueSummary' && v && v.kind === 'sensitive') {
      out.push(`<span class="detail">敏感值摘要 <span class="tag-chip">${esc(v.tag)}</span></span>`);
      return;
    }
    if (v === null || v === undefined) return;
    if (typeof v === 'object') v = JSON.stringify(v);
    if (k === 'bucketInput') {
      out.push(`<span class="detail">分桶输入=<code>${esc(v)}</code></span>`);
    } else if (k === 'bucketBp') {
      out.push(`<span class="detail">桶=${esc(v)}/10000 (${(v / 100).toFixed(2)}%)</span>`);
    } else {
      out.push(`<span class="detail">${esc(k)}=${esc(displayValue(v))}</span>`);
    }
  });
  return out.join(' ');
}

function traceNodeEl(node, isRoot) {
  const el = document.createElement('div');
  el.className = 'tnode' + (isRoot ? ' head' : '');
  const reads = (node.reads || []);
  const readChips = reads.length
    ? `<div class="reads"><span class="muted">读取字段：</span>${reads.map((r) => `<span class="tag-chip">${esc(r)}</span>`).join('')}</div>`
    : '';
  el.innerHTML = `
    <div class="line">
      <span class="caret">▼</span>
      <span class="step">${esc(node.step)}</span>
      <span class="pill ${esc(node.outcome)}">${esc(node.outcome)}</span>
      <span>${detailSpans(node.detail)}</span>
    </div>
    ${readChips}
    <div class="tchildren"></div>`;
  const line = el.querySelector('.line');
  const childBox = el.querySelector('.tchildren');
  node.children.forEach((c) => childBox.appendChild(traceNodeEl(c, false)));
  line.onclick = () => el.classList.toggle('collapsed');
  return el;
}

/* ---------------- version diff ---------------- */

async function compare() {
  const f = currentFlag();
  const a = $('cmpA').value, b = $('cmpB').value;
  const ids = $('cmpAllCtx').checked ? contexts().map((c) => c.id) : [];
  const url = `/api/flags/${encodeURIComponent(f.key)}/compare?a=${a}&b=${b}` +
    (ids.length ? '&contexts=' + ids.map(encodeURIComponent).join(',') : '');
  try {
    const d = await api(url);
    let html = '';
    if (d.changes.length) {
      html += '<table class="diff"><tr><th>变更项</th><th>v' + a + '</th><th>v' + b + '</th></tr>';
      d.changes.forEach((c) => {
        html += `<tr><td>${esc(c.field)}</td>
          <td><code>${esc(JSON.stringify(c.from))}</code></td>
          <td><code>${esc(JSON.stringify(c.to))}</code></td></tr>`;
      });
      html += '</table>';
    } else {
      html = '<div class="muted">两个版本结构完全相同。</div>';
    }
    if (d.contextRows.length) {
      html += '<table class="diff"><tr><th>上下文</th><th>v' + a + '</th><th>v' + b + '</th><th>影响</th></tr>';
      d.contextRows.forEach((r) => {
        html += `<tr><td>${esc(r.contextName)}</td>
          <td class="${valueClass(r.valueA)}">${esc(displayValue(r.valueA))}</td>
          <td class="${valueClass(r.valueB)}">${esc(displayValue(r.valueB))}</td>
          <td class="${r.changed ? 'diff-changed' : 'diff-same'}">${r.changed ? '改变' : '一致'}</td></tr>`;
      });
      html += '</table>';
    }
    $('diffView').innerHTML = html;
  } catch (e) {
    $('diffView').innerHTML = `<div class="error">${esc(e.message)}</div>`;
  }
}

/* ---------------- records / replay ---------------- */

async function openRecord(id) {
  try {
    const d = await api(`/api/records/${encodeURIComponent(id)}/replay`);
    const r = d.record;
    $('recordTitle').textContent = `${r.flagKey} · v${r.version} 记录重放`;
    $('recordContent').innerHTML = `
      <div class="kv">
        <div class="k">上下文</div><div>${esc(r.contextName || '（未命名）')}</div>
        <div class="k">记录中的结果</div>
        <div class="${valueClass(r.result)}"><strong>${esc(displayValue(r.result))}</strong></div>
        <div class="k">按绑定版本重放</div>
        <div class="${valueClass(d.replay.value)}">${esc(displayValue(d.replay.value))}
          ${d.sameValue ? '<span class="diff-same">（一致）</span>' : '<span class="diff-changed">（不一致）</span>'}</div>
      </div>
      <h4>历史轨迹（冻结于发布时的规则）</h4>
      <div class="trace"></div>`;
    $('recordContent').querySelector('.trace').appendChild(traceNodeEl(r.trace, true));
    $('recordModal').hidden = false;
  } catch (e) { alert(e.message); }
}

/* ---------------- create flag / contexts / import ---------------- */

async function createFlag() {
  const key = prompt('开关 key（小写字母/数字/下划线/连字符）', 'new_flag');
  if (!key) return;
  const type = confirm('确定用 boolean 类型吗？（取消=string 变体）') ? 'boolean' : 'string';
  const payload = {
    type,
    default: type === 'boolean' ? false : 'off',
    salt: key + '-v1',
    stableIdentityField: 'user_id',
    prerequisiteKey: null,
    prerequisiteExpected: null,
    sensitiveFields: [],
    rules: [],
  };
  try {
    await api('/api/flags', { method: 'POST', body: JSON.stringify({ key, name: key, version: payload }) });
    state.selectedFlag = key; state.viewVersion = 1;
    await loadState(); render();
  } catch (e) { alert(e.message); }
}

async function saveCurrentContext() {
  let data;
  try { data = readContext(); }
  catch (e) { alert('上下文 JSON 解析失败：' + e.message); return; }
  const name = prompt('给这个上下文起个名字', '上下文 ' + (contexts().length + 1));
  if (!name) return;
  await api('/api/contexts', { method: 'POST', body: JSON.stringify({ name, data }) });
  await loadState();
}

function initImport() {
  $('importFile').onchange = async () => {
    const file = $('importFile').files[0];
    if (!file) return;
    const mode = $('importMode').value;
    const text = await file.text();
    if (!confirm('以「' + (mode === 'replace' ? '替换（恢复）' : '合并') + '」模式导入？' +
      (mode === 'replace' ? '\n替换会覆盖当前全部开关、上下文与记录。' : ''))) return;
    try {
      const r = await api('/api/import?mode=' + mode, { method: 'POST', body: text });
      alert(`导入完成：${r.flags} 个开关，${r.contexts} 个上下文，${r.records} 条记录`);
      await loadState();
    } catch (e) { alert('导入失败：' + e.message); }
    $('importFile').value = '';
  };
}

function render() {
  renderSidebar();
  renderFlagPanel();
}

function init() {
  $('refreshBtn').onclick = loadState;
  $('newFlagBtn').onclick = createFlag;
  $('newCtxBtn').onclick = saveCurrentContext;
  $('editBtn').onclick = startEdit;
  $('cancelEditBtn').onclick = () => { state.editing = false; render(); };
  $('publishBtn').onclick = publish;
  $('addValueRule').onclick = () => {
    syncEditorFields();
    state.draft.rules.push({
      id: 'rule-' + (state.draft.rules.length + 1),
      conditions: [{ field: '', op: 'eq', argument: '"value"' }],
      kind: 'value',
      value: state.draft.type === 'boolean' ? 'true' : 'variant',
      rollout: [],
    });
    renderEditor();
  };
  $('addRolloutRule').onclick = () => {
    syncEditorFields();
    state.draft.rules.push({
      id: 'rollout-' + (state.draft.rules.length + 1),
      conditions: [{ field: '', op: 'eq', argument: '"value"' }],
      kind: 'rollout',
      value: '',
      rollout: [
        { variant: state.draft.type === 'boolean' ? 'on' : 'full', weightBp: 5000 },
        { variant: state.draft.type === 'boolean' ? 'off' : 'other', weightBp: 5000 },
      ],
    });
    renderEditor();
  };
  $('evalBtn').onclick = () => doEvaluate(false, false);
  $('evalBatchBtn').onclick = () => doEvaluate(true, false);
  $('saveEvalRecord').onclick = () => doEvaluate(false, true);
  $('compareBtn').onclick = compare;
  $('closeRecord').onclick = () => { $('recordModal').hidden = true; };
  $('recordModal').onclick = (e) => { if (e.target.id === 'recordModal') $('recordModal').hidden = true; };
  $('contextInput').value = JSON.stringify({
    user_id: 'demo-user',
    country: 'JP',
    staff: false,
    tier: 'gold',
    email: 'demo@example.com',
  }, null, 2);
  initImport();
  loadState();
}

document.addEventListener('DOMContentLoaded', init);
