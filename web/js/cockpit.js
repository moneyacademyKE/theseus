// cockpit — the interactive column: compose a message, watch the turn
// run (live tool flow), answer approvals, read the transcript. The same
// engine Telegram rides; the session file is the shared history, so a
// session continued here is a session continued there.
import { get, getToken, setApiState } from './api.js';

const el = (id) => document.getElementById(id);

const state = {
  session: localStorage.getItem('theseus-cockpit-session') || 'web',
  attachments: [],
  jobId: null,
  timer: null,
};

// ---------- authed non-GET helpers ----------

async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
    body: JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

async function put(path, body) {
  const res = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream', Authorization: 'Bearer ' + getToken() },
    body,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

// ---------- sessions ----------

async function refreshSessions() {
  const sel = el('cockpit-session');
  if (!sel) return;
  try {
    const rows = await get('/api/session-list');
    sel.innerHTML = '';
    const opt = (id, label) => {
      const o = document.createElement('option');
      o.value = id;
      o.textContent = label;
      if (id === state.session) o.selected = true;
      sel.appendChild(o);
    };
    const known = new Set(rows.map((r) => r.id));
    opt(state.session, state.session + (known.has(state.session) ? '' : '  (new)'));
    for (const r of rows) if (r.id !== state.session) opt(r.id, r.id);
  } catch (e) { /* picker failure is cosmetic — the lane still works */ }
}

function pickSession(id) {
  state.session = id;
  localStorage.setItem('theseus-cockpit-session', id);
  refreshSessions();
  loadTranscript();
}

function newSession() {
  pickSession('web-' + Math.random().toString(16).slice(2, 10));
}

// ---------- transcript ----------

function bubble(text, cls) {
  const div = document.createElement('div');
  div.className = 'bubble ' + cls;
  div.textContent = text || '';
  return div;
}

async function loadTranscript() {
  const box = el('cockpit-transcript');
  if (!box) return;
  box.innerHTML = '';
  try {
    const rows = await get('/api/transcript?id=' + encodeURIComponent(state.session));
    if (!rows.length) box.appendChild(bubble('no history yet — say something', 'dim'));
    for (const t of rows) {
      box.appendChild(bubble(t.input, 'in'));
      box.appendChild(bubble(t.final, t.ok ? 'out' : 'out err'));
    }
  } catch (e) {
    box.appendChild(bubble('transcript unavailable', 'dim'));
  }
  box.scrollTop = box.scrollHeight;
}

// ---------- attachments ----------

async function uploadFiles(files) {
  for (const f of files) {
    try {
      const buf = await f.arrayBuffer();
      const q = '?name=' + encodeURIComponent(f.name) +
                '&mime=' + encodeURIComponent(f.type || 'application/octet-stream');
      const desc = await put('/api/attachment' + q, buf);
      state.attachments.push({ id: desc['upload/id'], name: f.name });
    } catch (e) {
      setApiState('upload failed: ' + e.message);
    }
  }
  renderAttachments();
}

function renderAttachments() {
  const box = el('cockpit-attached');
  if (!box) return;
  box.innerHTML = '';
  for (const a of state.attachments) {
    const chip = document.createElement('span');
    chip.className = 'chip';
    chip.textContent = a.name;
    chip.title = 'click to remove';
    chip.onclick = () => {
      state.attachments = state.attachments.filter((x) => x.id !== a.id);
      renderAttachments();
    };
    box.appendChild(chip);
  }
}

// ---------- job + live flow ----------

function flowLine(ev) {
  const div = document.createElement('div');
  div.className = 'flow ' + ev.type;
  const icon = ev.type === 'call' ? '⚙️' : (ev.ok ? '✅' : '❌');
  div.textContent = icon + ' ' + ev.tool + (ev.context ? ' · ' + ev.context : '');
  return div;
}

async function decide(id, decision) {
  try {
    await post('/api/approval', { id, decision });
  } catch (e) {
    setApiState('approval failed: ' + e.message);
  }
}

function renderJob(job) {
  const box = el('cockpit-job');
  if (!box) return;
  box.hidden = false;
  box.innerHTML = '';
  const head = document.createElement('div');
  head.className = 'job-head ' + job.status;
  head.textContent = job.status === 'queued'
    ? 'queued — one turn at a time on this lane'
    : job.status;
  box.appendChild(head);
  const flow = document.createElement('div');
  flow.className = 'job-flow';
  for (const ev of (job.events || [])) flow.appendChild(flowLine(ev));
  box.appendChild(flow);
  if (job.approval) {
    const ap = document.createElement('div');
    ap.className = 'approval';
    const txt = document.createElement('div');
    txt.textContent = '🔒 ' + job.approval.tool +
      (job.approval.context ? ' · ' + job.approval.context : '');
    ap.appendChild(txt);
    const bar = document.createElement('div');
    bar.className = 'approval-bar';
    const choices = [['approve', 'approve'], ['approve rest', 'approve-rest'], ['deny', 'deny']];
    for (const [label, dec] of choices) {
      const b = document.createElement('button');
      b.textContent = label;
      b.className = dec === 'deny' ? 'deny' : 'approve';
      b.onclick = () => decide(job.approval.id, dec);
      bar.appendChild(b);
    }
    ap.appendChild(bar);
    box.appendChild(ap);
  }
  if (job.status === 'failed') {
    const err = document.createElement('div');
    err.className = 'job-error';
    err.textContent = job.error || 'failed';
    box.appendChild(err);
  }
}

function stopPoll() {
  if (state.timer) { clearInterval(state.timer); state.timer = null; }
}

function watchJob(id) {
  stopPoll();
  state.jobId = id;
  state.timer = setInterval(async () => {
    let job = null;
    try {
      job = await get('/api/job/' + encodeURIComponent(id));
    } catch (e) {
      stopPoll();
      state.jobId = null;
      return;
    }
    renderJob(job);
    if (job.status === 'done' || job.status === 'failed') {
      stopPoll();
      state.jobId = null;
      el('cockpit-status').textContent = job.status;
      loadTranscript();
      refreshSessions();
      setTimeout(() => {
        const box = el('cockpit-job');
        if (box && !state.jobId) box.hidden = true;
      }, 5000);
    }
  }, 1500);
}

async function send() {
  const input = el('cockpit-text');
  const text = (input.value || '').trim();
  if (!text && state.attachments.length === 0) return;
  input.value = '';
  const attachments = state.attachments.map((a) => a.id);
  state.attachments = [];
  renderAttachments();
  try {
    const job = await post('/api/turn', { session: state.session, text, attachments });
    el('cockpit-status').textContent = 'running';
    renderJob({ status: 'queued', events: [] });
    watchJob(job['job/id']);
  } catch (e) {
    el('cockpit-status').textContent = 'send failed';
    setApiState('turn submit failed: ' + e.message);
    input.value = text;
  }
}

// ---------- boot ----------

export function bootCockpit() {
  const form = el('cockpit-form');
  if (!form) return;
  form.addEventListener('submit', (e) => { e.preventDefault(); send(); });
  const input = el('cockpit-text');
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  });
  el('cockpit-new').addEventListener('click', newSession);
  el('cockpit-session').addEventListener('change', (e) => pickSession(e.target.value));
  el('cockpit-attach').addEventListener('click', () => el('cockpit-file').click());
  el('cockpit-file').addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length) uploadFiles([...e.target.files]);
    e.target.value = '';
  });
  refreshSessions();
  loadTranscript();
  setInterval(refreshSessions, 30000);
}
