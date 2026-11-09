// deck columns — one renderer per column. All content goes through
// textContent (el()); the two markdown surfaces (rsi digest, cockpit
// transcript) go through md.js — marked + DOMPurify, never raw
// innerHTML with data.

import { mdToNode } from './md.js';
import { relTime, eatFull } from './time.js';

export function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

function pill(kind, text) {
  const p = el('span', 'pill ' + kind, text);
  return p;
}

function timeNode(value) {
  const ms = typeof value === 'number' ? value : Date.parse(value);
  const iso = Number.isFinite(ms) ? new Date(ms).toISOString() : '';
  const node = el('time', 'ts', relTime(value));
  if (iso) {
    node.dateTime = iso;
    node.dataset.ts = iso;
    node.title = eatFull(value);
  }
  return node;
}

export function refreshTimes() {
  document.querySelectorAll('time[data-ts]').forEach((node) => {
    node.textContent = relTime(node.dataset.ts);
  });
}

function clearInto(colKey) {
  const body = document.querySelector(`[data-body="${colKey}"]`);
  const count = document.querySelector(`[data-count="${colKey}"]`);
  if (body) body.replaceChildren();
  return { body, count };
}

function done(colKey, n, shown) {
  const count = document.querySelector(`[data-count="${colKey}"]`);
  if (count) {
    const total = n != null ? n : 0;
    const label = typeof n === 'number'
      ? colKey + ': ' + total + (total === 1 ? ' item' : ' items')
      : (n ? colKey + ': ' + n : colKey);
    count.textContent = shown != null && shown < total
      ? shown + '+'
      : (n != null ? String(n) : '');
    count.title = label;
    count.setAttribute('aria-label', label);
  }
  const col = document.getElementById('col-' + colKey);
  if (col) {
    col.classList.remove('flash');
    void col.offsetWidth; // restart the animation
    col.classList.add('flash');
  }
}

function card(body, parts) {
  const c = el('article', 'card');
  const r1 = el('div', 'row1');
  if (parts.ts) r1.append(timeNode(parts.ts));
  if (parts.pill) r1.append(pill(parts.pill[0], parts.pill[1]));
  c.append(r1);
  if (parts.title) c.append(el('div', 'title', parts.title));
  if (parts.sub) c.append(el('div', 'sub', parts.sub));
  body.append(c);
}

function empty(body, text) {
  body.append(el('div', 'empty', text || 'nothing yet'));
}

// ---- flow: recent LLM turns ----
export function renderTurns(turns) {
  const { body } = clearInto('flow');
  if (!Array.isArray(turns) || !turns.length) return empty(body, 'no turns yet — the ledger starts with the first LLM call'), done('flow', 0);
  for (const t of turns.slice(0, 60)) {
    card(body, {
      ts: t.ts,
      pill: t.ok ? ['ok', 'ok'] : ['fail', 'fail'],
      title: t.model || 'unknown model',
      sub: `${t.session || ''} · ${t.tokens ?? '–'} tok`,
    });
  }
  done('flow', turns.length, Math.min(turns.length, 60));
}

// ---- tools: the funnel table ----
export function renderTools(rows) {
  const { body } = clearInto('tools');
  if (!Array.isArray(rows) || !rows.length) return empty(body, 'no tool events yet'), done('tools', 0);
  const table = el('table', 'stat-table');
  const caption = el('caption', 'sr-only', 'tool call outcomes');
  table.append(caption);
  const headRow = el('tr');
  for (const label of ['tool', 'call', 'ok', 'fail', 'deny']) {
    const th = el('th', label === 'tool' ? 'tool' : 'n', label);
    th.scope = 'col';
    headRow.append(th);
  }
  const thead = el('thead');
  thead.append(headRow);
  table.append(thead);
  const tbody = el('tbody');
  for (const r of rows) {
    const row = el('tr');
    row.append(el('td', 'tool', r.tool));
    row.append(el('td', 'n', String(r.calls)));
    row.append(el('td', 'n good', String(r.ok)));
    row.append(el('td', 'n ' + (r.fail > 0 ? 'bad' : ''), String(r.fail)));
    row.append(el('td', 'n ' + (r.denied > 0 ? 'warn' : ''), String(r.denied)));
    tbody.append(row);
  }
  table.append(tbody);
  body.append(table);
  done('tools', rows.length);
}

