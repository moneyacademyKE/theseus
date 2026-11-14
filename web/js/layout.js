const LAYOUT_KEY = 'theseus-deck-layout-v2';
const ORDER_KEY = 'theseus-deck-order-v2';
const PIN_KEY = 'theseus-deck-pins-v2';
const STREAM_KEY = 'theseus-deck-streams-v1';
const UI_KEY = 'theseus-deck-ui-v1';

const readJson = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key) || 'null') || fallback; } catch (_) { return fallback; } };
const columns = () => [...document.querySelectorAll('#deck > .col')];
const save = (key, value) => localStorage.setItem(key, JSON.stringify(value));
const saveOrder = () => save(ORDER_KEY, columns().map((col) => col.id));
const ui = () => readJson(UI_KEY, { scanlines: true, density: 'spatial' });
const applyUI = () => { const state = ui(); document.body.classList.toggle('scanlines', state.scanlines); document.body.classList.toggle('compact', state.density === 'compact'); };
function applyVisibility(state) { columns().forEach((col) => { col.hidden = state[col.id] === false; }); }
function restoreLayout() {
  const deck = document.getElementById('deck'); const ids = readJson(ORDER_KEY, []);
  const byId = new Map(columns().map((col) => [col.id, col]));
  ids.forEach((id) => { if (byId.has(id)) deck.append(byId.get(id)); });
  applyVisibility(readJson(LAYOUT_KEY, {})); applyUI();
}
function action(label, title, onClick) { const button = document.createElement('button'); button.type = 'button'; button.className = 'layout-action'; button.textContent = label; button.title = title; button.addEventListener('click', onClick); return button; }
function setupColumnDrag(col) {
  const header = col.querySelector(':scope > header'); if (!header || header.dataset.dragWired) return;
  header.dataset.dragWired = '1'; header.draggable = true; header.classList.add('drag-handle');
  header.addEventListener('dragstart', (event) => { col.classList.add('dragging'); event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', col.id); });
  header.addEventListener('dragend', () => { col.classList.remove('dragging'); document.querySelectorAll('.drop-target').forEach((node) => node.classList.remove('drop-target')); });
  col.addEventListener('dragover', (event) => { if (event.target === header && !col.classList.contains('dragging')) { event.preventDefault(); col.classList.add('drop-target'); } });
  col.addEventListener('dragleave', () => col.classList.remove('drop-target'));
  col.addEventListener('drop', (event) => { if (event.target !== header) return; event.preventDefault(); const dragged = document.getElementById(event.dataTransfer.getData('text/plain')); if (dragged && dragged !== col) { col.parentElement.insertBefore(dragged, col); saveOrder(); } col.classList.remove('drop-target'); });
}
function renderPins() { const body = document.querySelector('[data-body="pinned"]'); if (!body) return; body.replaceChildren(); readJson(PIN_KEY, []).forEach((text) => { const card = document.createElement('article'); card.className = 'card pinned-card'; card.textContent = text; body.append(card); }); const count = document.querySelector('#col-pinned .count'); if (count) count.textContent = String(body.children.length); }
function addPinnedStream() { if (document.getElementById('col-pinned')) return; addCustomStream({ id: 'col-pinned', title: 'pinned watchlist', accent: 'cyan' }); renderPins(); }
function addCustomStream(stream) {
  const col = document.createElement('section'); col.className = 'col custom-stream'; col.id = stream.id;
  const header = document.createElement('header'); header.innerHTML = `<h2>${stream.title}</h2><span class="count">0</span><button type="button" class="stream-remove" title="remove stream">×</button>`;
  const body = document.createElement('div'); body.className = 'body'; body.dataset.body = stream.id.replace('col-', '');
  col.append(header, body); document.getElementById('deck').append(col); setupColumnDrag(col);
  header.querySelector('.stream-remove').addEventListener('click', () => { col.remove(); save(ORDER_KEY, columns().map((item) => item.id)); });
}
function pinCard(card) { const pins = readJson(PIN_KEY, []); const text = card.textContent.trim(); if (text && !pins.includes(text)) { pins.push(text); save(PIN_KEY, pins); } addPinnedStream(); renderPins(); }
function setupCardDrops() { const deck = document.getElementById('deck'); if (!deck || deck.dataset.cardsWired) return; deck.dataset.cardsWired = '1';
  deck.addEventListener('dragstart', (event) => { const item = event.target.closest('#col-flow .card, #col-tools tr'); if (!item) return; event.dataTransfer.effectAllowed = 'copy'; event.dataTransfer.setData('text/plain', item.textContent.trim()); item.classList.add('dragging'); });
  deck.addEventListener('dragend', (event) => event.target.closest('.dragging')?.classList.remove('dragging'));
  deck.addEventListener('dragover', (event) => { if (event.target.closest('#col-flow .card, #col-tools tr, #col-pinned .body')) event.preventDefault(); });
  deck.addEventListener('drop', (event) => { const target = event.target.closest('#col-flow .card, #col-tools tr, #col-pinned .body'); if (!target) return; event.preventDefault(); if (target.closest('#col-pinned')) pinCard({ textContent: event.dataTransfer.getData('text/plain') }); else { const dragged = deck.querySelector('.dragging'); if (dragged && dragged !== target && dragged.parentElement === target.parentElement) target.parentElement.insertBefore(dragged, event.clientY < target.getBoundingClientRect().top + target.offsetHeight / 2 ? target : target.nextSibling); } });
}
function inspect(item) { const dialog = document.getElementById('deck-inspector'); if (!dialog) return; dialog.querySelector('pre').textContent = JSON.stringify({ text: item.textContent.trim(), column: item.closest('.col')?.id, capturedAt: new Date().toISOString() }, null, 2); dialog.showModal(); }
function setupToolbar() {
  const topbar = document.getElementById('topbar'); if (!topbar || document.getElementById('layout-tools')) return;
  const tools = document.createElement('div'); tools.id = 'layout-tools'; tools.className = 'layout-tools';
  const filter = document.createElement('input'); filter.type = 'search'; filter.className = 'layout-filter'; filter.placeholder = '⌘K filter deck'; filter.setAttribute('aria-label', 'filter deck');
  filter.addEventListener('input', () => { const term = filter.value.trim().toLowerCase(); document.querySelectorAll('#deck .card, #deck .stat-table tr, #deck .kv').forEach((node) => { node.hidden = Boolean(term && !node.textContent.toLowerCase().includes(term)); }); });
  const menu = document.createElement('div'); menu.id = 'layout-menu'; menu.className = 'layout-menu'; menu.hidden = true;
  columns().forEach((col) => { const label = document.createElement('label'); const box = document.createElement('input'); box.type = 'checkbox'; box.checked = !col.hidden; box.addEventListener('change', () => { const state = readJson(LAYOUT_KEY, {}); state[col.id] = box.checked; save(LAYOUT_KEY, state); applyVisibility(state); }); label.append(box, document.createTextNode(' ' + col.querySelector('h2').textContent)); menu.append(label); });
  tools.append(filter, action('add stream', 'add a custom stream', addStream), action('columns', 'show or hide columns', () => { menu.hidden = !menu.hidden; }), action('SCANLINES', 'toggle scanlines', () => { const state = ui(); state.scanlines = !state.scanlines; save(UI_KEY, state); applyUI(); }), action('density', 'toggle compact density', () => { const state = ui(); state.density = state.density === 'compact' ? 'spatial' : 'compact'; save(UI_KEY, state); applyUI(); }), action('reset', 'reset deck layout', () => { [ORDER_KEY, LAYOUT_KEY, PIN_KEY, STREAM_KEY].forEach((key) => localStorage.removeItem(key)); location.reload(); }), menu); topbar.append(tools);
  filter.addEventListener('keydown', (event) => { if (event.key === 'Escape') { filter.value = ''; filter.dispatchEvent(new Event('input')); filter.blur(); } });
  document.addEventListener('keydown', (event) => { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); filter.focus(); } });
  document.querySelectorAll('#deck .card, #deck .stat-table tr').forEach((item) => { item.addEventListener('dblclick', () => inspect(item)); });
  document.getElementById('deck-inspector-close')?.addEventListener('click', () => document.getElementById('deck-inspector')?.close());
}
function addStream() { const title = prompt('stream name', 'Pinned Watchlist'); if (!title?.trim()) return; const stream = { id: 'col-custom-' + Date.now(), title: title.trim() }; const saved = readJson(STREAM_KEY, []); saved.push(stream); save(STREAM_KEY, saved); addCustomStream(stream); }
export function bootLayout() { restoreLayout(); readJson(STREAM_KEY, []).forEach(addCustomStream); if (readJson(PIN_KEY, []).length) { addPinnedStream(); renderPins(); } columns().forEach(setupColumnDrag); setupToolbar(); setupCardDrops(); }
