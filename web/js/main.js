// deck main — clock (EAT, always), login overlay, the single push stream.
import { getToken, login, stream, showLogin } from './api.js';
import { setConn } from './state.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem, refreshTimes } from './columns.js';
import { bootCockpit } from './cockpit.js';
import { bootSearch } from './search.js';
import { bootPip, pipPush } from './pip.js';
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
  setConn('login required');
} else {
  setConn('connecting…');
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
  const applyState = (st) => {
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
    try { pipPush(st); } catch (_) { /* mini cockpit never blocks the deck */ }
  };
  // Instant paint: the last snapshot is cached locally, so a reload
  // shows the deck immediately instead of a blank flash. The stream
  // corrects it — a fresh channel gets current state within a second
  // (per-channel fingerprints), so the cache only has to be close.
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
      // remember it for the next instant paint; quota loss is fine
      try { localStorage.setItem(SNAP_KEY, JSON.stringify(st)); } catch (_) { /* stale cache, no crime */ }
    },
  });
  bootSearch();
  bootPip();
  bootUsage();
}
