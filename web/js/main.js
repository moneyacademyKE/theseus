// deck main — clock (EAT, always), login overlay, the single push stream.
import { getToken, login, stream, setApiState, showLogin } from './api.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem } from './columns.js';
import { bootCockpit } from './cockpit.js';
import { bootUsage, refreshUsage } from './usage.js';

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
const fmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Nairobi',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false,
});
function tickClock() {
  const el = document.getElementById('clock');
  if (el) el.textContent = fmt.format(new Date()) + ' EAT';
}
tickClock();
setInterval(tickClock, 1000);

// ---- login overlay: the master password buys a session token ----
const loginDialog = document.getElementById('login');
if (loginDialog) {
  loginDialog.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return;
    const focusables = [...loginDialog.querySelectorAll('input, button')]
      .filter((el) => !el.disabled);
    if (focusables.length < 2) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });
}
const form = document.getElementById('login-form');
if (form) {
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = document.getElementById('login-pass');
    const err = document.getElementById('login-error');
    err.hidden = true;
    try {
      await login(input.value);
      location.reload();
    } catch (ex) {
      err.textContent =
        ex.code === 'not-set'
          ? 'No master password set yet — on the host: bb scripts/set_web_password.bb'
          : ex.code === 'bad-password'
            ? 'wrong password'
            : 'login failed';
      err.hidden = false;
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
