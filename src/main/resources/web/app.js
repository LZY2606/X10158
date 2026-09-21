"use strict";

const state = {
  projects: [],
  currentProject: null,
  flags: [],
  contexts: [],
  editingFlagKey: null,
  draft: { rules: [], prerequisites: [], defaultValue: "off", stableIdField: "user.id" }
};

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
  return data;
}

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));
    btn.classList.add("active");
    $("tab-" + btn.dataset.tab).classList.add("active");
    if (btn.dataset.tab === "history") loadHistory();
    if (btn.dataset.tab === "compare") setupCompare();
  });
});

async function boot() {
  state.projects = await api("/api/projects");
  renderProjectSelect();
  if (state.projects.length) await selectProject(state.projects[0].id);
  bindStaticEvents();
}

function renderProjectSelect() {
  $("projectSelect").innerHTML = state.projects
    .map((p) => `<option value="${esc(p.id)}">${esc(p.name)}</option>`).join("");
}

$("projectSelect").addEventListener("change", async (e) => {
  await selectProject(e.target.value);
});

async function selectProject(projectId) {
  state.currentProject = projectId;
  $("projectSelect").value = projectId;
  const detail = await api(`/api/projects/${projectId}`);
  state.flags = detail.flags;
  state.contexts = detail.contexts;
  renderFlags();
  renderContexts();
  renderEvalControls();
}

$("newProjectBtn").addEventListener("click", async () => {
  const name = prompt("项目名称", "新项目");
  if (name === null) return;
  const sensitiveRaw = prompt("敏感字段（逗号分隔，支持 user.pii.* 通配）", "user.email,user.phone");
  const sensitive = (sensitiveRaw || "").split(",").map((s) => s.trim()).filter(Boolean);
  const project = await api("/api/projects", {
    method: "POST",
    body: { name, sensitiveFields: sensitive }
  });
  state.projects.push(project);
  renderProjectSelect();
  await selectProject(project.id);
});

$("editProjectBtn").addEventListener("click", async () => {
  const project = state.projects.find((p) => p.id === state.currentProject);
  const sensitiveRaw = prompt("敏感字段（逗号分隔）", project.sensitiveFields.join(", "));
  if (sensitiveRaw === null) return;
  const sensitive = sensitiveRaw.split(",").map((s) => s.trim()).filter(Boolean);
  const updated = await api(`/api/projects/${project.id}`, {
    method: "PUT",
    body: { name: project.name, sensitiveFields: sensitive }
  });
  state.projects = state.projects.map((p) => (p.id === updated.id ? updated : p));
});

function renderFlags() {
  const tbody = $("flagTable").querySelector("tbody");
  if (!state.flags.length) {
    tbody.innerHTML = `<tr><td colspan="5" class="muted">还没有开关，点击“新建开关草稿”。</td></tr>`;
    return;
  }
  tbody.innerHTML = state.flags.map((f) => {
    const latest = f.versions[f.versions.length - 1];
    return `<tr>
      <td><code>${esc(f.key)}</code></td>
      <td>${esc(f.name)}</td>
      <td>${f.versions.length}</td>
      <td>${latest ? "v" + latest.version : "—"}</td>
      <td><button data-edit="${esc(f.key)}">编辑</button></td>
    </tr>`;
  }).join("");
  tbody.querySelectorAll("[data-edit]").forEach((b) =>
    b.addEventListener("click", () => openEditor(b.dataset.edit)));
}

$("newFlagBtn").addEventListener("click", async () => {
  const key = prompt("开关 key（小写字母/数字/下划线/点）", "new_flag");
  if (!key) return;
  if (!/^[a-zA-Z0-9_.\-]+$/.test(key)) return alert("key 只能包含字母数字、_、.、-");
  await openEditorWith(key, {
    name: key,
    description: "",
    rules: [newRuleDraft()],
    prerequisites: [],
    defaultValue: "off",
    stableIdField: "user.id"
  });
});

