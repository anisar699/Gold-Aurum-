import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { Badge, Bullet, Card, Loading, Muted, Row, s, Title } from '../../components/ui';
import { C, stateColor } from '../../components/theme';
import { useStore } from '../../lib/store';
import { useApi } from '../../lib/hooks';
import { t, tx } from '../../lib/i18n';
import { formatDateTime, formatPrice, formatR } from '../../lib/format';
import type { SignalEntry } from '../../lib/types';

/** One signal: immutable original parameters plus its timestamped, append-only audit history. */
export default function SignalDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { settings } = useStore();
  const lang = settings.language;
  const tz = settings.timezone;
  const r = useApi<SignalEntry>(id ? `/api/signals/${encodeURIComponent(id)}` : null, [id]);
  const e = r.data;
  if (!e) return <ScrollView style={s.screen} contentContainerStyle={s.content}>{r.error ? <Muted>{r.error}</Muted> : <Loading text={t('loading', lang)} />}</ScrollView>;
  const g = e.signal;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      <Card>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
          <Text style={{ color: g.direction === 'LONG' ? C.up : C.down, fontSize: 20, fontWeight: '900' }}>{g.direction === 'LONG' ? 'BUY' : 'SELL'} XAU/USD</Text>
          <Badge text={e.state} color={stateColor(e.state)} />
        </View>
        <Muted>{g.id}</Muted>
        <Row label="Strategy" value={g.strategyName} />
        <Row label={t('score', lang)} value={`${g.score}/100`} />
        <Row label={lang === 'ur' ? 'Bana' : 'Created'} value={formatDateTime(g.createdAtMs, tz)} />
        <Row label={lang === 'ur' ? 'Data ka waqt' : 'Data time'} value={formatDateTime(g.dataTimeMs, tz)} />
        <Row label={t('entryZone', lang)} value={`${formatPrice(g.entryLow, 2)} – ${formatPrice(g.entryHigh, 2)}`} mono />
        <Row label={t('stopLoss', lang)} value={formatPrice(g.stopLoss, 2)} valueColor={C.down} mono />
        <Row label="TP1 / TP2" value={`${formatPrice(g.tp1, 2)} / ${formatPrice(g.tp2, 2)}`} valueColor={C.up} mono />
        <Row label={t('riskReward', lang)} value={`${g.netRrTp1} / ${g.netRrTp2}`} mono />
        <Row label={t('expiry', lang)} value={formatDateTime(g.expiresAtMs, tz)} />
        <Row label={t('maxHold', lang)} value={`${g.maxHoldMinutes} ${t('minutes', lang)}`} />
        <Row label="Spread" value={String(g.spreadAtCreation)} mono />
        <Row label="Feed" value={g.feedStatus} />
        <Row label="Session" value={g.session} />
        {e.result?.r != null && <Row label="R" value={formatR(e.result.r)} valueColor={e.result.r >= 0 ? C.up : C.down} />}
        {g.rejectionReason && <Text style={{ color: C.warn, marginTop: 6 }}>{tx(g.rejectionReason, lang)}</Text>}
        <Muted style={{ marginTop: 6 }}>{lang === 'ur' ? 'Asal entry, SL aur TP kabhi tabdeel nahi hote (database immutable).' : 'Original entry, stop and targets are never modified (immutable records).'}</Muted>
      </Card>
      <Card testID="audit-events">
        <Title>{t('auditHistory', lang)}</Title>
        {e.events.map((ev) => (
          <View key={ev.seq} style={{ borderLeftWidth: 2, borderLeftColor: C.gold, paddingLeft: 8, marginBottom: 8 }}>
            <Text style={{ color: C.text, fontWeight: '700' }}>{ev.type}{ev.toState ? ` → ${ev.toState}` : ''}</Text>
            <Muted>{formatDateTime(ev.timeMs, tz)}{ev.fillPrice != null ? ` · fill ${ev.fillPrice}` : ''}{ev.rMultiple != null ? ` · ${formatR(ev.rMultiple)}` : ''}{ev.bid != null ? ` · bid ${ev.bid} / ask ${ev.ask}` : ''}</Muted>
            <Text style={{ color: C.sub, fontSize: 13 }}>{tx(ev.reason, lang)}</Text>
          </View>
        ))}
      </Card>
      <Card>
        <Title>{t('reasons', lang)}</Title>
        {g.reasons.map((x, i) => <Bullet key={i}>{tx(x, lang)}</Bullet>)}
        {g.risks.map((x, i) => <Bullet key={'r' + i} color={C.warn}>{tx(x, lang)}</Bullet>)}
      </Card>
    </ScrollView>
  );
}
