// usage sparkline — tokens per day over the last 30 days, drawn by the
// vendored uPlot. The panel is one clear idea: activity at a glance.
// Exact numbers live in /api/usage and the turns column; the chart
// only needs the shape. Refreshes when the turns column changes (a new
// turn means the ledger grew). No uPlot → no chart, never a half-drawn
// one; the deck degrades the same way md.js does.
import { get } from './api.js';

let plot = null;

function fmtK(n) {
  return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(0) + 'k' : String(n);
}

function render(rows) {
  const root = document.getElementById('usage');
  if (!root || typeof uPlot === 'undefined') return;
  const data = [
    rows.map((r) => Date.parse(r.date + 'T00:00:00Z') / 1000),
    rows.map((r) => r.tokens),
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
  const total = data[1].reduce((a, b) => a + b, 0);
  root.title = 'tokens/day · last 30d · total ' + fmtK(total);
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
  if (root) root.title = 'tokens/day · last 30d';
  refreshUsage();
}
