// fsm — tiny, zero-dependency finite-state-machine kernel and async
// sequence gate for the Web Deck. Keeps state transitions explicit,
// rejects illegal moves without crashing the UI, projects states to
// data-* attributes, and exposes a frozen read-only introspection
// surface (window.__THESEUS_DECK__) for developers (DX) and agents (AX).

const MAX_HISTORY = 60;
const registry = new Map();
const historyRing = [];
let globalSeq = 0;

function record(entry) {
  historyRing.push(entry);
  if (historyRing.length > MAX_HISTORY) historyRing.shift();
}

export function createMachine({
  name,
  initial,
  transitions = {},
  onTransition = null,
}) {
  if (!name || typeof name !== 'string') {
    throw new Error('createMachine requires a string name');
  }
  let current = initial;
  let since = Date.now();
  let seq = 0;

  function can(next) {
    if (next === current) return true;
    const allowed = transitions[current];
    return Array.isArray(allowed) && allowed.includes(next);
  }

  function apply(next, meta, forced) {
    if (next === current) return true;
    const prev = current;
    current = next;
    since = Date.now();
    seq += 1;
    globalSeq += 1;
    const ev = {
      seq: globalSeq,
      machine: name,
      from: prev,
      to: next,
      at: since,
      forced: Boolean(forced),
      meta: meta || null,
    };
    record(ev);
    if (typeof onTransition === 'function') {
      try { onTransition(ev); } catch (_) { /* listener fault must not brick FSM */ }
    }
    return true;
  }

  const machine = {
    name,
    state: () => current,
    since: () => since,
    seq: () => seq,
    can,
    to(next, meta) {
      if (!can(next)) {
        globalSeq += 1;
        record({
          seq: globalSeq,
          machine: name,
          from: current,
          to: next,
          at: Date.now(),
          rejected: true,
          meta: meta || null,
        });
        return false;
      }
      return apply(next, meta, false);
    },
    force(next, meta) {
      return apply(next, meta, true);
    },
    snapshot() {
      return { state: current, since, seq };
    },
  };
  return machine;
}

export function registerMachine(machine) {
  if (machine && machine.name) registry.set(machine.name, machine);
  return machine;
}

export function deckHistory() {
  return historyRing.slice();
}

export function deckSnapshot(contextProviders = {}) {
  const machines = {};
  for (const [name, m] of registry.entries()) {
    machines[name] = m.snapshot();
  }
  const context = {};
  for (const [k, fn] of Object.entries(contextProviders)) {
    try { context[k] = typeof fn === 'function' ? fn() : fn; }
    catch (_) { context[k] = null; }
  }
  return { version: 1, at: Date.now(), machines, context };
}

export function installDeckInspector(target = globalThis, contextProviders = {}) {
  const api = Object.freeze({
    version: 1,
    snapshot: () => deckSnapshot(contextProviders),
    history: () => deckHistory(),
    machines: () => Array.from(registry.keys()),
  });
  if (target && typeof target === 'object') {
    target.__THESEUS_DECK__ = api;
  }
  return api;
}

export function clearRegistryForTest() {
  registry.clear();
  historyRing.length = 0;
  globalSeq = 0;
}

// Monotonic generation gate: prevents out-of-order async responses
// (rapid session switches, overlapping FTS searches, goal-detail loads)
// from overwriting newer UI state.
export function createGate() {
  let gen = 0;
  return {
    next() {
      gen += 1;
      return gen;
    },
    isCurrent(token) {
      return token === gen;
    },
    invalidate() {
      gen += 1;
    },
  };
}

// Helper for safe DOM data-state projection (no-op in Node tests).
export function projectAttr(selectorOrEl, attr, value) {
  if (typeof document === 'undefined') return;
  const el = typeof selectorOrEl === 'string'
    ? document.querySelector(selectorOrEl)
    : selectorOrEl;
  if (!el) return;
  if (value == null || value === '') el.removeAttribute(attr);
  else el.setAttribute(attr, String(value));
}
