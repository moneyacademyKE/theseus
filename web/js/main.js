// deck main — clock (EAT, always), login & rotate dialogs, single push stream,
// and the read-only AX introspection surface (window.__THESEUS_DECK__).
import { getToken, login, stream, showLogin, logout, rotatePassword } from './api.js';
import { setConn, connLabel, getRole, applyRoleUi, setAuthState } from './state.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem, refreshTimes } from './columns.js';
import { bootCockpit, getActiveSession, getActiveJobId } from './cockpit.js';
import { bootSearch } from './search.js';
import { bootPip, pipPush } from './pip.js';
import { bootUsage, refreshUsage } from './usage.js';
import { bootSchedules } from './schedules.js';
import { eatClock } from './time.js';
import { bootLayout, reapplyFilter } from './layout.js';
import { installDeckInspector, createMachine, registerMachine, projectAttr } from './fsm.js';

bootLayout();

const rotateMachine = registerMachine(createMachine({
  name: 'passwordRotate',
  initial: 'idle',
  transitions: {
    idle: ['open'],
    open: ['rotating', 'idle'],
    rotating: ['done', 'error'],
    done: ['idle', 'open'],
    error: ['rotating', 'idle'],
  },
  onTransition: ({ to }) => projectAttr('#rotate-dialog', 'data-state', to),
}));

installDeckInspector(window, {
  conn: connLabel,
  role: getRole,
  session: getActiveSession,
  jobId: getActiveJobId,
});

// One renderer per snapshot field. A throwing renderer skips its own
// frame and never kills the others.
const RENDER = [
  ['turns', renderTurns],
  ['tools', renderTools],
  ['rsi', renderRsi],
  ['sessions', renderSessions],
  ['goals', renderGoals],
  ['schedules', renderSchedules],
];

// ---- clock: EAT, full stop ----
function tickClock() {
  const el = document.getElementById('clock');
  if (el) el.textContent = eatClock();
}
tickClock();
setInterval(tickClock, 1000);
setInterval(refreshTimes, 30000);

// ---- login dialog: the master password buys a session token ----
const loginDialog = document.getElementById('login');
if (loginDialog) {
  loginDialog.addEventListener('close', () => {
    if (getToken()) return;
    document.body.addEventListener('click', () => showLogin(), { once: true });
  });
}
const form = document.getElementById('login-form');
if (form) {
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = document.getElementById('login-pass');
    const err = document.getElementById('login-error');
    const button = form.querySelector('button[type="submit"]');
    err.hidden = true;
    if (button) {
      button.disabled = true;
      button.textContent = 'unlocking…';
    }
    try {
      await login(input.value);
      location.reload();
    } catch (ex) {
      if (ex.code === 'rate-limited') {
        const waitMs = Math.max(0, Number(ex.retryMs) || 0);
        const waitSec = Math.ceil(waitMs / 1000);
        err.textContent = waitSec > 0
          ? 'too many attempts — try again in ' + waitSec + 's'
          : 'too many attempts — try again later';
        err.hidden = false;
        if (button && waitMs > 0) {
          setTimeout(() => {
            button.disabled = false;
            button.textContent = 'unlock';
          }, waitMs);
        } else if (button) {
          button.disabled = false;
          button.textContent = 'unlock';
        }
      } else {
        err.textContent =
          ex.code === 'not-set'
            ? 'No master password set yet — on the host: bb scripts/set_web_password.bb'
            : ex.code === 'bad-password'
              ? 'wrong password'
              : 'login failed';
        err.hidden = false;
        if (button) {
          button.disabled = false;
          button.textContent = 'unlock';
        }
      }
      input.select();
    }
  });
}

