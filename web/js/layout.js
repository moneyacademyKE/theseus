// Local-only deck composition: order, visibility, filtering, and pinned cards.
const LAYOUT_KEY = 'theseus-deck-layout-v1';
const ORDER_KEY = 'theseus-deck-order-v1';
const PIN_KEY = 'theseus-deck-pins-v1';

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key) || 'null') || fallback; }
  catch (_) { return fallback; }
}
function columns() { return [...document.querySelectorAll('#deck > .col')]; }
function saveOrder() { localStorage.setItem(ORDER_KEY, JSON.stringify(columns().map((c) => c.id))); }
function applyVisibility(state) {
  for (const col of columns()) col.hidden = state[col.id] === false;
}
function restoreLayout() {
  const ids = readJson(ORDER_KEY, []);
  const deck = document.getElementById('deck');
  const byId = new Map(columns().map((c) => [c.id, c]));
  if (deck && Array.isArray(ids)) for (const id of ids) if (byId.has(id)) deck.append(byId.get(id));
  applyVisibility(readJson(LAYOUT_KEY, {}));
}
function action(label, title, onClick) {
  const button = document.createElement('button');
  button.type = 'button'; button.className = 'layout-action';
  button.textContent = label; button.title = title; button.addEventListener('click', onClick);
  return button;
}
function addPinnedStream() {
  if (document.getElementById('col-pinned')) return;
  const col = document.createElement('section');
  col.className = 'col'; col.id = 'col-pinned';
  const header = document.createElement('header');
  header.innerHTML = '<h2>pinned watchlist</h2><span class="count">0</span>';
  const body = document.createElement('div');
  body.className = 'body'; body.dataset.body = 'pinned';
  col.append(header, body);
  document.getElementById('deck').append(col);
  setupColumnDrag(col);
  renderPins();
}
function addStream(label) {
  if (label === 'pinned watchlist') addPinnedStream();
}
function renderPins() {
  const body = document.querySelector('[data-body="pinned"]');
  if (!body) return;
  body.replaceChildren();
  const pins = readJson(PIN_KEY, []);
  for (const text of pins) {
    const card = document.createElement('article');
    card.className = 'card pinned-card'; card.textContent = text; card.draggable = true;
    card.title = 'drag out to remove';
    card.addEventListener('dragstart', () => card.classList.add('dragging'));
    card.addEventListener('dragend', () => card.classList.remove('dragging'));
    body.append(card);
  }
  const count = document.querySelector('#col-pinned .count');
  if (count) count.textContent = String(pins.length);
}
function pinCard(card) {
  const pins = readJson(PIN_KEY, []);
  const text = card.textContent.trim();
  if (text && !pins.includes(text)) { pins.push(text); localStorage.setItem(PIN_KEY, JSON.stringify(pins)); }
  addPinnedStream(); renderPins();
}
function setupCardDrops() {
  const deck = document.getElementById('deck');
  if (!deck || deck.dataset.cardsWired) return;
  deck.dataset.cardsWired = '1';
  deck.addEventListener('dragstart', (event) => {
    const card = event.target.closest('#col-flow .card, #col-tools tr');
    if (!card) return;
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData('text/plain', card.textContent.trim());
    card.classList.add('dragging');
  });
  deck.addEventListener('dragend', (event) => event.target.closest('.dragging')?.classList.remove('dragging'));
  deck.addEventListener('dragover', (event) => {
    if (event.target.closest('#col-flow .card, #col-tools tr, #col-pinned .body')) event.preventDefault();
  });
  deck.addEventListener('drop', (event) => {
    const target = event.target.closest('#col-flow .card, #col-tools tr, #col-pinned .body');
    if (!target) return;
    event.preventDefault();
    if (target.closest('#col-pinned')) {
      const source = event.dataTransfer.getData('text/plain');
      if (source) { pinCard({textContent: source}); }
      return;
    }
    const dragged = deck.querySelector('.dragging');
    if (dragged && dragged !== target && dragged.parentElement === target.parentElement) {
      target.parentElement.insertBefore(dragged, event.clientY < target.getBoundingClientRect().top + target.offsetHeight / 2 ? target : target.nextSibling);
    }
  });
}
function setupColumnDrag(col) {
  const header = col.querySelector(':scope > header');
  if (!header || header.dataset.dragWired) return;
  header.dataset.dragWired = '1'; header.draggable = true; header.classList.add('drag-handle');
  header.addEventListener('dragstart', (event) => {
    col.classList.add('dragging'); event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', col.id);
  });
  header.addEventListener('dragend', () => {
    col.classList.remove('dragging'); document.querySelectorAll('.drop-target').forEach((n) => n.classList.remove('drop-target'));
  });
  col.addEventListener('dragover', (event) => { if (event.target === header && !col.classList.contains('dragging')) { event.preventDefault(); col.classList.add('drop-target'); } });
  col.addEventListener('dragleave', () => col.classList.remove('drop-target'));
  col.addEventListener('drop', (event) => {
    if (event.target !== header) return;
    event.preventDefault(); const id = event.dataTransfer.getData('text/plain');
    const dragged = document.getElementById(id);
    if (dragged && dragged !== col) { col.parentElement.insertBefore(dragged, col); saveOrder(); }
    col.classList.remove('drop-target');
  });
}
function setupToolbar() {
  const topbar = document.getElementById('topbar');
  if (!topbar || document.getElementById('layout-tools')) return;
  const tools = document.createElement('div'); tools.id = 'layout-tools'; tools.className = 'layout-tools';
  const filter = document.createElement('input'); filter.type = 'search'; filter.className = 'layout-filter'; filter.placeholder = 'filter deck'; filter.setAttribute('aria-label', 'filter deck columns');
  filter.addEventListener('input', () => { const term = filter.value.trim().toLowerCase(); document.querySelectorAll('#deck .card, #deck .stat-table tr, #deck .kv').forEach((n) => { n.hidden = Boolean(term && !n.textContent.toLowerCase().includes(term)); }); });
  const menu = document.createElement('div'); menu.id = 'layout-menu'; menu.className = 'layout-menu'; menu.hidden = true;
  for (const col of columns()) { const label = document.createElement('label'); const box = document.createElement('input'); box.type = 'checkbox'; box.checked = !col.hidden; box.addEventListener('change', () => { const state = readJson(LAYOUT_KEY, {}); state[col.id] = box.checked; localStorage.setItem(LAYOUT_KEY, JSON.stringify(state)); applyVisibility(state); }); label.append(box, document.createTextNode(' ' + col.querySelector('h2').textContent)); menu.append(label); }
  tools.append(filter, action('add stream', 'add a pinned watchlist', () => addStream('pinned watchlist')), action('columns', 'show or hide columns', () => { menu.hidden = !menu.hidden; }), action('reset', 'reset deck layout', () => { localStorage.removeItem(ORDER_KEY); localStorage.removeItem(LAYOUT_KEY); localStorage.removeItem(PIN_KEY); location.reload(); }), menu);
  topbar.append(tools);
}
export function bootLayout() { restoreLayout(); columns().forEach(setupColumnDrag); setupToolbar(); setupCardDrops(); if (readJson(PIN_KEY, []).length) addPinnedStream(); }
