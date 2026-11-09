// state.js — the deck's connection state machine, made explicit.
//
// The deck runs ~20 state machines (docs/webdeck.md §State machines has
// the map). This one was the only machine scattered across files: api.js
// wrote the status text at six call sites, setLed and the document title
// at others, and cockpit.js even wrote error strings into the connection
// line. Now the vocabulary is data — one table, one write function, and
// text, led, title and html[data-conn] are all derived from it.
//
// Legality is documentation, not a gate: an out-of-map transition logs
// [deck-fsm] and applies anyway — a wrong label is a bug to fix, not a
// frame to drop. State fails visible, never loud.
//
// Policy: a new state gets a row here before it gets rendered anywhere.
// html[data-conn] is the machine-readable projection — agents and tests
// read it instead of parsing the status text.

const LIVE = new Set(['live · push', 'live · poll']);

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

// Every transition the transport actually makes. Reload (fresh boot)
// is the only way back to `connecting…` from locked or poll mode.
const LEGAL = {
  'login required': ['connecting…'],
  'connecting…':    ['cached', 'live · push', 'live · poll', 'reconnecting…', 'login required'],
  'cached':         ['live · push', 'live · poll', 'reconnecting…', 'login required'],
  'live · push':    ['reconnecting…', 'live · poll', 'login required'],
  'live · poll':    ['offline', 'login required'],
  'reconnecting…':  ['live · push', 'live · poll', 'login required'],
  'offline':        ['live · poll', 'login required'],
};

let label = 'connecting…'; // matches the static shell until boot decides

export function connLabel() { return label; }
export function connState() { return STATES[label]; }

function deckTitle(text) {
  document.title = text && !LIVE.has(text)
    ? 'THESEUS · deck — ' + text
    : 'THESEUS · deck';
}

export function setLed(state) {
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

// setConn(label) — the ONE write. Unknown labels (future code, a
// custom banner) log and render as text only: never break the deck.
export function setConn(next) {
  const el = document.getElementById('api-state');
  if (el) el.textContent = next;
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
  document.documentElement.dataset.conn = row.conn;
  setLed(row.led);
  return label;
}
