import React, { useEffect, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { Banner, Button, Card, Chip, Loading, Muted, Row, s, Title } from '../components/ui';
import { C } from '../components/theme';
import { ConnectPrompt } from '../components/trading';
import { useStore } from '../lib/store';
import { useApi } from '../lib/hooks';
import { api } from '../lib/api';
import { t, type Lang } from '../lib/i18n';
import { formatDateTime, formatPct, formatR } from '../lib/format';
import { exportCsv, exportPdf } from '../lib/export';
import type { PerformanceReport, PerfGroup, SignalEntry } from '../lib/types';

interface PerfResp { report: PerformanceReport; backtests: { runId: string; createdAtMs: number; dataSource: string; fromMs: number; toMs: number }[] }
interface Job { runId: string; status: string; stage?: string; error?: string; result?: BtResult }
interface Fold { trainFromMs: number; trainToMs: number; testFromMs: number; testToMs: number; chosen: { minScore: number; minNetRR: number }; trainTrades: number; testTrades: number; testExpectancyR: number | null }
interface BtResult {
  dataSource: string; fromMs: number; toMs: number; bars: number; durationMs: number;
  defaultParams: { minScore: number; minNetRR: number }; inSampleBest: { minScore: number; minNetRR: number };
  fullPeriod: PerformanceReport; inSample: PerformanceReport; outOfSample: PerformanceReport; walkForwardOos: PerformanceReport | null;
  folds: Fold[]; notes: string[];
}

function Stats({ r, lang }: { r: PerformanceReport; lang: Lang }) {
  return (
    <>
      <Row label={t('totalSetups', lang)} value={`${r.totalSetups} (${t('rejected', lang)} ${r.rejected})`} />
      <Row label={t('activated', lang)} value={`${r.activated} · closed ${r.closed} · open ${r.open}`} />
      <Row label={t('winRate', lang)} value={`${formatPct(r.winRate)} (W ${r.wins} / L ${r.losses} / BE ${r.breakeven})`} />
      <Row label={t('expectancy', lang)} value={formatR(r.expectancyR)} valueColor={(r.expectancyR ?? 0) >= 0 ? C.up : C.down} />
      <Row label={t('profitFactor', lang)} value={r.profitFactor == null ? '—' : String(r.profitFactor)} />
      <Row label={t('maxDrawdown', lang)} value={r.maxDrawdownR == null ? '—' : `${r.maxDrawdownR}R`} />
      <Row label={lang === 'ur' ? 'Ausat planned R:R' : 'Avg planned net R:R'} value={r.avgPlannedRr == null ? '—' : String(r.avgPlannedRr)} />
      <Row label={lang === 'ur' ? 'Ausat win / loss' : 'Avg win / loss'} value={`${formatR(r.avgWinR)} / ${formatR(r.avgLossR)}`} />
      <Row label={t('consecutive', lang)} value={`${r.maxConsecutiveWins} / ${r.maxConsecutiveLosses}`} />
      <Row label={t('costImpact', lang)} value={r.avgCostR == null ? '—' : `${r.avgCostR}R ${lang === 'ur' ? 'fi trade' : 'per trade'}`} />
      <Row label={t('avgHold', lang)} value={r.avgHoldMinutes == null ? '—' : `${r.avgHoldMinutes} ${t('minutes', lang)}`} />
      <Row label={`${t('expiredRate', lang)} / ${t('invalidatedRate', lang)}`} value={`${formatPct(r.expiredRate)} / ${formatPct(r.invalidatedRate)}`} />
      <Muted style={{ marginTop: 6 }}>{r.disclaimer}</Muted>
    </>
  );
}

function Groups({ title, g }: { title: string; g: Record<string, PerfGroup> }) {
  const entries = Object.entries(g);
  if (!entries.length) return null;
  return (
    <Card>
      <Title>{title}</Title>
      {entries.map(([k, v]) => (
        <Row key={k} label={`${k} (${v.trades})`} value={`${formatPct(v.winRate)} · ${formatR(v.expectancyR)}`} valueColor={(v.expectancyR ?? 0) >= 0 ? C.up : C.down} />
      ))}
    </Card>
  );
}

function Curve({ points }: { points: { timeMs: number; cumulativeR: number }[] }) {
  if (points.length < 2) return null;
  const vals = points.map((p) => p.cumulativeR);
  const max = Math.max(...vals, 0), min = Math.min(...vals, 0), span = Math.max(0.0001, max - min);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', height: 90, gap: 1, marginTop: 6 }}>
      {vals.slice(-120).map((v, i) => (
        <View key={i} style={{ flex: 1, height: Math.max(2, ((v - min) / span) * 86), backgroundColor: v >= 0 ? C.up : C.down, opacity: 0.8 }} />
      ))}
    </View>
  );
}

