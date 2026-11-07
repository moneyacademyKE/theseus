// deck main — clock (EAT, always), column wiring, login overlay, cockpit.
import { getToken, login, poll, setApiState } from './api.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem } from './columns.js';
import { bootCockpit } from './cockpit.js';

const COLUMNS = [
  { key: 'flow',      render: renderTurns },
  { key: 'tools',     render: renderTools },
  { key: 'rsi',       render: renderRsi },
  { key: 'sessions',  render: renderSessions },
  { key: 'goals',     render: renderGoals },
  { key: 'schedules', render: renderSchedules },
  { key: 'system',    render: renderSystem },
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

// ---- boot the deck ----
if (!getToken()) {
  document.getElementById('login').hidden = false;
  setApiState('login required');
} else {
  setApiState('connecting…');
  bootCockpit();
  for (const c of COLUMNS) {
    const body = document.querySelector(`[data-body="${c.key}"]`);
    if (!body) continue;
    const seconds = parseInt(body.dataset.interval || '30', 10);
    poll(body.dataset.src, seconds, c.render);
  }
}
