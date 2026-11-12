import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useStore } from '../lib/store';
import { useTicker } from '../lib/hooks';
import { actionable, displayStatus, statusColor } from '../lib/freshness';
import { formatAge, formatCountdown, formatDateTime, formatPrice, formatSigned } from '../lib/format';
import { actionLabel, biasLabel, t, tx, type Lang } from '../lib/i18n';
import type { ActiveSignal, Decision, Quote, TradeSetup } from '../lib/types';
import { actionColor, C } from './theme';
import { Badge, Banner, Bullet, Button, Card, Muted, Row, Title } from './ui';

export function ConnectPrompt() {
  const lang = useStore((s) => s.settings.language);
  return (
    <Card testID="connect-prompt">
      <Title>{t('notConnected', lang)}</Title>
      <Muted>
        {lang === 'ur'
          ? 'Backend URL aur API token Settings mein darj karein. Backend chalane ka tareeqa docs/USER_GUIDE_ROMAN_URDU.md mein hai.'
          : 'Enter the backend URL and API token in Settings. See docs/SETUP_BACKEND.md to run the backend.'}
      </Muted>
      <View style={{ height: 8 }} />
      <Button testID="open-settings" title={t('openSettings', lang)} onPress={() => router.push('/settings')} />
    </Card>
  );
}

/** Banners that make data quality impossible to miss. */
export function StatusBanners({ cachedAt }: { cachedAt?: number | null }) {
  const { quote, conn, serverOffsetMs, settings } = useStore();
  const lang = settings.language;
  const now = useTicker(2000);
  const status = displayStatus(quote, conn, now, serverOffsetMs);
  return (
    <>
      {conn.authFailed && <Banner testID="banner-auth" text={t('authFailed', lang)} color={C.down} />}
      {!conn.authFailed && status === 'DISCONNECTED' && settings.backendUrl ? (
        <Banner testID="banner-offline" text={t('backendOffline', lang)} color={C.down} />
      ) : null}
      {cachedAt ? <Banner text={`${t('cachedData', lang)} ${formatDateTime(cachedAt, settings.timezone)}`} color={C.warn} /> : null}
      {status === 'DEMO_DATA' && <Banner testID="banner-demo" text={t('demoBanner', lang)} color={C.gold} />}
      {status !== 'LIVE' && status !== 'DEMO_DATA' && status !== 'DISCONNECTED' && (
        <Banner text={`${t('notLiveBanner', lang)}: ${status.replace('_', ' ')}`} color={C.warn} />
      )}
    </>
  );
}

export function DecisionHero({ decision, active, now }: { decision: Decision | null; active: ActiveSignal | null; now: number }) {
  const { quote, conn, serverOffsetMs, settings } = useStore();
  const lang = settings.language;
  const status = displayStatus(quote, conn, now, serverOffsetMs);
  // Device-side safety: never show BUY/SELL as actionable when the data on this phone is not live.
  const action = decision && actionable(status) ? decision.action : 'WAIT';
  const forced = decision && decision.action !== 'WAIT' && action === 'WAIT';
  return (
    <Card testID="decision-card" style={{ borderColor: actionColor(action), borderWidth: 2 }}>
      <View style={st.heroTop}>
        <View>
          <Text style={st.heroLabel}>{t('decision', lang)}</Text>
          <Badge testID="decision-action" big text={actionLabel(action, lang)} color={actionColor(action)} />
        </View>
        <View style={{ alignItems: 'flex-end' }}>
          <Text style={st.heroLabel}>{t('bias', lang)}</Text>
          <Text testID="decision-bias" style={[st.bias, { color: decision?.bias === 'BULLISH' ? C.up : decision?.bias === 'BEARISH' ? C.down : C.sub }]}>
            {decision ? biasLabel(decision.bias, lang) : '—'}
          </Text>
          <Text style={st.score}>{t('score', lang)}: <Text style={{ color: C.gold }}>{decision?.setup ? decision.score : '—'}/100</Text></Text>
        </View>
      </View>
      <Text testID="decision-summary" style={st.summary}>
        {forced ? (lang === 'ur' ? 'Data live nahi, is liye WAIT. ' : 'Data not live, so WAIT. ') : ''}
        {decision ? tx(decision.summary, lang) : t('loading', lang)}
      </Text>
      {active && (
        <View style={st.activeRow}>
          <Badge text={active.state} color={C.info} />
          <Text style={st.activeText}>
            {active.state === 'ARMED'
              ? `${t('expiry', lang)}: ${formatCountdown(active.signal.expiresAtMs - now)}`
              : active.holdUntilMs ? `${t('maxHold', lang)}: ${formatCountdown(active.holdUntilMs - now)}` : ''}
          </Text>
        </View>
      )}
      <Muted style={{ marginTop: 6 }}>{t('scoreNote', lang)}</Muted>
    </Card>
  );
}

