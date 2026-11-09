// deck main — clock (EAT, always), login overlay, the single push stream.
import { getToken, login, stream, setApiState, showLogin } from './api.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem, refreshTimes } from './columns.js';
import { bootCockpit } from './cockpit.js';
import { bootUsage, refreshUsage } from './usage.js';
import { eatClock } from './time.js';

// One renderer per snapshot field. A throwing renderer skips its own
// frame and never kills the others — the same isolation the old
// per-column pollers had, kept for free.
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
// The focus trap used to live here — showModal() owns it now. What's
// left is the one thing the native element changes: Esc closes the
// dialog, so a locked deck needs a way back in that isn't an
// instant-reopen loop. Any click re-arms it, once.
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

// ---- boot the deck: one push channel, everything rides it ----
if (!getToken()) {
  showLogin();
  setApiState('login required');
} else {
  setApiState('connecting…');
  const cockpit = bootCockpit();
  // Per-column change detection: a push carries the WHOLE snapshot, so
  // without this one changed file re-renders all seven columns. The
  // server's per-second :now is excluded — the deck's clock is local.
  const last = {};
  const changed = (key, payload) => {
    const sig = JSON.stringify(key === 'status'
      ? { ...payload, now: undefined }
      : payload);
    if (last[key] === sig) return false;
    last[key] = sig;
    return true;
  };
  stream({
    onState: (st) => {
      for (const [key, render] of RENDER) {
        if (changed(key, st[key])) {
          try { render(st[key]); } catch (_) { /* one column, one bad frame */ }
          // the sparkline and the turns column read the same ledger —
          // a changed turns payload means the ledger grew
          if (key === 'turns') refreshUsage();
        }
      }
      if (changed('status', st.status)) {
        try { renderSystem(st.status); } catch (_) { /* ditto */ }
      }
      if (cockpit) { try { cockpit.onState(st); } catch (_) { /* ditto */ } }
    },
  });
  bootUsage();
}