// ---- rsi: the digest, rendered as sanitized markdown ----

export function renderRsi(snap) {
  const { body } = clearInto('rsi');
  if (!snap || !snap.md) return empty(body, 'no digest yet — RSI writes the first one'), done('rsi', '');
  body.append(mdToNode(snap.md, 'digest'));
  const when = el('div', 'sub', snap.ms ? 'written ' + relTime(snap.ms) + ' ago' : '');
  when.style.padding = '8px 14px';
  body.append(when);
  done('rsi', relTime(snap.ms));
}

// ---- sessions ----
function sessionCard(body, s) {
  const c = el('button', 'card session-card');
  c.type = 'button';
  c.setAttribute('aria-label', 'open session ' + (s.id || 'session') + ' in the cockpit');
  const r1 = el('div', 'row1');
  if (s.updated) r1.append(timeNode(s.updated));
  c.append(r1);
  c.append(el('div', 'title', s.id || 'session'));
  if (s.summary) c.append(el('div', 'sub', s.summary));
  c.addEventListener('click', () => {
    window.dispatchEvent(new CustomEvent('theseus:open-session', {detail: s.id}));
  });
  body.append(c);
}

export function renderSessions(rows) {
  const { body } = clearInto('sessions');
  if (!Array.isArray(rows) || !rows.length) return empty(body, 'no sessions yet — say something in the cockpit'), done('sessions', 0);
  for (const s of rows.slice(0, 30)) sessionCard(body, s);
  done('sessions', rows.length, Math.min(rows.length, 30));
}

// ---- goals ----
export function renderGoals(rows) {
  const { body } = clearInto('goals');
  if (!Array.isArray(rows) || !rows.length) return empty(body, 'no goal workspaces yet'), done('goals', 0);
  for (const g of rows) {
    card(body, {
      ts: g.updated || '',
      pill: g.active ? ['ok', 'live'] : ['idle', 'idle'],
      title: g.name || g.dir,
      sub: g.last || '',
    });
  }
  done('goals', rows.length);
}

// ---- schedules ----
export function renderSchedules(rows) {
  const { body } = clearInto('schedules');
  if (!Array.isArray(rows) || !rows.length) return empty(body, 'no schedule runs yet'), done('schedules', 0);
  for (const r of rows.slice(0, 40)) {
    card(body, {
      ts: r.at,
      pill: r.status === 'ok' ? ['ok', 'ok'] : ['fail', r.status || 'fail'],
      title: r.id || 'schedule',
      sub: r.final || '',
    });
  }
  done('schedules', rows.length, Math.min(rows.length, 40));
}

// ---- system ----
export function renderSystem(st) {
  const { body } = clearInto('system');
  if (!st) return empty(body, 'no status snapshot'), done('system', '');
  const list = el('dl', 'system-list');
  body.append(list);
  const kv = (k, v, cls) => {
    const row = el('div', 'kv');
    row.append(el('dt', 'k', k), el('dd', 'v' + (cls ? ' ' + cls : ''), v));
    list.append(row);
  };
  kv('poller', st.poller && st.poller.alive ? 'up (' + (st.poller.pid || '?') + ')' : 'DOWN',
     st.poller && st.poller.alive ? 'good' : 'bad');
  kv('events', String(st.events));
  kv('tool events', String(st.tool_events));
  kv('last event', st.last_event ? relTime(st.last_event) + ' ago' : '—');
  kv('digest', st.digest_ms ? relTime(st.digest_ms) + ' ago' : '—');
  kv('schedules', String(st.schedules));
  kv('goals', String(st.goal_count));
  kv('server time', st.now ? eatFull(st.now) : '—');
  done('system', '');
}
