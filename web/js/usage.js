// usage sparkline — tokens per day over the last 30 days, drawn by the
// vendored uPlot. The panel is one clear idea: activity at a glance.
// Exact numbers live in /api/usage and the turns column; the chart
// only needs the shape. Refreshes when the turns column changes (a new
// turn means the ledger grew). No uPlot → no chart, never a half-drawn
// one; the deck degrades the same way md.js does.
import { get } from './api.js';

let plot = null;
let lastRows = [];

function fmtK(n) {
  return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(0) + 'k' : String(n);
}

function summaryText(rows) {
  const total = rows.reduce((acc, row) => acc + (row.tokens || 0), 0);
  return rows.length + 'd · ' + fmtK(total) + ' tokens';
}

function render(rows) {
  const root = document.getElementById('usage');
  if (!root || typeof uPlot === 'undefined') return;
  lastRows = rows;
  root.replaceChildren();
  const data = [
    rows.map((row) => Date.parse(row.date + 'T00:00:00Z') / 1000),
    rows.map((row) => row.tokens),
  ];
  const opts = {
    width: root.clientWidth || 260,
    height: 56,
    padding: [4, 0, 0, 0],
    cursor: { show: false },
    legend: { show: false },
    axes: [{ show: false }, { show: false }],
    series: [
      {},
      {
        stroke: '#39c5cf',
        fill: 'rgba(57,197,207,0.14)',
        width: 1.5,
        spanGaps: true,
        points: { show: false },
      },
    ],
  };
  if (plot) plot.destroy();
  plot = new uPlot(opts, data, root);
  const summary = document.createElement('p');
  summary.className = 'usage-summary';
  summary.textContent = summaryText(rows);
  root.appendChild(summary);
  root.setAttribute('role', 'img');
  root.setAttribute('aria-label', 'token usage sparkline, ' + summaryText(rows));
  root.title = 'tokens/day · ' + summaryText(rows);
}

export async function refreshUsage() {
  try {
    render(await get('/api/usage'));
  } catch (_) {
    /* no data, no chart — the deck stays honest */
  }
}

export function bootUsage() {
  const root = document.getElementById('usage');
  if (!root) return;
  root.title = 'tokens/day · last 30d';
  refreshUsage();
  if (typeof ResizeObserver !== 'undefined') {
    const observer = new ResizeObserver(() => render(lastRows));
    observer.observe(root);
  }
}