async function openEditor(key) {
  const flag = state.flags.find((f) => f.key === key);
  await openEditorWith(key, {
    name: flag.name,
    description: flag.description,
    rules: flag.draftRules?.length ? flag.draftRules : flag.versions.at(-1)?.rules || [newRuleDraft()],
    prerequisites: flag.draftPrerequisites?.length
      ? flag.draftPrerequisites
      : flag.versions.at(-1)?.prerequisites || [],
    defaultValue: (flag.draftDefaultValue || flag.versions.at(-1)?.defaultValue || { name: "off" }).name,
    stableIdField: flag.draftStableIdField || flag.versions.at(-1)?.stableIdField || "user.id"
  });
}

async function openEditorWith(key, draft) {
  state.editingFlagKey = key;
  state.draft = draft;
  $("flagEditor").classList.remove("hidden");
  $("editorFlagKey").textContent = key;
  $("flagName").value = draft.name;
  $("flagDescription").value = draft.description || "";
  $("stableIdField").value = draft.stableIdField;
  $("defaultValue").value = draft.defaultValue;
  renderPrereqs();
  renderRules();
  $("flagEditor").scrollIntoView({ behavior: "smooth" });
}

function renderContexts() {
  $("evalSavedContext").innerHTML =
    `<option value="">— 手动输入 —</option>` +
    state.contexts.map((c) => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join("");
  const tbody = $("contextTable").querySelector("tbody");
  tbody.innerHTML = state.contexts.map((c) =>
    `<tr>
      <td>${esc(c.name)}</td>
      <td><code>${esc(JSON.stringify(c.context)).slice(0, 90)}</code></td>
      <td>
        <button data-use="${esc(c.id)}">填入</button>
        <button class="danger" data-del-ctx="${esc(c.id)}">删除</button>
      </td>
    </tr>`).join("");
  tbody.querySelectorAll("[data-use]").forEach((b) =>
    b.addEventListener("click", () => {
      const c = state.contexts.find((x) => x.id === b.dataset.use);
      $("evalContext").value = JSON.stringify(c.context, null, 2);
    }));
  tbody.querySelectorAll("[data-del-ctx]").forEach((b) =>
    b.addEventListener("click", async () => {
      await api(`/api/contexts/${b.dataset.delCtx}`, { method: "DELETE" });
      await selectProject(state.currentProject);
    }));
}

$("saveContextBtn").addEventListener("click", async () => {
  let parsed;
  try {
    parsed = JSON.parse($("contextJson").value);
  } catch (e) {
    return alert("上下文 JSON 不合法: " + e.message);
  }
  await api("/api/contexts", {
    method: "POST",
    body: { name: $("contextName").value || "未命名", context: parsed }
  });
  await selectProject(state.currentProject);
});

function renderEvalControls() {
  $("evalFlag").innerHTML = state.flags
    .filter((f) => f.versions.length)
    .map((f) => `<option value="${esc(f.key)}">${esc(f.key)}</option>`).join("");
  refreshVersionSelect();
}

function refreshVersionSelect() {
  const flag = state.flags.find((f) => f.key === $("evalFlag").value)
    || state.flags.find((f) => f.versions.length);
  $("evalVersion").innerHTML = `<option value="">最新</option>` +
    (flag?.versions || []).map((v) => `<option value="${v.version}">v${v.version} — ${esc(v.note || "")}</option>`)
      .join("");
}

$("evalFlag").addEventListener("change", refreshVersionSelect);
$("evalSavedContext").addEventListener("change", () => {
  const c = state.contexts.find((x) => x.id === $("evalSavedContext").value);
  if (c) $("evalContext").value = JSON.stringify(c.context, null, 2);
});

const OPERATORS = [
  "eq", "ne", "gt", "gte", "lt", "lte",
  "in", "notIn", "contains", "startsWith", "endsWith", "exists"
];

function newConditionDraft() {
  return { field: "user.tier", operator: "eq", value: "vip" };
}

function newRuleDraft() {
  return {
    id: "rule_" + Math.random().toString(36).slice(2, 8),
    name: "新规则",
    conditions: [newConditionDraft()],
    serve: "on",
    rollout: null,
    fallbackServe: "off",
    salt: ""
  };
}

function renderPrereqs() {
  const root = $("prereqList");
  root.innerHTML = state.draft.prerequisites.map((p, i) => `
    <div class="prereq-card" data-index="${i}">
      <div class="cond-row">
        <select class="p-key">
          ${state.flags.filter((f) => f.key !== state.editingFlagKey)
            .map((f) => `<option ${f.key === p.flagKey ? "selected" : ""}>${esc(f.key)}</option>`)
            .join("")}
        </select>
        <span>需要结果 ∈</span>
        <input class="p-any" value="${esc((p.anyOf || ["on"]).map((x) => x.name || x).join(","))}"
               placeholder="on,blue" style="flex:1">
        <span>否则给出</span>
        <input class="p-gate" value="${esc((p.gateServe?.name ?? p.gateServe) ?? "off")}" class="small">
        <button class="p-del danger">删</button>
      </div>
    </div>`).join("");
  root.querySelectorAll(".prereq-card").forEach((card) => {
    const i = Number(card.dataset.index);
    card.querySelector(".p-key").addEventListener("change", (e) => {
      state.draft.prerequisites[i].flagKey = e.target.value;
    });
    card.querySelector(".p-any").addEventListener("input", (e) => {
      state.draft.prerequisites[i].anyOf = e.target.value.split(",").map((s) => s.trim()).filter(Boolean);
    });
    card.querySelector(".p-gate").addEventListener("input", (e) => {
      state.draft.prerequisites[i].gateServe = e.target.value;
    });
    card.querySelector(".p-del").addEventListener("click", () => {
      state.draft.prerequisites.splice(i, 1);
      renderPrereqs();
    });
  });
}

$("addPrereqBtn").addEventListener("click", () => {
  const firstOther = state.flags.find((f) => f.key !== state.editingFlagKey);
  if (!firstOther) return alert("需要先有另一个开关");
  state.draft.prerequisites.push({
    flagKey: firstOther.key, anyOf: ["on"], gateServe: "off"
  });
  renderPrereqs();
});

function renderRules() {
  const root = $("ruleList");
  root.innerHTML = state.draft.rules.map((rule, i) => {
    const rolloutEnabled = !!rule.rollout;
    const total = (rule.rollout || []).reduce((s, c) => s + Number(c.weightBp || 0), 0);
    return `
    <div class="rule-card" data-index="${i}">
      <div class="rule-head">
        <button class="r-up" title="上移">↑</button>
        <button class="r-down" title="下移">↓</button>
        <input class="r-name" value="${esc(rule.name)}" placeholder="规则名称">
        <code class="muted">${esc(rule.id)}</code>
        <label class="inline"><input type="checkbox" class="r-rollout" ${rolloutEnabled ? "checked" : ""}>
          百分比分流</label>
        <button class="r-del danger">删</button>
      </div>
      <div class="conditions"></div>
      <button class="r-add-cond">+ 条件（AND）</button>
      ${rolloutEnabled ? `
        <h3>拖动百分比（基点，满分 10000 = 100%）</h3>
        <div class="clauses"></div>
        <div class="row">
          <button class="r-add-clause">+ 变体分桶</button>
          <span class="muted">已分配 ${total} / 10000（${(total / 100).toFixed(2)}%）；
            未覆盖部分走 fallback</span>
        </div>
        <div class="cond-row" style="margin-top:6px">
          <span>fallback 值</span>
          <input class="r-fallback" value="${esc(rule.fallbackServe || "off")}">
          <span>salt</span>
          <input class="r-salt" value="${esc(rule.salt || "")}" placeholder="留空则用规则 id">
        </div>
      ` : `
        <div class="cond-row" style="margin-top:6px">
          <span>固定给出</span>
          <input class="r-serve" value="${esc(rule.serve || "on")}">
        </div>
      `}
    </div>`;
  }).join("");

  root.querySelectorAll(".rule-card").forEach((card) => bindRuleCard(card, Number(card.dataset.index)));
}

function bindRuleCard(card, i) {
  const rule = state.draft.rules[i];
  card.querySelector(".r-up").addEventListener("click", () => {
    if (i > 0) {
      [state.draft.rules[i - 1], state.draft.rules[i]] = [state.draft.rules[i], state.draft.rules[i - 1]];
      renderRules();
    }
  });
  card.querySelector(".r-down").addEventListener("click", () => {
    if (i < state.draft.rules.length - 1) {
      [state.draft.rules[i + 1], state.draft.rules[i]] = [state.draft.rules[i], state.draft.rules[i + 1]];
      renderRules();
    }
  });
  card.querySelector(".r-del").addEventListener("click", () => {
    state.draft.rules.splice(i, 1);
    renderRules();
  });
  card.querySelector(".r-name").addEventListener("input", (e) => { rule.name = e.target.value; });
  const serveInput = card.querySelector(".r-serve");
  if (serveInput) serveInput.addEventListener("input", (e) => { rule.serve = e.target.value; });
  const fallbackInput = card.querySelector(".r-fallback");
  if (fallbackInput) fallbackInput.addEventListener("input", (e) => { rule.fallbackServe = e.target.value; });
  const saltInput = card.querySelector(".r-salt");
  if (saltInput) saltInput.addEventListener("input", (e) => { rule.salt = e.target.value; });
  card.querySelector(".r-rollout").addEventListener("change", (e) => {
    rule.rollout = e.target.checked
      ? (rule.rollout?.length ? rule.rollout : [{ serve: "blue", weightBp: 5000 }])
      : null;
    rule.serve = e.target.checked ? null : rule.serve || "on";
    renderRules();
  });
  card.querySelector(".r-add-cond").addEventListener("click", () => {
    rule.conditions.push(newConditionDraft());
    renderRules();
  });
  if (card.querySelector(".r-add-clause")) {
    card.querySelector(".r-add-clause").addEventListener("click", () => {
      rule.rollout.push({ serve: "variant", weightBp: 1000 });
      renderRules();
    });
  }
  renderConditionEditors(card, rule);
  renderClauseSliders(card, rule);
}

function renderConditionEditors(card, rule) {
  const host = card.querySelector(".conditions");
  host.innerHTML = rule.conditions.map((c, ci) => `
    <div class="cond-row">
      <input class="c-field f-field" value="${esc(c.field)}">
      <select class="c-op f-op">
        ${OPERATORS.map((op) => `<option ${op === c.operator ? "selected" : ""}>${op}</option>`).join("")}
      </select>
      <input class="c-val f-val" value="${esc(formatConditionValue(c.value))}"
             placeholder='JSON 值，如 "vip" / 100 / true / null' ${c.operator === "exists" ? "disabled" : ""}>
      <button class="c-del danger">×</button>
    </div>`).join("");
  host.querySelectorAll(".cond-row").forEach((row, ci) => {
    row.querySelector(".c-field").addEventListener("input", (e) => {
      rule.conditions[ci].field = e.target.value;
    });
    row.querySelector(".c-op").addEventListener("change", (e) => {
      rule.conditions[ci].operator = e.target.value;
      renderRules();
    });
    row.querySelector(".c-val").addEventListener("change", (e) => {
      rule.conditions[ci].value = parseConditionValue(e.target.value);
    });
    row.querySelector(".c-del").addEventListener("click", () => {
      rule.conditions.splice(ci, 1);
      renderRules();
    });
  });
}

function formatConditionValue(v) {
  if (v === undefined || v === null) return "null";
  if (typeof v === "string") return JSON.stringify(v);
  return JSON.stringify(v);
}

function parseConditionValue(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return text;
  }
}

