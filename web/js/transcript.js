// transcript column internals — rendering, pagination ("load older"),
// and the edit-affordance payload. Split from cockpit.js to keep both
// under the size ceiling; cockpit owns WHEN to reload, this module owns
// HOW the history renders.
import { get } from './api.js';
import { mdToNode } from './md.js';
import { canLoadOlder } from './paging.js';

const el = (id) => document.getElementById(id);

// Transcripts are markdown on the wire (assistant finals especially) —
// mdToNode runs marked + DOMPurify, and degrades to plain text if the
// vendored libs somehow failed to load.
export function bubble(text, cls, role) {
  const node = mdToNode(text, 'bubble ' + cls);
  const label = document.createElement('span');
  label.className = 'msg-role';
  label.textContent = role || 'theseus';
  node.prepend(label);
  return node;
}

const page = { num: 1, hasMore: false, lastUserInput: null };

// The last user input on page 1 is the edit affordance's payload: edit
// re-runs THAT turn, not an arbitrary one.
export function getLastUserInput() { return page.lastUserInput; }

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

async function renderPage(sessionId, box, url, prepend) {
  let env;
  try {
    env = await get(url);
  } catch (e) {
    box.appendChild(bubble('transcript unavailable', 'dim'));
    return;
  }
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
  if (page.hasMore) box.prepend(olderButton(sessionId, box));
  if (rows.length) {
    const lastUser = [...rows].reverse().find((t) => t.input);
    if (lastUser) page.lastUserInput = lastUser.input;
  }
}

export async function loadTranscript(sessionId) {
  const box = el('cockpit-transcript');
  if (!box || !sessionId) return;
  box.innerHTML = '';
  page.num = 1;
  await renderPage(sessionId, box, '/api/transcript?id=' + encodeURIComponent(sessionId), false);
  // content-visibility: bubbles report estimated heights until the
  // browser realizes them near the viewport; scrollIntoView on the last
  // bubble is the primitive the browser resolves through it.
  let frames = 0;
  const settle = () => {
    if (box.lastElementChild) box.lastElementChild.scrollIntoView({block: 'end', behavior: 'instant'});
    if (++frames < 5) requestAnimationFrame(settle);
  };
  settle();
}

async function loadOlder(sessionId, box) {
  const next = page.num + 1;
  const anchor = box.firstElementChild;
  await renderPage(sessionId, box,
    '/api/transcript?id=' + encodeURIComponent(sessionId) + '&page=' + next, true);
  if (anchor) anchor.scrollIntoView({block: 'start', behavior: 'instant'});
}
