// deck pip — Document Picture-in-Picture mini cockpit: the led, the
// running job, and the EAT clock in an always-on-top window that
// follows you out of the tab. Reads connection state directly from
// state.js instead of scraping DOM text/classes.

import { eatClock } from './time.js';
import { connLabel, connState } from './state.js';
import { createMachine, registerMachine, projectAttr } from './fsm.js';

const pipMachine = registerMachine(createMachine({
  name: 'pip',
  initial: 'unavailable',
  transitions: {
    unavailable: ['closed'],
    closed: ['open'],
    open: ['closed'],
  },
  onTransition: ({ to }) => projectAttr('#pip-toggle', 'data-state', to),
}));

let pipWindow = null;
let els = null;
let jobs = {};
let timer = null;

function available() {
  return typeof window !== 'undefined' && 'documentPictureInPicture' in window;
}

function copyStyles(win) {
  for (const sheet of document.styleSheets) {
    if (sheet.href) {
      const link = win.document.createElement('link');
      link.rel = 'stylesheet';
      link.href = sheet.href;
      win.document.head.appendChild(link);
    }
  }
}

function node(win, tag, cls, text) {
  const n = win.document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

// Pure helper exported for Node unit tests (fixes the total / 60 hours bug).
export function formatElapsed(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return total + 's';
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return minutes + 'm ' + (total % 60) + 's';
  return Math.floor(total / 3600) + 'h ' + (minutes % 60) + 'm';
}

function runningJob() {
  const list = Object.values(jobs);
  return list.find((j) => j.status === 'running')
      || list.find((j) => j.status === 'queued')
      || null;
}

function renderJob() {
  if (!els) return;
  const job = runningJob();
  if (!job) { els.job.textContent = 'no active job'; els.job.className = 'pip-job idle'; return; }
  const started = job['started-at'] || job['submitted-at'];
  const ended = job['finished-at'] || Date.now();
  const elapsed = started ? Math.max(0, ended - started) : 0;
  const steps = (job.events || []).length;
  els.job.className = 'pip-job ' + job.status;
  els.job.textContent = job.status + ' · ' + formatElapsed(elapsed) + ' · '
    + steps + (steps === 1 ? ' step' : ' steps');
}

function renderStatus() {
  if (!els) return;
  const st = connState();
  els.state.textContent = connLabel() || '';
  els.led.className = 'pip-led ' + (st && st.led ? st.led : 'off');
}

function tick() {
  if (!els) return;
  els.clock.textContent = eatClock();
  renderJob();
  renderStatus();
}

async function openPip(button) {
  pipWindow = await documentPictureInPicture.requestWindow({ width: 340, height: 170 });
  copyStyles(pipWindow);
  pipWindow.document.title = 'theseus';
  const root = node(pipWindow, 'div', 'pip');
  const row = node(pipWindow, 'div', 'pip-row');
  els = {
    led: node(pipWindow, 'span', 'pip-led off'),
    state: node(pipWindow, 'span', 'pip-state', ''),
    job: node(pipWindow, 'div', 'pip-job idle', 'no active job'),
    clock: node(pipWindow, 'div', 'pip-clock', ''),
  };
  row.appendChild(els.led);
  row.appendChild(els.state);
  root.appendChild(row);
  root.appendChild(els.job);
  root.appendChild(els.clock);
  pipWindow.document.body.appendChild(root);
  pipWindow.addEventListener('pagehide', () => {
    clearInterval(timer);
    timer = null;
    pipWindow = null;
    els = null;
    pipMachine.force('closed');
    if (button) button.textContent = '⧉';
  });
  pipMachine.force('open');
  timer = setInterval(tick, 1000);
  tick();
  if (button) button.textContent = '⧉ on';
}

export function pipPush(st) {
  jobs = (st && st.jobs) || {};
  if (pipWindow) renderJob();
}

export function bootPip() {
  const button = document.getElementById('pip-toggle');
  if (!button) return;
  if (!available()) {
    projectAttr(button, 'data-state', 'unavailable');
    return;
  }
  pipMachine.force('closed');
  button.hidden = false;
  button.addEventListener('click', () => {
    if (pipWindow) { pipWindow.close(); return; }
    openPip(button).catch(() => { /* refused: stay in tab */ });
  });
}
