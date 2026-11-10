// cockpit — the interactive column: compose a message, watch the turn
// run (live tool flow), answer approvals, read the transcript. The same
// engine Telegram rides; the session file is the shared history, so a
// session continued here is a session continued there.
import { get, getToken } from './api.js';
import { mdToNode } from './md.js';
import { relTime } from './time.js';
import { reloadTarget } from './paging.js';
import { bubble, loadTranscript, getLastUserInput } from './transcript.js';
import { micSupported, micToggle, captureAndSend } from './media.js';

const el = (id) => document.getElementById(id);

const state = {
  session: localStorage.getItem('theseus-cockpit-session') || 'web',
  attachments: [],
  jobId: localStorage.getItem('theseus-cockpit-job') || null,
  lastApprovalId: null,
  busySession: null, // the session our running job writes to
};

// one in-flight missing-job verification at a time (issue #35): a
// snapshot without our job triggers a job-route check, and overlapping
// checks would race each other's watchJob(null)
let verifyingGone = false;

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
let lastSessionRows = [];

function refreshSessions(rows) {
  const sel = el('cockpit-session');
  if (!sel) return;
  if (Array.isArray(rows)) lastSessionRows = rows;
  const list = Array.isArray(rows)
    ? rows
    : (lastSessionRows.length ? lastSessionRows : [{id: state.session}]);
  sel.replaceChildren();
  const opt = (id, label) => {
    const o = document.createElement('option');
    o.value = id;
    o.textContent = label;
    if (id === state.session) o.selected = true;
    sel.appendChild(o);
  };
  const known = new Set(list.map((r) => r.id));
  const sessionLabel = (r) => {
    const bits = [r.id];
    if (r.model) bits.push(r.model);
    if (r.updated) bits.push(relTime(r.updated));
    return bits.join(' · ');
  };
  const current = list.find((r) => r.id === state.session) || {id: state.session};
  opt(state.session, sessionLabel(current) + (known.has(state.session) ? '' : '  (new)'));
  for (const r of list) if (r.id !== state.session) opt(r.id, sessionLabel(r));
}

function pickSession(id) {
  state.session = id;
  localStorage.setItem('theseus-cockpit-session', id);
  refreshSessions();
  loadTranscript(state.session);
}

function newSession() {
  pickSession('web-' + Math.random().toString(16).slice(2, 10));
}



// ---------- attachments ----------

async function uploadFiles(files) {
  state.uploading = true;
  const attachButton = el('cockpit-attach');
  if (attachButton) attachButton.disabled = true;
  for (const f of files) {
    try {
      const buf = await f.arrayBuffer();
      const q = '?name=' + encodeURIComponent(f.name) +
                '&mime=' + encodeURIComponent(f.type || 'application/octet-stream');
      const desc = await put('/api/attachment' + q, buf);
      state.attachments.push({ id: desc['upload/id'], name: f.name });
    } catch (e) {
      const error = el('cockpit-error');
      if (error) {
        error.textContent = 'upload failed — ' + e.message;
        error.hidden = false;
      }
    }
  }
  state.uploading = false;
  if (attachButton) attachButton.disabled = false;
  renderAttachments();
}

function renderAttachments() {
  const box = el('cockpit-attached');
  if (!box) return;
  box.replaceChildren();
  for (const a of state.attachments) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = a.name;
    chip.title = 'click to remove';
    chip.setAttribute('aria-label', 'remove attachment ' + a.name);
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
  const icon = ev.type === 'call' ? '⚙️' : (ev.type === 'retry' ? '⏳' : (ev.ok ? '✅' : '❌'));
  if (ev.type === 'retry') {
    div.textContent = icon + ' retry ' + (ev.attempt || '?') +
      (ev.reason ? ' · ' + ev.reason : '');
    return div;
  }
  div.textContent = icon + ' ' + ev.tool + (ev.context ? ' · ' + ev.context : '');
  return div;
}

async function jobAction(id, verb) {
  try {
    const data = await post('/api/job/' + encodeURIComponent(id) + '/' + verb, {});
    if (verb === 'cancel' && data.canceled) el('cockpit-status').textContent = 'canceled';
    if (verb === 'cancel' && data['cancel-requested']) el('cockpit-status').textContent = 'cancel requested';
    if (verb === 'retry' && data['job/id']) {
      watchJob(data['job/id']);
      renderJob({ status: 'queued', events: [] });
      el('cockpit-status').textContent = 'requeued';
    }
  } catch (e) {
    const error = el('cockpit-error');
    if (error) {
      error.textContent = verb + ' failed — ' + e.message;
      error.hidden = false;
    }
  }
}

async function decide(id, decision) {
  try {
    await post('/api/approval', { id, decision });
  } catch (e) {
    // errors belong to the action that caused them, not the connection
    // line — the alert element the shell already reserves for this
    const error = el('cockpit-error');
    if (error) {
      error.textContent = 'approval failed — ' + e.message;
      error.hidden = false;
    }
  }
}

