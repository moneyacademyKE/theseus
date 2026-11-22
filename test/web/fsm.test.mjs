import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createMachine,
  createGate,
  registerMachine,
  deckSnapshot,
  deckHistory,
  installDeckInspector,
  clearRegistryForTest,
} from '../../web/js/fsm.js';
import { formatElapsed } from '../../web/js/pip.js';

test('createMachine validates transitions and records history', () => {
  clearRegistryForTest();
  const seen = [];
  const m = createMachine({
    name: 'composer',
    initial: 'idle',
    transitions: {
      idle: ['editing', 'sending'],
      editing: ['idle', 'sending'],
      sending: ['idle', 'error'],
      error: ['idle', 'editing', 'sending'],
    },
    onTransition: (ev) => seen.push(ev),
  });

  assert.equal(m.state(), 'idle');
  assert.equal(m.can('editing'), true);
  assert.equal(m.can('error'), false);

  assert.equal(m.to('editing', { reason: 'click-edit' }), true);
  assert.equal(m.state(), 'editing');
  assert.equal(seen.length, 1);
  assert.equal(seen[0].from, 'idle');
  assert.equal(seen[0].to, 'editing');
  assert.equal(seen[0].meta.reason, 'click-edit');

  // Illegal transition is rejected without throwing or mutating state.
  assert.equal(m.to('error'), false);
  assert.equal(m.state(), 'editing');
  assert.equal(seen.length, 1);

  // Self-transition with same state is a clean no-op returning true.
  assert.equal(m.to('editing'), true);
  assert.equal(seen.length, 1);

  // Force allows recovery when server truth overrides local state.
  assert.equal(m.force('idle', { reason: 'server-reset' }), true);
  assert.equal(m.state(), 'idle');
});

test('registry, deckSnapshot, bounded history, and frozen AX inspector', () => {
  clearRegistryForTest();
  const m = registerMachine(createMachine({
    name: 'auth',
    initial: 'locked',
    transitions: {
      locked: ['authenticating', 'owner', 'ro'],
      authenticating: ['locked', 'owner', 'ro'],
      owner: ['locked'],
      ro: ['locked'],
    },
  }));

  for (let i = 0; i < 40; i++) {
    m.to('owner');
    m.to('locked');
  }
  const hist = deckHistory();
  assert.ok(hist.length <= 60, `history should stay bounded, got ${hist.length}`);
  assert.equal(hist[hist.length - 1].machine, 'auth');

  const snap = deckSnapshot({ role: () => 'owner' });
  assert.equal(snap.version, 1);
  assert.equal(snap.machines.auth.state, 'locked');
  assert.equal(snap.context.role, 'owner');

  const target = {};
  const inspector = installDeckInspector(target, { role: () => 'ro' });
  assert.equal(Object.isFrozen(inspector), true);
  assert.equal(target.__THESEUS_DECK__, inspector);
  assert.equal(inspector.snapshot().context.role, 'ro');
});

test('createGate rejects stale async generations', () => {
  const gate = createGate();
  const t1 = gate.next();
  assert.equal(gate.isCurrent(t1), true);
  const t2 = gate.next();
  assert.equal(gate.isCurrent(t1), false);
  assert.equal(gate.isCurrent(t2), true);
  gate.invalidate();
  assert.equal(gate.isCurrent(t2), false);
});

test('formatElapsed computes seconds, minutes, and hours (not minutes/60 bug)', () => {
  assert.equal(formatElapsed(0), '0s');
  assert.equal(formatElapsed(59_000), '59s');
  assert.equal(formatElapsed(60_000), '1m 0s');
  assert.equal(formatElapsed(3_599_000), '59m 59s');
  assert.equal(formatElapsed(3_600_000), '1h 0m');
  assert.equal(formatElapsed(3_660_000), '1h 1m');
  assert.equal(formatElapsed(7_380_000), '2h 3m');
});