function renderClauseSliders(card, rule) {
  const host = card.querySelector(".clauses");
  if (!host) return;
  host.innerHTML = rule.rollout.map((clause, ci) => {
    const pct = (clause.weightBp / 100).toFixed(2);
    return `
      <div class="slider-row">
        <input class="cl-serve" value="${esc(clause.serve.name ?? clause.serve)}" style="width:90px">
        <input type="range" class="cl-slider" min="0" max="10000" step="50" value="${clause.weightBp}">
        <span class="cl-bp">${clause.weightBp}</span>
        <span class="muted">${pct}%</span>
        <button class="cl-del danger">×</button>
      </div>`;
  }).join("");
  host.querySelectorAll(".slider-row").forEach((row, ci) => {
    row.querySelector(".cl-slider").addEventListener("input", (e) => {
      const v = Number(e.target.value);
      rule.rollout[ci].weightBp = v;
      row.querySelector(".cl-bp").textContent = v;
      row.nextElementSibling;
      const total = rule.rollout.reduce((s, c) => s + Number(c.weightBp || 0), 0);
      if (total > 10000) {
        rule.rollout[ci].weightBp -= total - 10000;
        e.target.value = rule.rollout[ci].weightBp;
        row.querySelector(".cl-bp").textContent = rule.rollout[ci].weightBp;
      }
    });
    row.querySelector(".cl-serve").addEventListener("input", (e) => {
      rule.rollout[ci].serve = e.target.value;
    });
    row.querySelector(".cl-del").addEventListener("click", () => {
      rule.rollout.splice(ci, 1);
      renderRules();
    });
  });
}

