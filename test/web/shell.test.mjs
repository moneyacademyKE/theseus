// node --test pins for the static shell (parity rows 12-16): the DOM
// affordances the cockpit needs MUST exist in index.html — the spec
// check's teeth. Parses the shell as text; no browser needed.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const shell = readFileSync(join(here, '../../web/index.html'), 'utf8');

const present = (needle, why) => {
  assert.ok(shell.includes(needle), `index.html must carry ${needle} — ${why}`);
};

test('transcript affordances: pagination + edit (rows 12, 16)', () => {
  present('id="cockpit-transcript"', 'the history column');
  present('id="cockpit-edit"', 'edit-last button');
});

test('media affordances: mic + camera (rows 14, 15)', () => {
  present('id="cockpit-mic"', 'microphone record button');
  present('id="cockpit-camera-input"', 'the hidden capture input');
  present('capture="environment"', 'rear-camera capture attribute');
  present('id="cockpit-file"', 'the file attach input');
});

test('media modules are imported by the shell entry point', () => {
  const main = readFileSync(join(here, '../../web/js/main.js'), 'utf8');
  assert.ok(main.includes('cockpit.js') || main.includes('bootCockpit'),
    'main.js must boot the cockpit');
});

test('cockpit module wires the new affordances (source pins)', () => {
  const cockpit = readFileSync(join(here, '../../web/js/cockpit.js'), 'utf8');
  assert.ok(cockpit.includes("reloadTarget"), 'cross-channel reload decision');
  assert.ok(cockpit.includes('state.busySession'), 'busy-session exclusion');
  assert.ok(cockpit.includes('micToggle'), 'mic wiring');
  assert.ok(cockpit.includes('captureAndSend'), 'camera wiring');
  assert.ok(cockpit.includes('editLast'), 'edit-last wiring');
  assert.ok(cockpit.includes('loadOlder') || cockpit.includes('load-older') ||
    JSON.stringify(cockpit).includes('transcript.js'), 'pagination import');
});

test('a snapshot missing the watched job must not end the watch (issue #35)', () => {
  const cockpit = readFileSync(join(here, '../../web/js/cockpit.js'), 'utf8');
  // a stale poll/push frame can predate the POST that created the job;
  // only a real 404 from the job route may declare it gone
  assert.ok(cockpit.includes("/api/job/"), 'missing-job verification fetch');
  assert.ok(cockpit.includes('status === 404') || cockpit.includes("status===404"),
    'gone is decided by the job route 404, not a missing snapshot entry');
  const api = readFileSync(join(here, '../../web/js/api.js'), 'utf8');
  // the fetch helper must carry the status code so the 404 is decidable
  assert.match(api, /export async function get\(path\) \{[\s\S]*?status: res\.status/,
    'get() attaches the HTTP status to its thrown error');
});

test('transcript.js owns paging + untruncated render (row 12)', () => {
  const t = readFileSync(join(here, '../../web/js/transcript.js'), 'utf8');
  assert.ok(t.includes('has-more') || t.includes('hasMore'), 'envelope field');
  assert.ok(t.includes('&page='), 'page parameter in the fetch');
  assert.ok(t.includes('getLastUserInput'), 'edit payload source');
});
