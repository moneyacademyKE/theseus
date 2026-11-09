// deck api — session-gated fetch, login, and the single push stream.
// The stored credential is a session token minted by POST /api/login
// (master password); it never equals the password itself.
const TOKEN_KEY = 'theseus-deck-token';

export function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }

export function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }

export async function login(password) {
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (res.status === 200) {
    const session = await res.json();
    setToken(session.token);
    return session;
  }
  const err = await res.json().catch(() => ({}));
  throw Object.assign(new Error('login failed'), {
    code: err.error || 'unknown',
    hint: err.hint || '',
  });
}

export function showLogin() {
  const el = document.getElementById('login');
  if (el) el.hidden = false;
}

export function setApiState(text) {
  const el = document.getElementById('api-state');
  if (el) el.textContent = text;
}

export function setLed(state) {
  const el = document.getElementById('live');
  if (!el) return;
  el.classList.remove('on', 'err');
  if (state === 'on') el.classList.add('on');
  if (state === 'err') el.classList.add('err');
}

export async function get(path) {
  const res = await fetch(path, {
    headers: { Authorization: 'Bearer ' + getToken() },
  });
  if (res.status === 401) {
    showLogin();
    throw Object.assign(new Error('unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

// stream({onState}) — the deck's single push channel: one EventSource
// replaces every column poller and the cockpit's job/session tickers.
// EventSource reconnects on its own after a drop; a dead token gets an
// explicit `unauthorized` event from the server before the close, so
// the login overlay takes over instead of a blind retry loop.
// EventSource cannot set headers, so the session token rides as
// ?token= on this one route.
export function stream({ onState }) {
  const es = new EventSource('/api/events?token=' + encodeURIComponent(getToken()));
  es.addEventListener('state', (e) => {
    try { onState(JSON.parse(e.data)); } catch (_) { /* one bad frame never kills the deck */ }
    setLed('on');
    setApiState('live · push');
  });
  es.addEventListener('unauthorized', () => {
    es.close();
    showLogin();
    setApiState('login required');
    setLed('err');
  });
  es.onerror = () => { setLed('err'); setApiState('reconnecting…'); };
  return es;
}