$("addRuleBtn").addEventListener("click", () => {
  state.draft.rules.push(newRuleDraft());
  renderRules();
});

function collectDraftPayload(publish) {
  const rules = state.draft.rules.map((r) => ({
    id: r.id,
    name: r.name,
    conditions: r.conditions.map((c) => ({
      field: c.field,
      operator: c.operator,
      value: c.value
    })),
    serve: r.rollout ? (r.fallbackServe || null) : (r.serve || "on"),
    fallbackServe: r.rollout ? (r.fallbackServe || "off") : undefined,
    rollout: r.rollout ? r.rollout.map((c) => ({
      serve: c.serve.name ?? c.serve,
      weightBp: Number(c.weightBp)
    })) : null,
    salt: r.salt || ""
  }));
  return {
    name: $("flagName").value,
    description: $("flagDescription").value,
    stableIdField: $("stableIdField").value,
    defaultValue: $("defaultValue").value,
    rules,
    prerequisites: state.draft.prerequisites.map((p) => ({
      flagKey: p.flagKey,
      anyOf: p.anyOf.map((x) => x.name ?? x),
      gateServe: p.gateServe?.name ?? p.gateServe ?? "off"
    })),
    publish,
    note: $("publishNote").value || ""
  };
}

$("saveDraftBtn").addEventListener("click", () => saveFlag(false));
$("publishBtn").addEventListener("click", () => saveFlag(true));