// ---- non-blocking password rotation dialog (replaces prompt/alert) ----
function wireRotateDialog(triggerBtn) {
  const dialog = document.getElementById('rotate-dialog');
  const rotateForm = document.getElementById('rotate-form');
  const currentIn = document.getElementById('rotate-current');
  const nextIn = document.getElementById('rotate-next');
  const confirmIn = document.getElementById('rotate-confirm');
  const statusEl = document.getElementById('rotate-status');
  const submitBtn = document.getElementById('rotate-submit');
  const cancelBtn = document.getElementById('rotate-cancel');
  if (!dialog || !rotateForm) return;

  const resetFields = () => {
    if (currentIn) currentIn.value = '';
    if (nextIn) nextIn.value = '';
    if (confirmIn) confirmIn.value = '';
    if (statusEl) { statusEl.textContent = ''; statusEl.hidden = true; }
    if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = 'rotate'; }
  };

  dialog.addEventListener('close', () => {
    resetFields();
    rotateMachine.force('idle');
  });
  if (cancelBtn) cancelBtn.addEventListener('click', () => dialog.close());
  triggerBtn.addEventListener('click', () => {
    resetFields();
    rotateMachine.force('open');
    if (!dialog.open) dialog.showModal();
    if (currentIn) currentIn.focus();
  });
  rotateForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const cur = currentIn ? currentIn.value : '';
    const nxt = nextIn ? nextIn.value : '';
    const cfm = confirmIn ? confirmIn.value : '';
    if (statusEl) statusEl.hidden = true;
    if (!cur || !nxt) return;
    if (nxt !== cfm) {
      rotateMachine.force('error', { reason: 'mismatch' });
      if (statusEl) { statusEl.textContent = 'new passwords do not match'; statusEl.hidden = false; }
      return;
    }
    rotateMachine.force('rotating');
    if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = 'rotating…'; }
    try {
      await rotatePassword(cur, nxt);
      rotateMachine.force('done');
      if (statusEl) {
        statusEl.textContent = 'password rotated — other sessions were logged out';
        statusEl.hidden = false;
      }
      setTimeout(() => { if (dialog.open) dialog.close(); }, 1200);
    } catch (ex) {
      rotateMachine.force('error', { error: ex && ex.message });
      if (statusEl) { statusEl.textContent = 'rotation failed — ' + (ex && ex.message || 'error'); statusEl.hidden = false; }
      if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = 'rotate'; }
    }
  });
}

// ---- boot the deck: one push channel, everything rides it ----
if (!getToken()) {
  setAuthState('locked', { reason: 'no-token' });
  showLogin();
  setConn('login required');
} else {
  setAuthState(getRole(), { reason: 'stored-token' });
  setConn('connecting…');
  const cockpit = bootCockpit();
  bootSchedules();
  const lo = document.getElementById('cockpit-logout');
  const ro = document.getElementById('cockpit-rotate');
  if (lo) {
    lo.hidden = false;
    lo.addEventListener('click', async () => { await logout(); location.reload(); });
  }
  if (ro) {
    ro.hidden = false;
    wireRotateDialog(ro);
  }
  applyRoleUi();

  const last = {};
  const changed = (key, payload) => {
    const sig = JSON.stringify(key === 'status'
      ? { ...payload, now: undefined }
      : payload);
    if (last[key] === sig) return false;
    last[key] = sig;
    return true;
  };
  const applyState = (st) => {
    let anyColumnChanged = false;
    for (const [key, render] of RENDER) {
      if (changed(key, st[key])) {
        anyColumnChanged = true;
        try { render(st[key]); } catch (_) { /* one column, one bad frame */ }
        if (key === 'turns') refreshUsage();
      }
    }
    if (changed('status', st.status)) {
      anyColumnChanged = true;
      try { renderSystem(st.status); } catch (_) { /* ditto */ }
    }
    if (anyColumnChanged) reapplyFilter();
    if (cockpit) { try { cockpit.onState(st); } catch (_) { /* ditto */ } }
    try { pipPush(st); } catch (_) { /* mini cockpit never blocks the deck */ }
  };

  const SNAP_KEY = 'theseus-deck-snapshot';
  try {
    const cached = JSON.parse(localStorage.getItem(SNAP_KEY) || 'null');
    if (cached && typeof cached === 'object') {
      setConn('cached');
      applyState(cached);
    }
  } catch (_) { /* corrupt cache — the stream paints anyway */ }
  stream({
    onState: (st) => {
      applyState(st);
      try { localStorage.setItem(SNAP_KEY, JSON.stringify(st)); } catch (_) { /* stale cache, no crime */ }
    },
  });
  bootSearch();
  bootPip();
  bootUsage();
}
