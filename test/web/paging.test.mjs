// node --test pins for web/js/paging.js — the pure decisions behind
// "load older" and cross-channel transcript reload (parity rows 12-13).
// No DOM: the browser modules import these same functions.
import test from 'node:test';
import assert from 'node:assert/strict';
import { canLoadOlder, sessionMoved, reloadTarget, uploadKind } from '../../web/js/paging.js';

test('canLoadOlder reads the envelope honestly', () => {
  assert.equal(canLoadOlder({ 'has-more': true, page: 1 }), true);
  assert.equal(canLoadOlder({ 'has-more': false, page: 1 }), false);
  assert.equal(canLoadOlder(null), false);
  assert.equal(canLoadOlder({}), false);
});

test('sessionMoved: a new row counts, our own busy session never does', () => {
  const row = { id: 'web-1', updated: '2026-11-10T07:00:00Z' };
  // first sighting → moved
  assert.equal(sessionMoved(null, row, null), true);
  // unchanged timestamp → not moved
  assert.equal(sessionMoved({ id: 'web-1', updated: row.updated }, row, null), false);
  // timestamp bumped → moved
  const bumped = { ...row, updated: '2026-11-10T07:05:00Z' };
  assert.equal(sessionMoved(row, bumped, null), true);
  // the session our own job is writing to is excluded — its growth
  // renders live via the job card, a reload would fight the flow
  assert.equal(sessionMoved(null, bumped, 'web-1'), false);
  assert.equal(sessionMoved(row, bumped, 'web-1'), false);
});

test('reloadTarget finds the moved session, ignores the busy one', () => {
  const old = [{ id: 'a', updated: '1' }, { id: 'b', updated: '1' }];
  const fresh = [
    { id: 'a', updated: '1' },
    { id: 'b', updated: '2' }, // b moved from the other channel
    { id: 'c', updated: '3' }, // brand new session
  ];
  assert.equal(reloadTarget(old, fresh, null), 'b');
  // our own job is writing b → no reload target from b
  assert.equal(reloadTarget(old, fresh, 'b'), 'c');
  // degenerate inputs
  assert.equal(reloadTarget(null, null, null), null);
  assert.equal(reloadTarget(old, [], null), null);
  assert.equal(reloadTarget(old, 'nope', null), null);
});

test('uploadKind mirrors the server-side upload-kind classifier', () => {
  assert.equal(uploadKind('image/jpeg'), 'photo');
  assert.equal(uploadKind('IMAGE/PNG'), 'photo');
  assert.equal(uploadKind('audio/ogg'), 'voice');
  assert.equal(uploadKind('audio/webm'), 'voice');
  assert.equal(uploadKind('application/pdf'), 'document');
  assert.equal(uploadKind(''), 'document');
  assert.equal(uploadKind(undefined), 'document');
});
