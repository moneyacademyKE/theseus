// schedule management — browser CRUD over the shared registry (parity
// row 20): a create form (id, prompt, cron) and a delete button per
// run card's schedule. Rides the same api.js post.
import { post, del } from './api.js';

const el = (id) => document.getElementById(id);

function esc(s) { return String(s == null ? '' : s); }

export function scheduleCount(n) {
  const badge = el('sched-count');
  if (badge) badge.textContent = n;
}

// Create: validates the same three fields the server does; errors land
// in the form's note line, never a dialog.
export async function createSchedule(form) {
  const note = el('sched-note');
  const id = esc(form['sched-id'].value).trim();
  const prompt = esc(form['sched-prompt'].value).trim();
  const cron = esc(form['sched-cron'].value).trim();
  if (!note) return false;
  note.hidden = true;
  try {
    await post('/api/schedule', { id, prompt, cron: cron || undefined });
    form['sched-id'].value = '';
    form['sched-prompt'].value = '';
    form['sched-cron'].value = '';
    note.textContent = 'created ' + id + ' — the scheduler picks it up on its next read';
    note.hidden = false;
    return true;
  } catch (e) {
    note.textContent = 'create failed — ' + e.message;
    note.hidden = false;
    return false;
  }
}

export async function deleteSchedule(id) {
  try {
    await del('/api/schedule/' + encodeURIComponent(id));
    return true;
  } catch (e) {
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
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    createSchedule(form);
  });
}
