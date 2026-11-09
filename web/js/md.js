// md — markdown rendering for the deck, the safe way: marked parses,
// DOMPurify sanitizes, and only the sanitized result ever touches
// innerHTML. Both libs are vendored UMD globals (see index.html); if
// either is missing we fall back to plain text — a missing renderer
// must never mean an unsanitized one.
export function mdToNode(text, cls) {
  const wrap = document.createElement('div');
  if (cls) wrap.className = cls;
  const src = String(text == null ? '' : text);
  const marked = globalThis.marked;
  const purify = globalThis.DOMPurify;
  if (marked && purify && typeof marked.parse === 'function'
      && typeof purify.sanitize === 'function') {
    try {
      wrap.innerHTML = purify.sanitize(marked.parse(src));
      return wrap;
    } catch (_) { /* fall through to text */ }
  }
  wrap.textContent = src;
  return wrap;
}
