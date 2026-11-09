// cockpit — the interactive column: compose a message, watch the turn
// run (live tool flow), answer approvals, read the transcript. The same
// engine Telegram rides; the session file is the shared history, so a
// session continued here is a session continued there.
import { get, getToken, setApiState } from './api.js';
import { mdToNode } from './md.js';

const el = (id) => document.getElementById(id);

const state = {
  session: localStorage.getItem('theseus-cockpit-session') || 'web',
  attachments: [],
  jobId: null,
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

// The picker rides the push: the stream carries session-list with
// every state event, so there is nothing left to poll.
function refreshSessions(rows) {
  const sel = el('cockpit-session');
  if (!sel || !Array.isArray(rows)) return;
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

// Transcripts are markdown on the wire (assistant finals especially) —
// mdToNode runs marked + DOMPurify, and degrades to plain text if the
// vendored libs somehow failed to load.
function bubble(text, cls) {
  return mdToNode(text, 'bubble ' + cls);
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
  // Scroll pinning across the rebuild: a turn re-renders this box on
  // every push (roughly per second). If the user was pinned near the
  // bottom, follow the new content; if they scrolled up to read an
  // earlier tool call, keep their exact offset instead of yanking
  // them back down.
  const pinned = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const offset = box.scrollTop;
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
  box.scrollTop = pinned ? box.scrollHeight : offset;
}

// The job rides the push stream: the state event carries the job map,
// so watching a job is just remembering which one. The old 1.5s ticker
// is gone — the broadcaster pushes within a second of any change.
function watchJob(id) {
  state.jobId = id;
}

function jobFinished(job) {
  state.jobId = null;
  el('cockpit-status').textContent = job ? job.status : 'gone';
  loadTranscript();
  setTimeout(() => {
    const box = el('cockpit-job');
    if (box && !state.jobId) box.hidden = true;
  }, 5000);
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
  loadTranscript();
  // Everything else rides the push: the session list arrives in every
  // state event, the job map lands here while a turn runs.
  return {
    onState: (st) => {
      if (Array.isArray(st['session-list'])) refreshSessions(st['session-list']);
      if (!state.jobId) return;
      const job = st.jobs && st.jobs[state.jobId];
      if (job) {
        renderJob(job);
        if (job.status === 'done' || job.status === 'failed') jobFinished(job);
      } else {
        jobFinished(null); // aged out of the push window, or a restart
      }
    },
  };
}
