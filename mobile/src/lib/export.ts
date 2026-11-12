import { Platform } from 'react-native';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { File, Paths } from 'expo-file-system';
import { api } from './api';
import type { PerformanceReport, SignalEntry } from './types';

function esc(s: unknown): string {
  return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string));
}

/** Download the backend CSV and open the Android share sheet. */
export async function exportCsv(source: string, runId?: string): Promise<string> {
  const q = runId ? `source=BACKTEST&runId=${encodeURIComponent(runId)}` : `source=${encodeURIComponent(source)}`;
  const csv = await api.text(`/api/export/signals.csv?${q}`);
  if (Platform.OS === 'web') {
    const blob = new Blob([csv], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'aurum-signals.csv';
    a.click();
    return 'downloaded';
  }
  const f = new File(Paths.cache, `aurum-signals-${Date.now()}.csv`);
  await f.write(csv);
  if (await Sharing.isAvailableAsync()) await Sharing.shareAsync(f.uri, { mimeType: 'text/csv', dialogTitle: 'AURUM XAU signals' });
  return f.uri;
}

export function reportHtml(title: string, report: PerformanceReport | null, entries: SignalEntry[]): string {
  const r = report;
  const stat = (k: string, v: unknown) => `<tr><td>${esc(k)}</td><td><b>${esc(v ?? '—')}</b></td></tr>`;
  const rows = entries.slice(0, 300).map((e) => `<tr><td>${esc(new Date(e.signal.createdAtMs).toISOString().replace('T', ' ').slice(0, 16))}</td>
    <td>${esc(e.signal.direction)}</td><td>${esc(e.signal.strategy)}</td><td>${esc(e.state)}</td><td>${esc(e.signal.entryRef)}</td>
    <td>${esc(e.signal.stopLoss)}</td><td>${esc(e.signal.tp2)}</td><td>${esc(e.result?.r == null ? '' : e.result.r.toFixed(2))}</td></tr>`).join('');
  return `<html><head><meta charset="utf-8"><style>
    body{font-family:sans-serif;font-size:11px;color:#111} h1{color:#8a6d0b} table{border-collapse:collapse;width:100%;margin-bottom:12px}
    td,th{border:1px solid #ccc;padding:3px 5px;text-align:left} th{background:#f3eed8}</style></head><body>
    <h1>AURUM XAU — ${esc(title)}</h1><p>Generated ${esc(new Date().toISOString())} (UTC). ${esc(r?.disclaimer ?? '')}</p>
    ${r ? `<table>${stat('Source', r.source)}${stat('Total setups', r.totalSetups)}${stat('Activated', r.activated)}${stat('Closed', r.closed)}
      ${stat('Win rate', r.winRate == null ? '—' : (r.winRate * 100).toFixed(1) + '%')}${stat('Expectancy (R)', r.expectancyR)}
      ${stat('Profit factor', r.profitFactor)}${stat('Max drawdown (R)', r.maxDrawdownR)}${stat('Avg cost (R)', r.avgCostR)}
      ${stat('Expiry rate', r.expiredRate)}${stat('Invalidation rate', r.invalidatedRate)}</table>` : ''}
    <table><tr><th>Created (UTC)</th><th>Dir</th><th>Strategy</th><th>Outcome</th><th>Entry</th><th>SL</th><th>TP2</th><th>R</th></tr>${rows}</table>
    <p>Analysis and paper trading only. Not financial advice.</p></body></html>`;
}

/** Render a PDF report on the device and open the share sheet. */
export async function exportPdf(title: string, report: PerformanceReport | null, entries: SignalEntry[]): Promise<void> {
  const html = reportHtml(title, report, entries);
  if (Platform.OS === 'web') {
    await Print.printAsync({ html });
    return;
  }
  const { uri } = await Print.printToFileAsync({ html });
  if (await Sharing.isAvailableAsync()) await Sharing.shareAsync(uri, { mimeType: 'application/pdf', dialogTitle: 'AURUM XAU report' });
}
