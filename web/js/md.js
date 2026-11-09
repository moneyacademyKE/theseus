// md — markdown rendering for the deck, the safe way: marked parses,
// DOMPurify sanitizes, and only the sanitized result ever touches
// innerHTML. Both libs are vendored UMD globals (see index.html); if
// either is missing we fall back to plain text — a missing renderer
// must never mean an unsanitized one.
let purifyHookInstalled = false;

function ensurePurifyHook(purify) {
  if (purifyHookInstalled || !purify || typeof purify.addHook !== 'function') return;
  purify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName === 'A') {
      node.setAttribute('target', '_blank');
      node.setAttribute('rel', 'noopener noreferrer');
    }
  });
  purifyHookInstalled = true;
}

function wrapTables(container) {
  container.querySelectorAll('table').forEach((table) => {
    const wrap = document.createElement('div');
    wrap.className = 'md-table-wrap';
    table.parentNode.insertBefore(wrap, table);
    wrap.appendChild(table);
  });
}

export function mdToNode(text, cls) {
  const wrap = document.createElement('div');
  if (cls) wrap.className = cls;
  const src = String(text == null ? '' : text);
  const marked = globalThis.marked;
  const purify = globalThis.DOMPurify;
  if (marked && purify && typeof marked.parse === 'function'
      && typeof purify.sanitize === 'function') {
    try {
      ensurePurifyHook(purify);
      wrap.innerHTML = purify.sanitize(marked.parse(src));
      wrapTables(wrap);
      return wrap;
    } catch (_) { /* fall through to text */ }
  }
  wrap.textContent = src;
  return wrap;
}
