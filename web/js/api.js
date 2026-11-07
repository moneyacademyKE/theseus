// deck api — session-gated fetch + a poller that keeps the topbar honest.
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

// poll(path, seconds, render) — immediate first tick, then interval.
// Stops itself on auth failure (login overlay takes over); a data error
// only dims the LED so a single bad tick doesn't kill the column.
export function poll(path, seconds, render) {
  let alive = true;
  async function tick() {
    if (!alive) return;
    try {
      const data = await get(path);
      render(data);
      setLed('on');
      setApiState('live');
    } catch (e) {
      if (e.unauthorized) { alive = false; return; }
      setLed('err');
      setApiState('api unreachable');
    }
  }
  tick();
  const id = setInterval(tick, seconds * 1000);
  return () => { alive = false; clearInterval(id); };
}