async function saveFlag(publish) {
  const payload = collectDraftPayload(publish);
  try {
    await api(`/api/projects/${state.currentProject}/flags/${encodeURIComponent(state.editingFlagKey)}`, {
      method: "PUT",
      body: payload
    });
    const detail = await api(`/api/projects/${state.currentProject}`);
    state.flags = detail.flags;
    renderFlags();
    renderEvalControls();
    alert(publish ? "已发布新版本（旧版本保持不变）" : "草稿已保存（尚未发布）");
  } catch (e) {
    alert("保存失败: " + e.message);
  }
}

// ---------- evaluation ----------

function resultClass(name) {
  if (name === "on") return "on";
  if (name === "off") return "off";
  return "variant";
}

$("evaluateBtn").addEventListener("click", async () => {
  let parsed;
  try {
    parsed = JSON.parse($("evalContext").value);
  } catch (e) {
    return alert("上下文 JSON 不合法: " + e.message);
  }
  const versionRaw = $("evalVersion").value;
  const body = {
    version: versionRaw ? Number(versionRaw) : null,
    save: $("saveRecord").checked,
    flagKeys: [$("evalFlag").value],
    contexts: [{ name: "手动输入", context: parsed }]
  };
  try {
    const records = await api(`/api/projects/${state.currentProject}/evaluate`, {
      method: "POST",
      body
    });
    renderEvalResult(records[0]);
  } catch (e) {
    alert("求值失败: " + e.message);
  }
});

function renderEvalResult(record) {
  const cls = resultClass(record.result);
  $("evalSummary").innerHTML = `
    <span class="pill ${cls}">${esc(record.result)}</span>
    开关 <code>${esc(record.flagKey)}</code>
    · 版本 <strong>v${record.version}</strong>
    · 结论类型 <code>${esc(record.trace.outcome)}</code>`;
  $("evalTrace").innerHTML = renderTraceTree(record.trace) + renderFieldReads(record.trace.fieldReads);
}

