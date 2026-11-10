// deck api — session-gated fetch, login, and the single push stream.
// The stored credential is a session token minted by POST /api/login
// (master password); it never equals the password itself.
// Status surfaces (text, led, title, data-conn) live in state.js —
// the connection machine's single source of truth.
import { setConn, setLed } from './state.js';

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
    retryMs: typeof err['retry-ms'] === 'number' ? err['retry-ms'] : 0,
  });
}

export function showLogin() {
  const el = document.getElementById('login');
  if (!el) return;
  // Native <dialog>: showModal owns the focus trap, Esc, inert
  // background, and centers the box. No-op if already open.
  el.showModal();
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
// If the pipe stays dead ~20s across EventSource's own retries, the
// stream degrades to a 5s poll of GET /api/state — the same snapshot,
// one request — and stays there until reload (which re-tries push).
// EventSource cannot set headers, so the session token rides as
// ?token= on this one route.
const FALLBACK_AFTER_MS = 20000;
const POLL_MS = 5000;
// The server pings every 15s exactly so the client can tell a quiet
// deck from a dead pipe. EventSource fires onerror when the OS
// delivers the close — but a half-open pipe (partition, sleep, a
// tunnel dying without FIN) delivers nothing at all, and `live` would
// be a lie forever. So the client watchdogs the heartbeat: three
// missed pings and the stream degrades to poll, which finds out the
// truth within one POLL_MS.
const HEARTBEAT_STALE_MS = 45000;

export function stream({ onState }) {
  let pollTimer = null;
  let watchdogTimer = null;
  let fellBack = false;
  let dead = false;
  let lastOk = Date.now();

  const markOk = () => { lastOk = Date.now(); setLed('on'); };

  function stop() {
    dead = true;
    if (pollTimer) clearInterval(pollTimer);
    if (watchdogTimer) clearInterval(watchdogTimer);
    pollTimer = null;
    watchdogTimer = null;
  }

  function startPoll() {
    fellBack = true;
    setConn('live · poll');
    setLed('on');
    pollTimer = setInterval(async () => {
      try {
        const st = await get('/api/state');
        markOk();
        setConn('live · poll');
        try { onState(st); } catch (_) { /* renderer isolation, same as push */ }
      } catch (ex) {
        if (ex && ex.unauthorized) { stop(); setConn('login required'); }
        else { setConn('offline'); }
      }
    }, POLL_MS);
  }

  const es = new EventSource('/api/events?token=' + encodeURIComponent(getToken()));
  watchdogTimer = setInterval(() => {
    if (dead || fellBack) return;
    if (Date.now() - lastOk > HEARTBEAT_STALE_MS) {
      es.close();
      dead = true;
      startPoll();
    }
  }, 5000);
  es.onopen = markOk;
  es.addEventListener('state', (e) => {
    markOk();
    setConn('live · push');
    try { onState(JSON.parse(e.data)); } catch (_) { /* one bad frame never kills the deck */ }
  });
  es.addEventListener('ping', markOk);
  es.addEventListener('unauthorized', () => {
    stop();
    es.close();
    showLogin();
    setConn('login required');
  });
  es.onerror = () => {
    if (dead || fellBack) return;
    if (Date.now() - lastOk > FALLBACK_AFTER_MS) {
      es.close();
      dead = true;
      startPoll();
      return;
    }
    setConn('reconnecting…');
  };
  return { stop };
}

// authed mutations shared by the cockpit + schedules modules. post
// already exists in cockpit.js's private scope; these are the module
// surface versions (schedules.js, account buttons) so no second copy
// of the fetch shape lives anywhere.
export async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
    body: JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    showLogin();
    throw Object.assign(new Error(data.error || 'unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

export async function del(path) {
  const res = await fetch(path, {
    method: 'DELETE',
    headers: { Authorization: 'Bearer ' + getToken() },
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    showLogin();
    throw Object.assign(new Error(data.error || 'unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

// logout + rotation (parity row 18): logout revokes this token and
// drops the local copy; rotation swaps the stored token for the fresh
// one the route returns.
export async function logout() {
  try { await post('/api/logout', {}); } catch (_) { /* a dead token logs out fine */ }
  localStorage.removeItem(TOKEN_KEY);
}

export async function rotatePassword(current, next) {
  const res = await post('/api/password', { current, new: next });
  if (res.token) setToken(res.token);
  return res;
}
