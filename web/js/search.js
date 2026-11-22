// deck search — FTS5 over every session transcript, server-side
// (bb-agent.web-search), rendered client-side. Uses a monotonic fetch
// gate and deterministic `theseus:transcript-loaded` synchronization
// instead of blind timeouts, and preserves jump highlights when closing
// the search bar from a result click.

import { get } from './api.js';
import { getLoadedSession } from './transcript.js';
import { createMachine, registerMachine, createGate, projectAttr } from './fsm.js';

function el(id) { return document.getElementById(id); }

const searchMachine = registerMachine(createMachine({
  name: 'search',
  initial: 'closed',
  transitions: {
    closed: ['open'],
    open: ['searching', 'closed'],
    searching: ['results', 'empty', 'error', 'closed', 'searching'],
    results: ['searching', 'closed'],
    empty: ['searching', 'closed'],
    error: ['searching', 'closed'],
  },
  onTransition: ({ to }) => projectAttr('#cockpit-searchbar', 'data-state', to),
}));

const searchGate = createGate();
let pendingHighlightHandler = null;

function clearPendingHighlight() {
  if (pendingHighlightHandler && typeof window !== 'undefined') {
    window.removeEventListener('theseus:transcript-loaded', pendingHighlightHandler);
  }
  pendingHighlightHandler = null;
}

function snippetNodes(snippet) {
  // split on the control-char markers: \u0001 opens a term, \u0002 closes
  const parts = String(snippet || '').split(/[\u0001\u0002]/);
  const frag = document.createDocumentFragment();
  for (let i = 0; i < parts.length; i++) {
    if (!parts[i]) continue;
    if (i % 2 === 0) frag.appendChild(document.createTextNode(parts[i]));
    else {
      const mark = document.createElement('mark');
      mark.className = 'search-hit';
      mark.textContent = parts[i];
      frag.appendChild(mark);
    }
  }
  return frag;
}

function resultRow(hit, tokens) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'search-result';
  row.dataset.session = hit.id;
  const title = document.createElement('div');
  title.className = 'search-result-title';
  title.textContent = hit.id + (hit.title ? ' — ' + hit.title : '');
  const snippet = document.createElement('div');
  snippet.className = 'search-result-snippet';
  snippet.appendChild(snippetNodes(hit.snippet));
  row.appendChild(title);
  row.appendChild(snippet);
  row.addEventListener('click', () => openResult(hit.id, tokens));
  return row;
}

function clearTranscriptHighlights() {
  if (window.CSS && CSS.highlights) CSS.highlights.delete('deck-search');
}

function markTranscript(tokens) {
  if (!window.CSS || !CSS.highlights || !window.Highlight) return;
  clearTranscriptHighlights();
  const box = el('cockpit-transcript');
  if (!box || !tokens.length) return;
  const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT);
  const ranges = [];
  let node;
  while ((node = walker.nextNode())) {
    const text = node.textContent.toLowerCase();
    for (const token of tokens) {
      let idx = text.indexOf(token);
      while (idx !== -1) {
        const range = document.createRange();
        range.setStart(node, idx);
        range.setEnd(node, idx + token.length);
        ranges.push(range);
        idx = text.indexOf(token, idx + token.length);
      }
    }
  }
  if (ranges.length) CSS.highlights.set('deck-search', new Highlight(...ranges));
}

function openResult(sessionId, tokens) {
  clearPendingHighlight();
  const alreadyLoaded = getLoadedSession() === sessionId;
  if (alreadyLoaded) {
    markTranscript(tokens);
  } else {
    pendingHighlightHandler = (ev) => {
      if (!ev.detail || ev.detail.session !== sessionId) return;
      clearPendingHighlight();
      markTranscript(tokens);
    };
    window.addEventListener('theseus:transcript-loaded', pendingHighlightHandler);
  }
  window.dispatchEvent(new CustomEvent('theseus:open-session', { detail: sessionId }));
  closeSearch({ preserveHighlights: true });
}

async function runSearch(input, results) {
  const q = input.value.trim();
  clearPendingHighlight();
  clearTranscriptHighlights();
  results.hidden = false;
  results.replaceChildren();
  if (!q) {
    searchMachine.force('open');
    results.appendChild(emptyRow('type a query and press Enter'));
    return;
  }
  const token = searchGate.next();
  searchMachine.force('searching', { q });
  results.appendChild(emptyRow('searching…'));
  try {
    const hits = await get('/api/search?q=' + encodeURIComponent(q));
    if (!searchGate.isCurrent(token)) return;
    results.replaceChildren();
    if (!hits.length) {
      searchMachine.force('empty', { q });
      results.appendChild(emptyRow('no sessions match'));
      return;
    }
    searchMachine.force('results', { q, count: hits.length });
    const tokens = q.toLowerCase().split(/[^a-z0-9_-]+/).filter(Boolean);
    hits.forEach((hit) => results.appendChild(resultRow(hit, tokens)));
  } catch (e) {
    if (!searchGate.isCurrent(token)) return;
    searchMachine.force('error', { q, error: e && e.message });
    results.replaceChildren();
    results.appendChild(emptyRow('search failed — ' + (e.message || 'try again')));
  }
}

function emptyRow(text) {
  const div = document.createElement('div');
  div.className = 'search-empty';
  div.textContent = text;
  return div;
}

function closeSearch(opts = {}) {
  searchGate.invalidate();
  const bar = el('cockpit-searchbar');
  const results = el('cockpit-search-results');
  if (bar) bar.hidden = true;
  if (results) { results.hidden = true; results.replaceChildren(); }
  searchMachine.force('closed');
  if (!opts.preserveHighlights) {
    clearPendingHighlight();
    clearTranscriptHighlights();
  }
}

export function bootSearch() {
  const toggle = el('cockpit-search');
  const bar = el('cockpit-searchbar');
  const input = el('cockpit-search-input');
  const closeBtn = el('cockpit-search-close');
  const results = el('cockpit-search-results');
  if (!toggle || !bar || !input) return;
  projectAttr(bar, 'data-state', searchMachine.state());
  toggle.addEventListener('click', () => {
    bar.hidden = !bar.hidden;
    if (!bar.hidden) {
      searchMachine.force('open');
      input.value = '';
      input.focus();
    } else {
      closeSearch();
    }
  });
  if (closeBtn) closeBtn.addEventListener('click', () => closeSearch());
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); runSearch(input, results); }
    if (e.key === 'Escape') closeSearch();
  });
}
