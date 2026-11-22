// state.js — the deck's connection & auth/role state machines.
//
// Connection vocabulary is data — one table, one write function, and
// text, led, title and html[data-conn] are all derived from it.
// Auth/role state is also projected onto html[data-auth] and
// html[data-role] so read-only viewers (:ro) and agents (AX) know the
// exact write capabilities without hitting surprise 403s.
import { createMachine, registerMachine, projectAttr } from './fsm.js';

const LIVE = new Set(['live · push', 'live · poll']);
const ROLE_KEY = 'theseus-deck-role';

// label → projections. conn is the canonical machine-readable value.
const STATES = {
  'login required': { conn: 'locked',       led: 'err'  },
  'connecting…':    { conn: 'connecting',   led: 'off'  },
  'cached':         { conn: 'cached',       led: 'off'  },
  'live · push':    { conn: 'live',         led: 'on'   },
  'live · poll':    { conn: 'live',         led: 'on'   },
  'reconnecting…':  { conn: 'reconnecting', led: 'warn' },
  'offline':        { conn: 'offline',      led: 'warn' },
};

// Every transition the transport actually makes.
const LEGAL = {
  'login required': ['connecting…', 'live · push', 'live · poll'],
  'connecting…':    ['cached', 'live · push', 'live · poll', 'reconnecting…', 'offline', 'login required'],
  'cached':         ['live · push', 'live · poll', 'reconnecting…', 'offline', 'login required'],
  'live · push':    ['reconnecting…', 'live · poll', 'offline', 'login required'],
  'live · poll':    ['offline', 'reconnecting…', 'login required'],
  'reconnecting…':  ['live · push', 'live · poll', 'offline', 'login required'],
  'offline':        ['live · poll', 'reconnecting…', 'login required'],
};

const connMachine = registerMachine(createMachine({
  name: 'conn',
  initial: 'connecting',
  transitions: {
    locked:       ['connecting', 'live'],
    connecting:   ['cached', 'live', 'reconnecting', 'offline', 'locked'],
    cached:       ['live', 'reconnecting', 'offline', 'locked'],
    live:         ['reconnecting', 'offline', 'locked'],
    reconnecting: ['live', 'offline', 'locked'],
    offline:      ['live', 'reconnecting', 'locked'],
  },
}));

const authMachine = registerMachine(createMachine({
  name: 'auth',
  initial: 'locked',
  transitions: {
    locked:         ['authenticating', 'owner', 'ro'],
    authenticating: ['locked', 'owner', 'ro'],
    owner:          ['locked', 'ro'],
    ro:             ['locked', 'owner'],
  },
  onTransition: ({ to }) => {
    if (typeof document !== 'undefined') {
      projectAttr(document.documentElement, 'data-auth', to);
    }
  },
}));

let label = 'connecting…'; // matches the static shell until boot decides
let currentRole = (typeof localStorage !== 'undefined' && localStorage.getItem(ROLE_KEY)) || 'owner';

export function connLabel() { return label; }
export function connState() { return STATES[label]; }
export function getRole() { return currentRole; }
export function isReadOnly() { return currentRole === 'ro'; }

export function applyRoleUi() {
  if (typeof document === 'undefined') return;
  const ro = isReadOnly();
  projectAttr(document.documentElement, 'data-role', currentRole);
  const writeIds = [
    'cockpit-text', 'cockpit-send', 'cockpit-new', 'cockpit-attach',
    'cockpit-camera', 'cockpit-mic', 'cockpit-edit', 'cockpit-rotate',
    'sched-id', 'sched-cron', 'sched-prompt',
  ];
  for (const id of writeIds) {
    const el = document.getElementById(id);
    if (!el) continue;
    if (ro) {
      el.setAttribute('data-ro-disabled', '1');
      el.disabled = true;
    } else if (el.getAttribute('data-ro-disabled') === '1') {
      el.removeAttribute('data-ro-disabled');
      el.disabled = false;
    }
  }
  const schedSubmit = document.querySelector('#sched-form button[type="submit"]');
  if (schedSubmit) schedSubmit.disabled = ro;
}

export function setRole(role) {
  const norm = role === 'ro' ? 'ro' : 'owner';
  currentRole = norm;
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(ROLE_KEY, norm);
  }
  authMachine.force(norm);
  applyRoleUi();
  return norm;
}

export function clearRole() {
  currentRole = 'owner';
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem(ROLE_KEY);
  }
  authMachine.force('locked');
  if (typeof document !== 'undefined') {
    projectAttr(document.documentElement, 'data-role', 'locked');
  }
}

export function setAuthState(next, meta) {
  if (!authMachine.to(next, meta)) authMachine.force(next, meta);
  return authMachine.state();
}

function deckTitle(text) {
  if (typeof document === 'undefined') return;
  document.title = text && !LIVE.has(text)
    ? 'THESEUS · deck — ' + text
    : 'THESEUS · deck';
}

export function setLed(state) {
  if (typeof document === 'undefined') return;
  const el = document.getElementById('live');
  if (!el) return;
  el.classList.remove('on', 'err', 'warn');
  if (state === 'on') {
    el.classList.add('on');
    el.setAttribute('aria-label', 'push stream live');
  } else if (state === 'err') {
    el.classList.add('err');
    el.setAttribute('aria-label', 'push stream error');
  } else if (state === 'warn') {
    el.classList.add('warn');
    el.setAttribute('aria-label', 'push stream degraded');
  } else {
    el.setAttribute('aria-label', 'push stream offline');
  }
}

// setConn(label) — the ONE write. Unknown labels log and render as text only.
export function setConn(next) {
  if (typeof document !== 'undefined') {
    const el = document.getElementById('api-state');
    if (el) el.textContent = next;
  }
  deckTitle(next);
  const row = STATES[next];
  if (!row) {
    console.warn('[deck-fsm] unknown label — text only:', next);
    return label;
  }
  if (next !== label && !(LEGAL[label] || []).includes(next)) {
    console.warn('[deck-fsm] out-of-map transition:', label, '→', next);
  }
  label = next;
  if (!connMachine.to(row.conn, { label: next })) {
    connMachine.force(row.conn, { label: next });
  }
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.conn = row.conn;
  }
  setLed(row.led);
  return label;
}