export default function Performance() {
  const { settings, token } = useStore();
  const lang = settings.language;
  const [source, setSource] = useState<'PAPER' | 'DEMO' | 'IMPORTED'>('PAPER');
  const r = useApi<PerfResp>(`/api/performance?source=${source}`, [source]);
  const [days, setDays] = useState(10);
  const [job, setJob] = useState<Job | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!job || job.status === 'DONE' || job.status === 'FAILED') return;
    const id = setInterval(async () => {
      try {
        setJob(await api.get<Job>(`/api/backtest/${job.runId}`));
      } catch (e) {
        setErr(String((e as Error).message));
      }
    }, 3000);
    return () => clearInterval(id);
  }, [job]);

  if (!settings.backendUrl || !token) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  const rep = r.data?.report;
  const bt = job?.result;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      <View style={s.wrap}>
        {(['PAPER', 'DEMO', 'IMPORTED'] as const).map((x) => (
          <Chip key={x} testID={'perf-' + x} label={x === 'PAPER' ? t('paper', lang) : x === 'DEMO' ? t('demo', lang) : t('imported', lang)} active={source === x} onPress={() => setSource(x)} />
        ))}
      </View>
      {r.loading && !rep && <Loading text={t('loading', lang)} />}
      {rep && (
        <Card testID="perf-report">
          <Title>{t('performance', lang)} · {rep.source}</Title>
          <Stats r={rep} lang={lang} />
          {rep.equityCurve.length > 1 && <><Muted style={{ marginTop: 8 }}>{t('equityCurve', lang)}</Muted><Curve points={rep.equityCurve} /></>}
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 8 }}>
            <View style={{ flex: 1 }}><Button kind="secondary" title={t('exportCsv', lang)} onPress={() => exportCsv(source).catch((e) => setErr(String(e)))} /></View>
            <View style={{ flex: 1 }}><Button kind="secondary" title={t('exportPdf', lang)} onPress={async () => {
              const entries = await api.get<SignalEntry[]>(`/api/signals?source=${source}&limit=300`).catch(() => [] as SignalEntry[]);
              exportPdf(`${t('performance', 'en')} (${source})`, rep, entries).catch((e) => setErr(String(e)));
            }} /></View>
          </View>
        </Card>
      )}
      {rep && <Groups title={t('bySession', lang)} g={rep.bySession} />}
      {rep && <Groups title={t('byStrategy', lang)} g={rep.byStrategy} />}
      <Card testID="backtest-card">
        <Title>{t('backtest', lang)}</Title>
        <Muted>{lang === 'ur' ? 'Historical data par chronological replay, bid/ask fills, slippage, out-of-sample aur walk-forward. Live performance nahi.' :
          'Chronological replay on historical data with bid/ask fills, slippage, out-of-sample and walk-forward. Not live performance.'}</Muted>
        <View style={[s.wrap, { marginTop: 6 }]}>
          {[5, 10, 20, 30].map((d) => <Chip key={d} label={`${d} ${t('days', lang)}`} active={days === d} onPress={() => setDays(d)} />)}
        </View>
        <Button testID="run-backtest" title={t('runBacktest', lang)} loading={!!job && job.status !== 'DONE' && job.status !== 'FAILED'}
          onPress={async () => {
            setErr(null);
            try {
              const j = await api.post<{ runId: string; status: string }>('/api/backtest', { days, walkForward: true, folds: 4 });
              setJob({ runId: j.runId, status: j.status });
            } catch (e) {
              setErr(String((e as Error).message));
            }
          }} />
        {job && job.status !== 'DONE' && <Muted testID="backtest-status">{job.status} {job.stage ?? ''} {job.error ?? ''}</Muted>}
        {bt && (
          <View testID="backtest-result">
            <Muted>{bt.dataSource} · {formatDateTime(bt.fromMs, settings.timezone)} → {formatDateTime(bt.toMs, settings.timezone)} · {bt.bars} bars · {Math.round(bt.durationMs / 1000)} s</Muted>
            <Text style={{ color: C.gold, fontWeight: '700', marginTop: 8 }}>{lang === 'ur' ? 'Poora arsa (default settings)' : 'Full period (default settings)'}</Text>
            <Stats r={bt.fullPeriod} lang={lang} />
            <Text style={{ color: C.gold, fontWeight: '700', marginTop: 8 }}>{t('inSample', lang)} (minScore {bt.inSampleBest.minScore}, minRR {bt.inSampleBest.minNetRR})</Text>
            <Row label={`${t('trades', lang)} / ${t('expectancy', lang)}`} value={`${bt.inSample.closed} / ${formatR(bt.inSample.expectancyR)}`} />
            <Text style={{ color: C.gold, fontWeight: '700', marginTop: 8 }}>{t('outOfSample', lang)}</Text>
            <Row label={`${t('trades', lang)} / ${t('expectancy', lang)}`} value={`${bt.outOfSample.closed} / ${formatR(bt.outOfSample.expectancyR)}`} />
            {bt.walkForwardOos && (
              <>
                <Text style={{ color: C.gold, fontWeight: '700', marginTop: 8 }}>{t('walkForward', lang)}</Text>
                <Row label={`${t('trades', lang)} / ${t('expectancy', lang)}`} value={`${bt.walkForwardOos.closed} / ${formatR(bt.walkForwardOos.expectancyR)}`} />
                {bt.folds.map((f, i) => (
                  <Muted key={i}>#{i + 1}: train {f.trainTrades} → test {f.testTrades} trades, {formatR(f.testExpectancyR)} (score ≥{f.chosen.minScore}, RR ≥{f.chosen.minNetRR})</Muted>
                ))}
              </>
            )}
            {bt.notes.map((n, i) => <Muted key={'n' + i}>• {n}</Muted>)}
            <Button kind="secondary" title={t('exportCsv', lang)} onPress={() => exportCsv('BACKTEST', job!.runId).catch((e) => setErr(String(e)))} />
          </View>
        )}
      </Card>
      {err && <Banner text={err} color={C.down} />}
    </ScrollView>
  );
}