function renderTraceTree(trace) {
  const steps = trace.steps || [];
  const rendered = steps.map((step) => renderStepNode(step, true)).join("");
  return `<div class="trace-root"><h3 style="margin-top:4px">逐步求值轨迹</h3>${rendered}</div>`;
}

function renderStepNode(step, expandedDefault) {
  const type = step.type;
  let title = "";
  let tag = "";
  let children = "";

  if (type === "prerequisite") {
    title = `前置开关 <code>${esc(step.flagKey)}</code> v${step.version ?? "?"}：要求 ∈ [${
      esc((step.requiredAnyOf || []).join(", "))}]，实际 <code>${esc(step.observed)}</code>`;
    tag = step.passed
      ? `<span class="trace-tag tag-pass">通过</span>`
      : `<span class="trace-tag tag-fail">门控 → ${esc(step.served || "")}</span>`;
    if (step.nestedTrace) children = renderStepChildren(step.nestedTrace);
  } else if (type === "rule") {
    title = `规则 <code>${esc(step.ruleId)}</code>（${esc(step.ruleName || "")}）`;
    if (step.matched === false) {
      tag = `<span class="trace-tag tag-fail">未命中</span>`;
    } else if (step.rollout) {
      tag = `<span class="trace-tag tag-pass">命中 → ${esc(step.served)}</span>`;
    } else {
      tag = `<span class="trace-tag tag-pass">命中 → ${esc(step.served)}</span>`;
    }
    const conds = (step.conditions || []).map((c) => {
      const tag2 = c.passed
        ? `<span class="trace-tag tag-pass">true</span>`
        : `<span class="trace-tag tag-fail">false</span>`;
      return `<div class="trace-node" style="border-color:var(--line)">
        <div class="trace-kv">
          <code>${esc(c.field)}</code> ${esc(c.operator)}
          <code>${esc(c.expected === undefined ? "" : JSON.stringify(c.expected))}</code>
          ${tag2}
          <div class="muted">${esc(c.state)} · ${esc(c.reason || "")}</div>
        </div>
      </div>`;
    }).join("");
    children = `<div class="trace-children">${conds}${
      step.rollout ? renderRolloutNode(step.rollout) : ""
    }</div>`;
  } else if (type === "default") {
    title = `默认值 → <code>${esc(step.served)}</code>`;
    tag = `<span class="trace-tag tag-info">默认</span>`;
  }

  const hasChildren = !!children || !!step.error;
  const caret = hasChildren ? `<span class="caret">▾</span>` : `<span class="caret">·</span>`;
  const errorLine = step.error
    ? `<div class="trace-tag tag-fail">${esc(step.error)}: ${esc((step.cyclePath || []).join(" → "))}</div>`
    : "";
  return `<div class="trace-node ${esc(type)} ${expandedDefault ? "" : "collapsed"}">
    <div class="trace-line" onclick="toggleNode(this)">
      ${caret}${title}${tag}
    </div>
    ${errorLine}
    ${children}
  </div>`;
}

function renderStepChildren(nested) {
  const inner = (nested.steps || []).map((s) => renderStepNode(s, true)).join("");
  return `<div class="trace-children">
    <div class="muted" style="font-size:12px">↳ 前置开关自身轨迹（版本 v${nested.version}，结果 <code>${esc(nested.result)}</code>）</div>
    ${inner}
  </div>`;
}

