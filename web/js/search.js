// deck search — FTS5 over every session transcript, server-side
// (bb-agent.web-search), rendered client-side. Snippets arrive with
// \u0001/\u0002 term markers; they are split into text nodes + <mark>,
// never innerHTML. Opening a result dispatches the existing
// theseus:open-session event (the same seam the columns use) and marks
// the query terms in the loaded transcript with the CSS Custom
// Highlights API — feature-detected, absent API means no marks, never
// a broken open.

import { get } from './api.js';

function el(id) { return document.getElementById(id); }

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
  window.dispatchEvent(new CustomEvent('theseus:open-session', { detail: sessionId }));
  // the transcript loads async; marks apply once it lands
  setTimeout(() => markTranscript(tokens), 400);
  setTimeout(() => markTranscript(tokens), 1200);
  closeSearch();
}

async function runSearch(input, results) {
  const q = input.value.trim();
  clearTranscriptHighlights();
  results.hidden = false;
  results.replaceChildren();
  if (!q) { results.appendChild(emptyRow('type a query and press Enter')); return; }
  results.appendChild(emptyRow('searching…'));
  try {
    const hits = await get('/api/search?q=' + encodeURIComponent(q));
    results.replaceChildren();
    if (!hits.length) { results.appendChild(emptyRow('no sessions match')); return; }
    const tokens = q.toLowerCase().split(/[^a-z0-9_-]+/).filter(Boolean);
    hits.forEach((hit) => results.appendChild(resultRow(hit, tokens)));
  } catch (e) {
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

function closeSearch() {
  const bar = el('cockpit-searchbar');
  const results = el('cockpit-search-results');
  if (bar) bar.hidden = true;
  if (results) { results.hidden = true; results.replaceChildren(); }
  clearTranscriptHighlights();
}

export function bootSearch() {
  const toggle = el('cockpit-search');
  const bar = el('cockpit-searchbar');
  const input = el('cockpit-search-input');
  const closeBtn = el('cockpit-search-close');
  const results = el('cockpit-search-results');
  if (!toggle || !bar || !input) return;
  toggle.addEventListener('click', () => {
    bar.hidden = !bar.hidden;
    if (!bar.hidden) { input.value = ''; input.focus(); }
    else closeSearch();
  });
  if (closeBtn) closeBtn.addEventListener('click', closeSearch);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); runSearch(input, results); }
    if (e.key === 'Escape') closeSearch();
  });
}
