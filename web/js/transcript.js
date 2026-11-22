// transcript column internals — rendering, pagination ("load older"),
// and the edit-affordance payload. Monotonic request gating ensures
// rapid session switches never render stale out-of-order transcripts,
// and loading older pages never overwrites the page-1 edit target.
import { get } from './api.js';
import { mdToNode } from './md.js';
import { canLoadOlder } from './paging.js';
import { createMachine, registerMachine, createGate, projectAttr } from './fsm.js';

const el = (id) => document.getElementById(id);

const transcriptMachine = registerMachine(createMachine({
  name: 'transcript',
  initial: 'idle',
  transitions: {
    idle: ['loading'],
    loading: ['loaded', 'empty', 'error', 'loading'],
    loaded: ['loading', 'loading-older'],
    'loading-older': ['loaded', 'error', 'loading'],
    empty: ['loading'],
    error: ['loading'],
  },
  onTransition: ({ to }) => projectAttr('#cockpit-transcript', 'data-state', to),
}));

const loadGate = createGate();

export function bubble(text, cls, role) {
  const node = mdToNode(text, 'bubble ' + cls);
  const label = document.createElement('span');
  label.className = 'msg-role';
  label.textContent = role || 'theseus';
  node.prepend(label);
  return node;
}

const page = { num: 1, hasMore: false, lastUserInput: null, session: null };

// The last user input on page 1 is the edit affordance's payload: edit
// re-runs THAT turn, not an arbitrary older page.
export function getLastUserInput() { return page.lastUserInput; }
export function getLoadedSession() { return page.session; }

function olderButton(sessionId, box) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'older';
  b.id = 'load-older';
  b.textContent = 'load older';
  b.setAttribute('aria-label', 'load older messages');
  b.onclick = () => { b.remove(); loadOlder(sessionId, box); };
  return b;
}

async function renderPage(sessionId, box, url, prepend, token) {
  let env;
  try {
    env = await get(url);
  } catch (_) {
    if (!loadGate.isCurrent(token)) return false;
    transcriptMachine.force('error', { session: sessionId, prepend });
    box.appendChild(bubble('transcript unavailable', 'dim'));
    return false;
  }
  if (!loadGate.isCurrent(token)) return false;

  const rows = env.rows || [];
  if (!prepend && !rows.length) {
    box.appendChild(bubble('no history yet — say something', 'dim', 'note'));
  }
  const frag = document.createDocumentFragment();
  for (const t of rows) {
    if (t.input) frag.appendChild(bubble(t.input, 'in', 'you'));
    frag.appendChild(bubble(t.final, t.ok ? 'out' : 'out err', 'theseus'));
  }
  if (prepend) box.prepend(frag);
  else box.appendChild(frag);
  page.num = env.page || 1;
  page.hasMore = canLoadOlder(env);
  page.session = sessionId;
  if (page.hasMore) box.prepend(olderButton(sessionId, box));
  // Only page 1 (newest turns) owns lastUserInput; prepending older
  // pages must never corrupt the "edit last message" target.
  if (!prepend) {
    const lastUser = rows.length ? [...rows].reverse().find((t) => t.input) : null;
    page.lastUserInput = lastUser ? lastUser.input : null;
  }
  transcriptMachine.force(!prepend && !rows.length ? 'empty' : 'loaded', {
    session: sessionId,
    page: page.num,
    rows: rows.length,
  });
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('theseus:transcript-loaded', {
      detail: { session: sessionId, page: page.num, count: rows.length, prepend },
    }));
  }
  return true;
}

export async function loadTranscript(sessionId) {
  const box = el('cockpit-transcript');
  if (!box || !sessionId) return;
  const token = loadGate.next();
  transcriptMachine.force('loading', { session: sessionId });
  box.replaceChildren();
  page.num = 1;
  page.hasMore = false;
  page.lastUserInput = null;
  const ok = await renderPage(
    sessionId,
    box,
    '/api/transcript?id=' + encodeURIComponent(sessionId),
    false,
    token,
  );
  if (!ok || !loadGate.isCurrent(token)) return;
  let frames = 0;
  const settle = () => {
    if (!loadGate.isCurrent(token)) return;
    if (box.lastElementChild) box.lastElementChild.scrollIntoView({ block: 'end', behavior: 'instant' });
    if (++frames < 5) requestAnimationFrame(settle);
  };
  settle();
}

async function loadOlder(sessionId, box) {
  const token = loadGate.next();
  transcriptMachine.force('loading-older', { session: sessionId, page: page.num + 1 });
  const next = page.num + 1;
  const anchor = box.firstElementChild;
  const ok = await renderPage(
    sessionId,
    box,
    '/api/transcript?id=' + encodeURIComponent(sessionId) + '&page=' + next,
    true,
    token,
  );
  if (ok && loadGate.isCurrent(token) && anchor) {
    anchor.scrollIntoView({ block: 'start', behavior: 'instant' });
  }
}
