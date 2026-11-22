// deck api — session-gated fetch, login, and the single push stream.
// The stored credential is a session token minted by POST /api/login
// (master password); it never equals the password itself.
// Status surfaces (text, led, title, data-conn, data-role) live in
// state.js — the connection & auth machines' single source of truth.
import { setConn, setRole, clearRole, setAuthState } from './state.js';
import { createMachine, registerMachine } from './fsm.js';

const TOKEN_KEY = 'theseus-deck-token';

const transportMachine = registerMachine(createMachine({
  name: 'transport',
  initial: 'idle',
  transitions: {
    idle: ['sse-connecting', 'polling'],
    'sse-connecting': ['sse-open', 'sse-backoff', 'polling', 'stopped'],
    'sse-open': ['sse-backoff', 'polling', 'stopped'],
    'sse-backoff': ['sse-open', 'polling', 'stopped'],
    polling: ['stopped'],
    stopped: ['sse-connecting'],
  },
}));

export function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }

export function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }

export function showLogin() {
  const el = document.getElementById('login');
  if (!el) return;
  // Native <dialog>: guard .open so concurrent 401s never throw InvalidStateError.
  if (!el.open) el.showModal();
}

function handleUnauthorized() {
  setAuthState('locked', { reason: '401' });
  setConn('login required');
  showLogin();
}

export async function login(password) {
  setAuthState('authenticating');
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (res.status === 200) {
    const session = await res.json();
    setToken(session.token);
    setRole(session.role || 'owner');
    return session;
  }
  setAuthState('locked', { reason: 'login-failed', status: res.status });
  const err = await res.json().catch(() => ({}));
  throw Object.assign(new Error('login failed'), {
    code: err.error || 'unknown',
    hint: err.hint || '',
    retryMs: typeof err['retry-ms'] === 'number' ? err['retry-ms'] : 0,
  });
}

export async function get(path) {
  const res = await fetch(path, {
    headers: { Authorization: 'Bearer ' + getToken() },
  });
  if (res.status === 401) {
    handleUnauthorized();
    throw Object.assign(new Error('unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw Object.assign(new Error('HTTP ' + res.status), { status: res.status });
  return res.json();
}

// stream({onState}) — the deck's single push channel: one EventSource
// replaces every column poller and the cockpit's job/session tickers.
const FALLBACK_AFTER_MS = 20000;
const POLL_MS = 5000;
const HEARTBEAT_STALE_MS = 45000;

export function stream({ onState }) {
  let pollTimer = null;
  let watchdogTimer = null;
  let fellBack = false;
  let dead = false;
  let lastOk = Date.now();

  const markOk = () => { lastOk = Date.now(); };

  function stop() {
    dead = true;
    if (pollTimer) clearInterval(pollTimer);
    if (watchdogTimer) clearInterval(watchdogTimer);
    pollTimer = null;
    watchdogTimer = null;
    transportMachine.force('stopped');
  }

  async function pollOnce() {
    if (dead) return;
    try {
      const st = await get('/api/state');
      if (dead) return;
      markOk();
      setConn('live · poll');
      try { onState(st); } catch (_) { /* renderer isolation, same as push */ }
    } catch (ex) {
      if (dead) return;
      if (ex && ex.unauthorized) { stop(); }
      else { setConn('offline'); }
    }
  }

  function startPoll() {
    if (fellBack) return;
    fellBack = true;
    transportMachine.force('polling');
    // Stay honest: remain reconnecting until the first poll actually returns 200 OK.
    setConn('reconnecting…');
    pollOnce();
    pollTimer = setInterval(pollOnce, POLL_MS);
  }

  transportMachine.force('sse-connecting');
  const es = new EventSource('/api/events?token=' + encodeURIComponent(getToken()));
  watchdogTimer = setInterval(() => {
    if (dead || fellBack) return;
    if (Date.now() - lastOk > HEARTBEAT_STALE_MS) {
      es.close();
      startPoll();
    }
  }, 5000);
  es.onopen = () => {
    markOk();
    transportMachine.to('sse-open');
  };
  es.addEventListener('state', (e) => {
    markOk();
    transportMachine.to('sse-open');
    setConn('live · push');
    try { onState(JSON.parse(e.data)); } catch (_) { /* one bad frame never kills the deck */ }
  });
  es.addEventListener('ping', markOk);
  es.addEventListener('unauthorized', () => {
    stop();
    es.close();
    handleUnauthorized();
  });
  es.onerror = () => {
    if (dead || fellBack) return;
    if (Date.now() - lastOk > FALLBACK_AFTER_MS) {
      es.close();
      startPoll();
      return;
    }
    transportMachine.to('sse-backoff');
    setConn('reconnecting…');
  };
  return { stop };
}

// Unified authed mutations shared by cockpit, columns, schedules, and account buttons.
export async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
    body: JSON.stringify(body || {}),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    handleUnauthorized();
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
    handleUnauthorized();
    throw Object.assign(new Error(data.error || 'unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

export async function put(path, bytes) {
  const res = await fetch(path, {
    method: 'PUT',
    headers: { Authorization: 'Bearer ' + getToken() },
    body: bytes,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    handleUnauthorized();
    throw Object.assign(new Error(data.error || 'unauthorized'), { unauthorized: true });
  }
  if (!res.ok) throw Object.assign(new Error(data.error || ('HTTP ' + res.status)), { status: res.status });
  return data;
}

export async function logout() {
  try { await post('/api/logout', {}); } catch (_) { /* a dead token logs out fine */ }
  localStorage.removeItem(TOKEN_KEY);
  clearRole();
}

export async function rotatePassword(current, next) {
  const res = await post('/api/password', { current, new: next });
  if (res.token) setToken(res.token);
  return res;
}
