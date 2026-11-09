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
    retryMs: typeof err['retry-ms'] === 'number' ? err['retry-ms'] : 0,
  });
}

export function showLogin() {
  const el = document.getElementById('login');
  if (!el) return;
  el.hidden = false;
  const pass = document.getElementById('login-pass');
  if (pass) pass.focus();
}

const LIVE_STATES = new Set(['live · push', 'live · poll']);

function setDeckTitle(stateText) {
  document.title = stateText && !LIVE_STATES.has(stateText)
    ? 'THESEUS · deck — ' + stateText
    : 'THESEUS · deck';
}

export function setApiState(text) {
  const el = document.getElementById('api-state');
  if (el) el.textContent = text;
  setDeckTitle(text);
}

export function setLed(state) {
  const el = document.getElementById('live');
  if (!el) return;
  el.classList.remove('on', 'err');
  if (state === 'on') {
    el.classList.add('on');
    el.setAttribute('aria-label', 'push stream live');
  } else if (state === 'err') {
    el.classList.add('err');
    el.setAttribute('aria-label', 'push stream error');
  } else {
    el.setAttribute('aria-label', 'push stream offline');
  }
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

export function stream({ onState }) {
  let pollTimer = null;
  let fellBack = false;
  let dead = false;
  let lastOk = Date.now();

  const markOk = () => { lastOk = Date.now(); setLed('on'); };

  function stop() {
    dead = true;
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
  }

  function startPoll() {
    fellBack = true;
    setApiState('live · poll');
    setLed('on');
    pollTimer = setInterval(async () => {
      try {
        const st = await get('/api/state');
        markOk();
        setApiState('live · poll');
        try { onState(st); } catch (_) { /* renderer isolation, same as push */ }
      } catch (ex) {
        if (ex && ex.unauthorized) { stop(); }
        else { setLed('err'); setApiState('offline'); }
      }
    }, POLL_MS);
  }

  const es = new EventSource('/api/events?token=' + encodeURIComponent(getToken()));
  es.onopen = markOk;
  es.addEventListener('state', (e) => {
    markOk();
    setApiState('live · push');
    try { onState(JSON.parse(e.data)); } catch (_) { /* one bad frame never kills the deck */ }
  });
  es.addEventListener('ping', markOk);
  es.addEventListener('unauthorized', () => {
    stop();
    es.close();
    showLogin();
    setApiState('login required');
    setLed('err');
  });
  es.onerror = () => {
    if (dead || fellBack) return;
    if (Date.now() - lastOk > FALLBACK_AFTER_MS) {
      es.close();
      dead = true;
      startPoll();
      return;
    }
    setLed('err');
    setApiState('reconnecting…');
  };
  return { stop };
}
