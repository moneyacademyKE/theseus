// cockpit — the interactive column: compose a message, watch the turn
// run (live tool flow), answer approvals, read the transcript. The same
// engine Telegram rides; the session file is the shared history, so a
// session continued here is a session continued there.
import { get, post, put, getToken } from './api.js';
import { mdToNode } from './md.js';
import { relTime } from './time.js';
import { reloadTarget } from './paging.js';
import { bubble, loadTranscript, getLastUserInput } from './transcript.js';
import { micSupported, micToggle, captureAndSend } from './media.js';
import { createMachine, registerMachine, projectAttr } from './fsm.js';

const el = (id) => document.getElementById(id);

const composerMachine = registerMachine(createMachine({
  name: 'composer',
  initial: 'idle',
  transitions: {
    idle: ['editing', 'sending'],
    editing: ['idle', 'sending'],
    sending: ['idle', 'error'],
    error: ['idle', 'editing', 'sending'],
  },
  onTransition: ({ to }) => projectAttr('#col-cockpit', 'data-compose-state', to),
}));

const jobMachine = registerMachine(createMachine({
  name: 'job',
  initial: 'idle',
  transitions: {
    idle: ['queued', 'running', 'waiting-approval', 'done', 'failed', 'interrupted', 'canceled'],
    queued: ['running', 'waiting-approval', 'done', 'failed', 'interrupted', 'canceled', 'idle'],
    running: ['waiting-approval', 'done', 'failed', 'interrupted', 'canceled', 'idle'],
    'waiting-approval': ['running', 'done', 'failed', 'interrupted', 'canceled', 'idle'],
    done: ['idle', 'queued'],
    failed: ['idle', 'queued'],
    interrupted: ['idle', 'queued'],
    canceled: ['idle', 'queued'],
  },
  onTransition: ({ to }) => projectAttr('#col-cockpit', 'data-job-state', to),
}));

const approvalMachine = registerMachine(createMachine({
  name: 'approval',
  initial: 'none',
  transitions: {
    none: ['pending'],
    pending: ['deciding', 'none'],
    deciding: ['resolved', 'pending', 'none'],
    resolved: ['none', 'pending'],
  },
  onTransition: ({ to }) => projectAttr('#cockpit-job', 'data-approval-state', to),
}));

const state = {
  session: localStorage.getItem('theseus-cockpit-session') || 'web',
  attachments: [],
  jobId: localStorage.getItem('theseus-cockpit-job') || null,
  lastApprovalId: null,
  decidingApprovalId: null,
  busySession: null, // the session our running job writes to
};

let verifyingGone = false;
let jobHideTimer = null;

function clearJobHideTimer() {
  if (jobHideTimer) {
    clearTimeout(jobHideTimer);
    jobHideTimer = null;
  }
}

export function getActiveSession() { return state.session; }
export function getActiveJobId() { return state.jobId; }

// ---------- sessions ----------

let lastSessionRows = [];

function refreshSessions(rows) {
  const sel = el('cockpit-session');
  if (!sel) return;
  if (Array.isArray(rows)) lastSessionRows = rows;
  const list = Array.isArray(rows)
    ? rows
    : (lastSessionRows.length ? lastSessionRows : [{ id: state.session }]);
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
  const current = list.find((r) => r.id === state.session) || { id: state.session };
  opt(state.session, sessionLabel(current) + (known.has(state.session) ? '' : '  (new)'));
  for (const r of list) if (r.id !== state.session) opt(r.id, sessionLabel(r));
}

