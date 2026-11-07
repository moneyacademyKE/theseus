// deck main — clock (EAT, always), column wiring, login overlay.
import { getToken, setToken, poll, setApiState } from './api.js';
import { renderTurns, renderTools, renderRsi, renderSessions,
         renderGoals, renderSchedules, renderSystem } from './columns.js';

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

// ---- login overlay ----
const form = document.getElementById('login-form');
if (form) {
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = document.getElementById('login-token');
    const err = document.getElementById('login-error');
    err.hidden = true;
    setToken(input.value.trim());
    try {
      const res = await fetch('/api/status', {
        headers: { Authorization: 'Bearer ' + getToken() },
      });
      if (res.status === 200) {
        location.reload();
      } else {
        err.hidden = false;
        localStorage.removeItem('theseus-deck-token');
      }
    } catch (_) {
      err.hidden = false;
    }
  });
}

// ---- boot the deck ----
if (!getToken()) {
  document.getElementById('login').hidden = false;
  setApiState('token required');
} else {
  setApiState('connecting…');
  for (const c of COLUMNS) {
    const body = document.querySelector(`[data-body="${c.key}"]`);
    if (!body) continue;
    const seconds = parseInt(body.dataset.interval || '30', 10);
    poll(body.dataset.src, seconds, c.render);
  }
}