function renderRolloutNode(rollout) {
  const clauses = (rollout.clauses || []).map((c) => {
    const inRange = c.containsBucket
      ? `<span class="trace-tag tag-pass">桶落在此区间</span>`
      : `<span class="trace-tag tag-info">未落入</span>`;
    return `<div class="trace-node rollout">
      <div class="trace-kv">
        <code>${esc(c.serve)}</code>：权重 ${c.weightBp} bp
        （区间 [${c.rangeStartBp}, ${c.rangeEndBp})） ${inRange}
      </div>
    </div>`;
  }).join("");
  return `<div class="trace-node rollout">
    <div class="trace-line" onclick="toggleNode(this)">
      <span class="caret">▾</span>百分比分流
      <span class="trace-tag ${rollout.servedByRollout ? "tag-pass" : "tag-fail"}">
        桶 ${rollout.bucketBp} / 10000
      </span>
    </div>
    <div class="trace-children">
      <div class="trace-kv">
        稳定身份字段 <code>${esc(rollout.stableIdField)}</code><br>
        salt <code>${esc(rollout.salt)}</code><br>
        分桶输入（敏感值只显示摘要）<code>${esc(rollout.hashInput)}</code><br>
        算法 <code>${esc(rollout.algorithm)}</code>
      </div>
      ${clauses}
    </div>
  </div>`;
}

function toggleNode(el) {
  const node = el.closest(".trace-node");
  if (!node.querySelector(".trace-children")) return;
  node.classList.toggle("collapsed");
  el.querySelector(".caret").textContent = node.classList.contains("collapsed") ? "▸" : "▾";
}

function renderFieldReads(reads) {
  if (!reads) return "";
  const keys = Object.keys(reads);
  if (!keys.length) return "";
  const rows = keys.map((key) => {
    const r = reads[key];
    let detail;
    if (r.state === "missing") {
      detail = `<span class="trace-tag tag-info">缺失</span>`;
    } else if (r.state === "null") {
      detail = `<span class="trace-tag tag-info">显式 null</span>`;
    } else if (r.sensitive) {
      detail = `<span class="trace-tag tag-fail">敏感，已脱敏</span>
        摘要 <code>${esc(r.digest || "")}</code>（类型 ${esc(r.type || "")}）`;
    } else {
      detail = `<code>${esc(JSON.stringify(r.value))}</code>`;
    }
    return `<div class="fr"><code>${esc(key)}</code>${detail}</div>`;
  }).join("");
  return `<h3>读取的字段（${keys.length}）</h3><div class="field-reads">${rows}</div>`;
}

// ---------- version comparison ----------

async function setupCompare() {
  $("compareFlag").innerHTML = state.flags
    .filter((f) => f.versions.length)
    .map((f) => `<option>${esc(f.key)}</option>`).join("");
  refreshCompareVersions();
  $("compareContextPicker").innerHTML = state.contexts.map((c) =>
    `<label class="inline" style="margin-right:12px">
      <input type="checkbox" class="cmp-ctx" value="${esc(c.id)}" checked>
      ${esc(c.name)}
    </label>`).join("") || `<span class="muted">先到“保存的上下文”页面添加几个上下文。</span>`;
}

function refreshCompareVersions() {
  const flag = state.flags.find((f) => f.key === $("compareFlag").value);
  const opts = (flag?.versions || []).map((v) =>
    `<option value="${v.version}">v${v.version} — ${esc(v.note || "")}</option>`).join("");
  $("compareFrom").innerHTML = opts;
  $("compareTo").innerHTML = opts;
  const versions = (flag?.versions || []);
  if (versions.length) {
    $("compareFrom").value = String(Math.max(1, versions.length - 1));
    $("compareTo").value = String(versions[versions.length - 1].version);
  }
}

$("compareFlag").addEventListener("change", refreshCompareVersions);

$("runCompareBtn").addEventListener("click", async () => {
  const chosen = [...document.querySelectorAll(".cmp-ctx:checked")]
    .map((cb) => state.contexts.find((c) => c.id === cb.value));
  if (!chosen.length) return alert("至少选择一个保存的上下文");
  try {
    const rows = await api(`/api/projects/${state.currentProject}/compare`, {
      method: "POST",
      body: {
        flagKeys: [$("compareFlag").value],
        fromVersion: Number($("compareFrom").value),
        toVersion: Number($("compareTo").value),
        contexts: chosen.map((c) => ({ name: c.name, context: c.context }))
      }
    });
    $("compareResults").innerHTML = `
      <table><thead><tr>
        <th>上下文</th><th>旧 v${rows[0]?.fromVersion}</th><th>新 v${rows[0]?.toVersion}</th>
        <th>是否变化</th><th>轨迹</th>
      </tr></thead><tbody>
      ${rows.map((r) => `
        <tr class="compare-row ${r.changed ? "changed" : ""}">
          <td>${esc(r.contextName)}</td>
          <td><span class="pill ${resultClass(r.fromResult)}">${esc(r.fromResult)}</span></td>
          <td><span class="pill ${resultClass(r.toResult)}">${esc(r.toResult)}</span></td>
          <td>${r.changed ? "✅ 变化" : "—"}</td>
          <td>
            <details><summary>旧轨迹</summary>${renderTraceTree(r.fromTrace)}</details>
            <details><summary>新轨迹</summary>${renderTraceTree(r.toTrace)}</details>
          </td>
        </tr>`).join("")}
      </tbody></table>`;
  } catch (e) {
    alert("对比失败: " + e.message);
  }
});

