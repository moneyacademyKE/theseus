// deck paging + reload decisions — pure functions, no DOM. cockpit.js
// imports these; test/web/paging.test.mjs pins them under node --test.
// Keeping the math here (not inline in the cockpit) is what makes the
// "load older" and cross-channel reload behavior testable at all.

// The transcript envelope arrives page-1-is-newest; older pages load on
// demand. Older rows PREPEND above what's already on screen.
export function nextPage(state) {
  if (!state || !state.page) return 1;
  return state.page + 1;
}

// True when the envelope says an older batch exists.
export function canLoadOlder(envelope) {
  return Boolean(envelope && envelope['has-more']);
}

// A session row moved (Telegram appended from the other channel) when
// its updated timestamp changed between snapshots AND it isn't the
// session our own job is writing to (that growth we render live).
export function sessionMoved(oldRow, newRow, busySession) {
  if (!newRow || !newRow.id) return false;
  if (newRow.id === busySession) return false;
  if (!oldRow) return true;
  return oldRow.updated !== newRow.updated;
}

// One pass over a fresh session list: which session (if any) should
// trigger a transcript reload? Returns the session id or null.
export function reloadTarget(oldList, newList, busySession) {
  if (!Array.isArray(newList)) return null;
  const old = new Map((Array.isArray(oldList) ? oldList : []).map((r) => [r.id, r]));
  for (const row of newList) {
    if (sessionMoved(old.get(row.id), row, busySession)) return row.id;
  }
  return null;
}

// The mime class the upload lane cares about: image → vision input,
// audio → STT voice note, else document. Mirrors upload-kind in
// web_turn.clj so the client can label chips before the round trip.
export function uploadKind(mime) {
  const m = String(mime || '').toLowerCase();
  if (m.startsWith('image/')) return 'photo';
  if (m.startsWith('audio/')) return 'voice';
  return 'document';
}