export function SnapshotCard({ quote, now }: { quote: Quote | null; now: number }) {
  const { settings, serverOffsetMs, conn } = useStore();
  const lang = settings.language;
  const p = quote?.precision ?? 2;
  const status = displayStatus(quote, conn, now, serverOffsetMs);
  const age = quote?.timeMs ? now + serverOffsetMs - quote.timeMs : null;
  const historical = quote?.kind === 'LAST_HISTORICAL_CLOSE';
  return (
    <Card testID="snapshot-card">
      <Title right={<Badge text={status.replace('_', ' ')} color={statusColor(status)} />}>XAU/USD</Title>
      {historical && <Muted style={{ color: C.warn, marginBottom: 4 }}>{t('lastHistoricalClose', lang)}</Muted>}
      <View style={st.quoteRow}>
        <View style={st.quoteBox}>
          <Text style={st.quoteLabel}>{t('bid', lang)}</Text>
          <Text testID="quote-bid" style={[st.quote, { color: C.down }]}>{formatPrice(quote?.bid, p)}</Text>
        </View>
        <View style={st.quoteBox}>
          <Text style={st.quoteLabel}>{t('ask', lang)}</Text>
          <Text testID="quote-ask" style={[st.quote, { color: C.up }]}>{formatPrice(quote?.ask, p)}</Text>
        </View>
      </View>
      <Row label={t('spread', lang)} value={formatPrice(quote?.spread, p)} mono />
      <Row label={t('mid', lang)} value={formatPrice(quote?.mid, p + 1)} mono />
      <Row label={`${t('dayHigh', lang)} / ${t('dayLow', lang)}`} value={`${formatPrice(quote?.dayHigh, p)} / ${formatPrice(quote?.dayLow, p)}`} mono />
      <Row label={t('change', lang)} value={`${formatSigned(quote?.change, p)} (${formatSigned(quote?.changePct, 2)}%)`}
        valueColor={(quote?.change ?? 0) >= 0 ? C.up : C.down} mono />
      <Row label={t('quoteTime', lang)} value={quote?.timeMs ? formatDateTime(quote.timeMs, settings.timezone) : '—'} />
      <Row label={t('age', lang)} value={formatAge(age)} testID="quote-age" mono />
      {quote?.latencyMs !== undefined && <Row label={t('latency', lang)} value={`${quote.latencyMs} ms`} mono />}
      <Row label={t('volatility', lang)} value={`M1 ${quote?.atrM1 ?? '—'} · M15 ${quote?.atrM15 ?? '—'} · ${quote?.volatility ?? '—'}`} mono />
      <Row label={t('source', lang)} value={quote?.source ?? '—'} />
    </Card>
  );
}

