// time — one place for the deck's clock rules. Theseus runs in EAT,
// so every timestamp the deck shows or titles is EAT, never bare UTC.
const eatClockFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Nairobi',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false,
});

const eatFullFmt = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Nairobi',
  year: 'numeric', month: 'short', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hour12: false,
});

export function relTime(isoOrMs) {
  const t = typeof isoOrMs === 'number' ? isoOrMs : Date.parse(isoOrMs);
  if (!t || isNaN(t)) return '';
  const s = Math.max(0, (Date.now() - t) / 1000);
  if (s < 60) return Math.floor(s) + 's';
  if (s < 3600) return Math.floor(s / 60) + 'm';
  if (s < 86400) return Math.floor(s / 3600) + 'h';
  return Math.floor(s / 86400) + 'd';
}

export function eatClock(date = new Date()) {
  return eatClockFmt.format(date) + ' EAT';
}

export function eatFull(isoOrMs) {
  const t = typeof isoOrMs === 'number' ? isoOrMs : Date.parse(isoOrMs);
  if (!t || isNaN(t)) return '';
  return eatFullFmt.format(new Date(t)) + ' EAT';
}