function elapsedLabel(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return total + 's';
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return minutes + 'm ' + (total % 60) + 's';
  return Math.floor(minutes / 60) + 'h ' + (minutes % 60) + 'm';
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
  const started = job['started-at'] || job['submitted-at'];
  const ended = job['finished-at'] || Date.now();
  const elapsed = started ? Math.max(0, ended - started) : 0;
  const steps = (job.events || []).length;
  head.textContent = job.status + (job['cancel-requested'] ? ' · cancel requested' : '') +
    ' · ' + elapsedLabel(elapsed) + ' · ' + steps + (steps === 1 ? ' step' : ' steps');
  box.appendChild(head);
  const flow = document.createElement('div');
  flow.className = 'job-flow';
  for (const ev of (job.events || [])) flow.appendChild(flowLine(ev));
  box.appendChild(flow);
  if (job.approval) {
    const isNewApproval = state.lastApprovalId !== job.approval.id;
    if (isNewApproval) state.lastApprovalId = job.approval.id;
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
      b.setAttribute('aria-label', dec + ' ' + job.approval.tool +
        (job.approval.context ? ' — ' + job.approval.context : ''));
      b.onclick = () => {
        [...bar.querySelectorAll('button')].forEach((btn) => { btn.disabled = true; });
        decide(job.approval.id, dec);
      };
      bar.appendChild(b);
    }
    if (isNewApproval) {
      const first = bar.querySelector('button');
      if (first) first.focus();
    }
    ap.appendChild(bar);
    box.appendChild(ap);
  } else {
    state.lastApprovalId = null;
  }
  if (job.status === 'failed' || job.status === 'interrupted' || job.status === 'canceled') {
    if (job.status !== 'canceled') {
      const err = document.createElement('div');
      err.className = 'job-error';
      err.textContent = job.error || 'failed';
      box.appendChild(err);
    }
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'job-retry';
    retry.textContent = '↻ retry';
    retry.setAttribute('aria-label', 'retry this message as a new turn');
    retry.onclick = () => { retry.disabled = true; jobAction(job['job/id'], 'retry'); };
    box.appendChild(retry);
  }
  if (job.artifacts && job.artifacts.length) {
    const chips = document.createElement('div');
    chips.className = 'job-artifacts';
    for (const a of job.artifacts) {
      const link = document.createElement('a');
      link.className = 'artifact-chip';
      link.href = '/api/artifact/' + encodeURIComponent(a['artifact/id']) +
        '?token=' + encodeURIComponent(getToken());
      link.textContent = '⬇ ' + a.name + ' (' + (a.bytes || 0) + 'B)';
      if (a.caption) link.title = a.caption;
      link.setAttribute('download', a.name);
      chips.appendChild(link);
    }
    box.appendChild(chips);
  }
  if (job.status === 'queued' || job.status === 'running') {
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'job-cancel';
    cancel.textContent = '✕ cancel';
    cancel.setAttribute('aria-label', 'cancel this turn');
    cancel.onclick = () => { cancel.disabled = true; jobAction(job['job/id'], 'cancel'); };
    box.appendChild(cancel);
  }
  box.scrollTop = pinned ? box.scrollHeight : offset;
}

// The job rides the push stream: the state event carries the job map,
// so watching a job is just remembering which one — now also across a
// reload (localStorage), so a refresh restores the live watch instead
// of orphaning a running turn.
function watchJob(id) {
  state.jobId = id;
  if (id) localStorage.setItem('theseus-cockpit-job', id);
  else localStorage.removeItem('theseus-cockpit-job');
}

function jobFinished(job) {
  watchJob(null);
  el('cockpit-status').textContent = job ? job.status : 'gone';
  state.busySession = null;
  loadTranscript(state.session);
  setTimeout(() => {
    const box = el('cockpit-job');
    if (box && !state.jobId) box.hidden = true;
  }, 5000);
}

async function send(opts) {
  const input = el('cockpit-text');
  const button = el('cockpit-send');
  const error = el('cockpit-error');
  const edit = Boolean(opts && opts.edit);
  const text = (input.value || '').trim();
  if (!text && state.attachments.length === 0) return;
  if (error) error.hidden = true;
  if (button) button.disabled = true;
  input.value = '';
  input.style.height = 'auto';
  const attachments = state.attachments.map((a) => a.id);
  state.attachments = [];
  renderAttachments();
  try {
    const job = await post('/api/turn', { session: state.session, text, attachments, edit });
    if (job.bypassed) {
      // a typed approval answered a waiting turn on the request thread —
      // no job exists; show the verdict where the transcript grows
      el('cockpit-status').textContent = 'approval ' + (job.decision || 'answered');
      const note = el('cockpit-job');
      if (note) {
        note.hidden = false;
        note.replaceChildren();
        const line = document.createElement('div');
        line.className = 'job-head done';
        line.textContent = 'approval ' + (job.decision || 'answered');
        note.appendChild(line);
        const reply = bubble(job.reply || '', 'out', 'theseus');
        note.appendChild(reply);
        setTimeout(() => { if (!state.jobId) note.hidden = true; }, 5000);
      }
      loadTranscript(state.session);
      return;
    }
    el('cockpit-status').textContent = 'running';
    state.busySession = state.session;
    renderJob({ status: 'queued', events: [] });
    watchJob(job['job/id']);
  } catch (e) {
    el('cockpit-status').textContent = 'send failed';
    if (error) {
      error.textContent = 'send failed — ' + e.message;
      error.hidden = false;
    }
    input.value = text;
  } finally {
    if (button) button.disabled = false;
  }
}