export function ActionPanel({ setup, decision, active, now, lang }: {
  setup: TradeSetup | null; decision: Decision | null; active: ActiveSignal | null; now: number; lang: Lang;
}) {
  const p = useStore((s) => s.quote?.precision ?? 2);
  const tz = useStore((s) => s.settings.timezone);
  const issued = decision && decision.action !== 'WAIT';
  if (!setup || (!issued && !active)) {
    return (
      <Card testID="action-panel">
        <Title>{t('whyWait', lang)}</Title>
        {decision?.blockers.map((b, i) => <Bullet key={'b' + i} color={C.down}>{tx(b, lang)}</Bullet>)}
        {decision?.reasons.map((b, i) => <Bullet key={'r' + i}>{tx(b, lang)}</Bullet>)}
        {!decision && <Muted>{t('loading', lang)}</Muted>}
      </Card>
    );
  }
  const sig = active?.signal;
  const entryLow = sig?.entryLow ?? setup.entryLow, entryHigh = sig?.entryHigh ?? setup.entryHigh;
  const sl = sig?.stopLoss ?? setup.stopLoss, tp1 = sig?.tp1 ?? setup.tp1, tp2 = sig?.tp2 ?? setup.tp2;
  return (
    <Card testID="action-panel" style={{ borderColor: actionColor(decision?.action), borderWidth: 1 }}>
      <Title right={<Badge text={setup.strategyName} color={C.gold} />}>{t('actionPanel', lang)}</Title>
      <Row label={t('entryZone', lang)} value={`${formatPrice(entryLow, p)} – ${formatPrice(entryHigh, p)}`} mono testID="entry-zone" />
      <Row label={t('stopLoss', lang)} value={formatPrice(sl, p)} valueColor={C.down} mono testID="stop-loss" />
      <Row label={t('tp1', lang)} value={formatPrice(tp1, p)} valueColor={C.up} mono />
      <Row label={t('tp2', lang)} value={formatPrice(tp2, p)} valueColor={C.up} mono />
      <Row label={t('riskReward', lang)} value={`TP1 ${setup.netRrTp1} · TP2 ${setup.netRrTp2} · ${lang === 'ur' ? 'mila jula' : 'blended'} ${setup.blendedNetRr}`} mono />
      <Row label={t('expiry', lang)} value={sig ? `${formatDateTime(sig.expiresAtMs, tz)} (${formatCountdown(sig.expiresAtMs - now)})` : '—'} />
      <Row label={t('maxHold', lang)} value={`${sig?.maxHoldMinutes ?? 10} ${t('minutes', lang)}`} />
      {active && <Row label={t('state', lang)} value={active.state} valueColor={C.info} />}
      {active?.fillPrice ? <Row label={t('fill', lang)} value={formatPrice(active.fillPrice, p)} mono /> : null}
      <View style={st.block}><Text style={st.blockLabel}>{t('activation', lang)}</Text><Text style={st.blockText}>{tx(setup.activationRule, lang)}</Text></View>
      <View style={st.block}><Text style={st.blockLabel}>{t('invalidation', lang)}</Text><Text style={st.blockText}>{tx(setup.invalidation, lang)}</Text></View>
      <View style={st.block}><Text style={st.blockLabel}>{t('confirmation', lang)}</Text><Text style={st.blockText}>{tx(setup.requiredConfirmation, lang)}</Text></View>
      <Row label={t('positionSize', lang)} value={setup.size.belowMinLot ? '—' : `${setup.size.lots} lot · ${setup.size.ounces} oz · $${setup.size.riskUsd}`} mono />
      <Muted>{tx(setup.size.note, lang)}</Muted>
      <Muted style={{ marginTop: 4, color: C.warn }}>{t('paperOnly', lang)}</Muted>
    </Card>
  );
}

export function ScoreBreakdown({ setup, lang }: { setup: TradeSetup; lang: Lang }) {
  return (
    <Card testID="score-breakdown">
      <Title right={<Text style={{ color: C.gold, fontWeight: '800' }}>{setup.score}/100</Text>}>{t('score', lang)}</Title>
      {setup.scoreCard.items.map((i) => (
        <View key={i.component} style={{ marginVertical: 3 }}>
          <View style={st.barRow}>
            <Text style={st.barLabel}>{i.component.replace('_', ' ')}</Text>
            <Text style={st.barValue}>{i.points}/{i.max}</Text>
          </View>
          <View style={st.barBg}><View style={[st.barFg, { width: `${Math.round((i.points / i.max) * 100)}%` }]} /></View>
          <Muted>{tx(i.note, lang)}</Muted>
        </View>
      ))}
      {setup.scoreCard.penalties.map((i) => (
        <Bullet key={i.component} color={C.down}>{i.points} · {tx(i.note, lang)}</Bullet>
      ))}
      <Muted style={{ marginTop: 6 }}>{t('scoreNote', lang)}</Muted>
    </Card>
  );
}

const st = StyleSheet.create({
  heroTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' },
  heroLabel: { color: C.sub, fontSize: 11, marginBottom: 4, textTransform: 'uppercase' },
  bias: { fontSize: 18, fontWeight: '800' },
  score: { color: C.sub, fontSize: 12, marginTop: 4 },
  summary: { color: C.text, fontSize: 15, lineHeight: 21, marginTop: 10 },
  activeRow: { flexDirection: 'row', alignItems: 'center', marginTop: 8, gap: 8 },
  activeText: { color: C.text, fontSize: 13, fontVariant: ['tabular-nums'] },
  quoteRow: { flexDirection: 'row', gap: 10, marginBottom: 6 },
  quoteBox: { flex: 1, backgroundColor: C.cardAlt, borderRadius: 10, padding: 10, alignItems: 'center' },
  quoteLabel: { color: C.sub, fontSize: 11 },
  quote: { fontSize: 24, fontWeight: '800', fontVariant: ['tabular-nums'] },
  block: { marginTop: 6 },
  blockLabel: { color: C.sub, fontSize: 12 },
  blockText: { color: C.text, fontSize: 13, lineHeight: 19 },
  barRow: { flexDirection: 'row', justifyContent: 'space-between' },
  barLabel: { color: C.text, fontSize: 12 },
  barValue: { color: C.gold, fontSize: 12, fontWeight: '700' },
  barBg: { height: 6, backgroundColor: C.cardAlt, borderRadius: 3, marginVertical: 3 },
  barFg: { height: 6, backgroundColor: C.gold, borderRadius: 3 },
});