// ---------- history ----------

async function loadHistory() {
  const records = await api(`/api/records?projectId=${encodeURIComponent(state.currentProject)}`);
  const tbody = $("historyTable").querySelector("tbody");
  tbody.innerHTML = records.length ? records.map((r) => `
    <tr>
      <td>${new Date(r.createdAtMs).toLocaleString()}</td>
      <td><code>${esc(r.flagKey)}</code></td>
      <td>v${r.version}</td>
      <td>${esc(r.contextName)}</td>
      <td><span class="pill ${resultClass(r.result)}">${esc(r.result)}</span></td>
      <td><button data-replay="${esc(r.id)}">重放</button></td>
    </tr>`).join("")
    : `<tr><td colspan="6" class="muted">暂无记录。</td></tr>`;
  tbody.querySelectorAll("[data-replay]").forEach((b) =>
    b.addEventListener("click", () => replay(b.dataset.replay)));
}

async function replay(id) {
  const data = await api(`/api/records/${encodeURIComponent(id)}/replay`, { method: "POST" });
  const stored = data.record;
  const fresh = data.replay;
  $("replayPanel").innerHTML = `
    <h3>重放记录（${esc(stored.flagKey)}）</h3>
    <div class="summary">
      历史结果：<span class="pill ${resultClass(stored.result)}">${esc(stored.result)}</span>
      绑定版本 v${stored.version}；
      重放结果：<span class="pill ${resultClass(fresh.result)}">${esc(fresh.result)}</span>
      v${fresh.version}；
      ${data.resultMatches
        ? '<span class="trace-tag tag-pass">一致：历史轨迹未被后来的规则改变</span>'
        : '<span class="trace-tag tag-fail">不一致</span>'}
    </div>
    <details open><summary>历史保存时的轨迹</summary>${renderTraceTree(stored.trace)}</details>
    <details><summary>按绑定版本重新计算的轨迹</summary>${renderTraceTree(fresh.trace)}</details>`;
}

// ---------- export / import ----------

$("exportBtn").addEventListener("click", async () => {
  const data = await api("/api/export");
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "feature-flags-export.json";
  a.click();
  URL.revokeObjectURL(url);
});

$("importBtn").addEventListener("click", () => $("importFile").click());
$("importFile").addEventListener("change", async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  const replace = confirm("导入会覆盖同 id 数据。\n确定 = 替换全部；取消 = 按 id 合并。");
  const text = await file.text();
  let bundle;
  try {
    bundle = JSON.parse(text);
  } catch (err) {
    return alert("文件不是合法 JSON: " + err.message);
  }
  bundle.replace = replace;
  const summary = await api("/api/import", { method: "POST", body: bundle });
  alert(`导入完成：${summary.projects} 项目 / ${summary.flags} 开关 / ${summary.contexts} 上下文 / ${summary.records} 记录`);
  state.projects = await api("/api/projects");
  renderProjectSelect();
  if (state.projects.length) await selectProject(state.projects[0].id);
});

// ---------- static events needing late binding ----------

function bindStaticEvents() {
  // Delegated toggling for dynamically rendered trace nodes already uses
  // the global onclick handler name; nothing else required here.
}

window.toggleNode = toggleNode;

boot().catch((e) => alert("启动失败: " + e.message));