function pickSession(id) {
  state.session = id;
  localStorage.setItem('theseus-cockpit-session', id);
  const input = el('cockpit-text');
  if (input && input.dataset.editing) {
    delete input.dataset.editing;
    composerMachine.force('idle');
  }
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
      clearJobHideTimer();
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
  state.decidingApprovalId = id;
  approvalMachine.force('deciding', { id, decision });
  try {
    await post('/api/approval', { id, decision });
    approvalMachine.force('resolved', { id, decision });
  } catch (e) {
    state.decidingApprovalId = null;
    approvalMachine.force('pending', { id, error: e && e.message });
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
  clearJobHideTimer();
  const pinned = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const offset = box.scrollTop;
  box.hidden = false;
  box.replaceChildren();
  const effectiveJobState = job.approval ? 'waiting-approval' : (job.status || 'queued');
  jobMachine.force(effectiveJobState, { id: job['job/id'] || state.jobId });

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
    const isDeciding = state.decidingApprovalId === job.approval.id;
    if (isNewApproval) {
      state.lastApprovalId = job.approval.id;
      if (!isDeciding) approvalMachine.force('pending', { id: job.approval.id });
    }
    const ap = document.createElement('div');
    ap.className = 'approval';
    const txt = document.createElement('div');
    txt.textContent = '🔒 ' + job.approval.tool +
      (job.approval.context ? ' · ' + job.approval.context : '') +
      (isDeciding ? ' · deciding…' : '');
    ap.appendChild(txt);
    const bar = document.createElement('div');
    bar.className = 'approval-bar';
    const choices = [['approve', 'approve'], ['approve rest', 'approve-rest'], ['deny', 'deny']];
    for (const [label, dec] of choices) {
      const b = document.createElement('button');
      b.textContent = label;
      b.className = dec === 'deny' ? 'deny' : 'approve';
      b.disabled = isDeciding;
      b.setAttribute('aria-label', dec + ' ' + job.approval.tool +
        (job.approval.context ? ' — ' + job.approval.context : ''));
      b.onclick = () => {
        if (state.decidingApprovalId === job.approval.id) return;
        [...bar.querySelectorAll('button')].forEach((btn) => { btn.disabled = true; });
        decide(job.approval.id, dec);
      };
      bar.appendChild(b);
    }
    if (isNewApproval && !isDeciding) {
      const first = bar.querySelector('button');
      if (first) first.focus();
    }
    ap.appendChild(bar);
    box.appendChild(ap);
  } else {
    state.lastApprovalId = null;
    state.decidingApprovalId = null;
    approvalMachine.force('none');
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

function watchJob(id) {
  state.jobId = id;
  if (id) localStorage.setItem('theseus-cockpit-job', id);
  else localStorage.removeItem('theseus-cockpit-job');
}

function jobFinished(job) {
  watchJob(null);
  state.decidingApprovalId = null;
  approvalMachine.force('none');
  jobMachine.force(job ? job.status : 'idle');
  el('cockpit-status').textContent = job ? job.status : 'gone';
  state.busySession = null;
  loadTranscript(state.session);
  clearJobHideTimer();
  jobHideTimer = setTimeout(() => {
    const box = el('cockpit-job');
    if (box && !state.jobId) {
      box.hidden = true;
      jobMachine.force('idle');
    }
    jobHideTimer = null;
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
  composerMachine.force('sending', { edit });
  input.value = '';
  input.style.height = 'auto';
  const savedAttachments = state.attachments.slice();
  const attachments = savedAttachments.map((a) => a.id);
  state.attachments = [];
  renderAttachments();
  try {
    const job = await post('/api/turn', { session: state.session, text, attachments, edit });
    composerMachine.force('idle');
    if (job.bypassed) {
      el('cockpit-status').textContent = 'approval ' + (job.decision || 'answered');
      const note = el('cockpit-job');
      if (note) {
        clearJobHideTimer();
        note.hidden = false;
        note.replaceChildren();
        const line = document.createElement('div');
        line.className = 'job-head done';
        line.textContent = 'approval ' + (job.decision || 'answered');
        note.appendChild(line);
        const reply = bubble(job.reply || '', 'out', 'theseus');
        note.appendChild(reply);
        jobHideTimer = setTimeout(() => {
          if (!state.jobId) note.hidden = true;
          jobHideTimer = null;
        }, 5000);
      }
      loadTranscript(state.session);
      return;
    }
    el('cockpit-status').textContent = 'running';
    state.busySession = state.session;
    renderJob({ status: 'queued', events: [] });
    watchJob(job['job/id']);
  } catch (e) {
    composerMachine.force('error', { error: e && e.message });
    el('cockpit-status').textContent = 'send failed';
    if (error) {
      error.textContent = 'send failed — ' + e.message;
      error.hidden = false;
    }
    input.value = text;
    if (edit) input.dataset.editing = 'last';
    // Restore attachments that were cleared optimistically so the user
    // never loses uploaded files on a transient network or 401 failure.
    if (savedAttachments.length) {
      state.attachments = savedAttachments.concat(state.attachments);
      renderAttachments();
    }
  } finally {
    if (button) button.disabled = false;
  }
}

function editLast() {
  const input = el('cockpit-text');
  const last = getLastUserInput();
  if (!input || !last) return;
  input.value = last;
  input.focus();
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 160) + 'px';
  input.dataset.editing = 'last';
  composerMachine.force('editing');
}

function cancelEdit() {
  const input = el('cockpit-text');
  if (!input || input.dataset.editing !== 'last') return;
  delete input.dataset.editing;
  input.value = '';
  input.style.height = 'auto';
  composerMachine.force('idle');
}

function submitFromComposer(e) {
  if (e) e.preventDefault();
  const input = el('cockpit-text');
  const edit = Boolean(input && input.dataset.editing === 'last');
  if (input) delete input.dataset.editing;
  send({ edit });
}

// ---------- boot ----------

export function bootCockpit() {
  const form = el('cockpit-form');
  if (!form) return;
  projectAttr('#col-cockpit', 'data-compose-state', composerMachine.state());
  projectAttr('#col-cockpit', 'data-job-state', jobMachine.state());
  form.addEventListener('submit', submitFromComposer);
  const input = el('cockpit-text');
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      submitFromComposer(e);
    } else if (e.key === 'Escape' && input.dataset.editing === 'last') {
      e.preventDefault();
      cancelEdit();
    }
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
  const editBtn = el('cockpit-edit');
  if (editBtn) editBtn.addEventListener('click', editLast);
  window.addEventListener('theseus:open-session', (e) => {
    if (e.detail && e.detail !== state.session) pickSession(e.detail);
  });
  loadTranscript(state.session);
  if (state.jobId) renderJob({ status: 'queued', events: [] });
  let seenSessions = null;
  return {
    onState: (st) => {
      if (Array.isArray(st['session-list'])) {
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
