// deck pip — Document Picture-in-Picture mini cockpit: the led, the
// running job, and the EAT clock in an always-on-top window that
// follows you out of the tab. Chromium-only API, feature-detected —
// the button simply doesn't exist where the platform doesn't.
// The pip window shares this page's JS realm, so updates are direct
// DOM writes from the same stream handler; no postMessage bridge.

import { eatClock } from './time.js';

let pipWindow = null;
let els = null;
let jobs = {};
let timer = null;

function available() { return 'documentPictureInPicture' in window; }

function copyStyles(win) {
  // all deck styles are <link> sheets (style-src 'self' in the CSP
  // blocks <style> elements in the inherited policy) — clone links
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

function elapsedLabel(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return total + 's';
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return minutes + 'm ' + (total % 60) + 's';
  return Math.floor(total / 60) + 'h ' + (minutes % 60) + 'm';
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
  els.job.textContent = job.status + ' · ' + elapsedLabel(elapsed) + ' · '
    + steps + (steps === 1 ? ' step' : ' steps');
}

function renderStatus() {
  if (!els) return;
  const state = document.getElementById('api-state');
  const led = document.querySelector('.status-led, .led');
  els.state.textContent = state ? state.textContent : '';
  els.led.className = 'pip-led ' + (led ? led.className.replace(/^led\s*/, '') : 'off');
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
    if (button) button.textContent = '⧉';
  });
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
  if (!available()) return; // stays hidden: the platform has no PiP document
  button.hidden = false;
  button.addEventListener('click', () => {
    if (pipWindow) { pipWindow.close(); return; }
    openPip(button).catch(() => { /* refused: stay in tab */ });
  });
}