// The edit affordance (parity row 16): the last user input returns to
// the composer; the re-submit sends :edit, the engine drops the old
// turn and re-runs — one replacement, never a duplicate.
function editLast() {
  const input = el('cockpit-text');
  const last = getLastUserInput();
  if (!input || !last) return;
  input.value = last;
  input.focus();
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 160) + 'px';
  input.dataset.editing = 'last';
}

function submitFromComposer(e) {
  e.preventDefault();
  const input = el('cockpit-text');
  const edit = input && input.dataset.editing === 'last';
  if (input) delete input.dataset.editing;
  send({ edit });
}

// ---------- boot ----------

export function bootCockpit() {
  const form = el('cockpit-form');
  if (!form) return;
  form.addEventListener('submit', submitFromComposer);
  const input = el('cockpit-text');
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send({}); }
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 160) + 'px';
  });
  el('cockpit-new').addEventListener('click', newSession);
  el('cockpit-session').addEventListener('change', (e) => pickSession(e.target.value));
  el('cockpit-attach').addEventListener('click', () => el('cockpit-file').click());
  el('cockpit-file').addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length) uploadFiles([...e.target.files]);
    e.target.value = '';
  });
  // microphone: feature-detected — the button hides itself when the
  // browser cannot record. Tap starts, tap again stops+sends.
  const micBtn = el('cockpit-mic');
  if (micBtn) {
    if (micSupported()) {
      micBtn.hidden = false;
      micBtn.addEventListener('click', () => {
        micToggle(state.session, (job) => {
          el('cockpit-status').textContent = 'running';
          state.busySession = state.session;
          renderJob({ status: 'queued', events: [] });
          watchJob(job['job/id']);
        });
      });
    } else {
      micBtn.hidden = true;
    }
  }
  // camera: capture="environment" rides the same upload lane; photo
  // sends immediately like a Telegram camera shot.
  const camBtn = el('cockpit-camera');
  if (camBtn) {
    camBtn.hidden = false;
    camBtn.addEventListener('click', () => {
      captureAndSend(state.session, (job) => {
        el('cockpit-status').textContent = 'running';
        state.busySession = state.session;
        renderJob({ status: 'queued', events: [] });
        watchJob(job['job/id']);
      });
    });
  }
  // edit affordance: the last user input returns to the composer
  const editBtn = el('cockpit-edit');
  if (editBtn) editBtn.addEventListener('click', editLast);
  window.addEventListener('theseus:open-session', (e) => {
    if (e.detail && e.detail !== state.session) pickSession(e.detail);
  });
  loadTranscript(state.session);
  // Everything else rides the push: the session list arrives in every
  // state event, the job map lands here while a turn runs. A reloaded
  // page re-watches its remembered job; if the server restarted the job
  // shows :interrupted and the retry button takes over from there.
  if (state.jobId) renderJob({ status: 'queued', events: [] });
  let seenSessions = null;
  return {
    onState: (st) => {
      if (Array.isArray(st['session-list'])) {
        // cross-channel sync (parity row 13): a session that moved but
        // is NOT the one our running job writes to grew from the other
        // channel — reload its transcript if it is the open one.
        const moved = reloadTarget(seenSessions, st['session-list'], state.busySession);
        seenSessions = st['session-list'];
        if (moved === state.session && !state.jobId) loadTranscript(state.session);
        refreshSessions(st['session-list']);
      }
      if (!state.jobId) return;
      const job = st.jobs && st.jobs[state.jobId];
      if (job) {
        renderJob(job);
        if (job.status === 'done' || job.status === 'failed' ||
            job.status === 'interrupted' || job.status === 'canceled') jobFinished(job);
      } else if (!verifyingGone) {
        // A snapshot can predate the POST that created the job (issue #35:
        // a poll/push frame raced the send and the cockpit declared the
        // job "gone" before the engine ever ran it). Missing ≠ gone —
        // ask the job route; only a real 404 ends the watch.
        verifyingGone = true;
        const id = state.jobId;
        get('/api/job/' + encodeURIComponent(id))
          .then((j) => {
            if (!state.jobId || state.jobId !== id) return;
            renderJob(j);
            if (j.status === 'done' || j.status === 'failed' ||
                j.status === 'interrupted' || j.status === 'canceled') jobFinished(j);
          })
          .catch((e) => {
            if (state.jobId === id && e && e.status === 404) jobFinished(null);
          })
          .finally(() => { verifyingGone = false; });
      }
    },
  };
}
