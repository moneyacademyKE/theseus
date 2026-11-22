// schedule management — browser CRUD over the shared registry (parity
// row 20): explicit CRUD state machine prevents double submits and
// projects #col-schedules[data-state] for UX and AX.
import { post, del } from './api.js';
import { createMachine, registerMachine, projectAttr } from './fsm.js';

const el = (id) => document.getElementById(id);

const schedulesMachine = registerMachine(createMachine({
  name: 'schedules',
  initial: 'idle',
  transitions: {
    idle: ['creating', 'deleting'],
    creating: ['idle', 'error'],
    deleting: ['idle', 'error'],
    error: ['idle', 'creating', 'deleting'],
  },
  onTransition: ({ to }) => projectAttr('#col-schedules', 'data-state', to),
}));

function esc(s) { return String(s == null ? '' : s); }

export function scheduleCount(n) {
  const badge = el('sched-count');
  if (badge) badge.textContent = n;
}

export async function createSchedule(form) {
  if (schedulesMachine.state() === 'creating') return false;
  const note = el('sched-note');
  const submitBtn = form.querySelector('button[type="submit"]');
  const id = esc(form['sched-id'].value).trim();
  const prompt = esc(form['sched-prompt'].value).trim();
  const cron = esc(form['sched-cron'].value).trim();
  if (!note) return false;
  note.hidden = true;
  schedulesMachine.force('creating', { id });
  if (submitBtn) submitBtn.disabled = true;
  try {
    await post('/api/schedule', { id, prompt, cron: cron || undefined });
    form['sched-id'].value = '';
    form['sched-prompt'].value = '';
    form['sched-cron'].value = '';
    note.textContent = 'created ' + id + ' — the scheduler picks it up on its next read';
    note.hidden = false;
    schedulesMachine.force('idle');
    return true;
  } catch (e) {
    schedulesMachine.force('error', { error: e && e.message });
    note.textContent = 'create failed — ' + e.message;
    note.hidden = false;
    return false;
  } finally {
    if (submitBtn) submitBtn.disabled = false;
  }
}

export async function deleteSchedule(id) {
  if (schedulesMachine.state() === 'deleting') return false;
  schedulesMachine.force('deleting', { id });
  try {
    await del('/api/schedule/' + encodeURIComponent(id));
    schedulesMachine.force('idle');
    return true;
  } catch (e) {
    schedulesMachine.force('error', { error: e && e.message });
    const note = el('sched-note');
    if (note) {
      note.textContent = 'delete failed — ' + e.message;
      note.hidden = false;
    }
    return false;
  }
}

export function bootSchedules() {
  const form = el('sched-form');
  if (!form) return;
  projectAttr('#col-schedules', 'data-state', schedulesMachine.state());
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    createSchedule(form);
  });
}
