import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { ActionPanel, ConnectPrompt, DecisionHero, ScoreBreakdown, StatusBanners } from '../../components/trading';
import { Bullet, Button, Card, Muted, Row, s, Title } from '../../components/ui';
import { C } from '../../components/theme';
import { useStore } from '../../lib/store';
import { useApi, useTicker } from '../../lib/hooks';
import { t, tx } from '../../lib/i18n';
import { formatDateTime, formatPrice } from '../../lib/format';
import type { SignalEntry } from '../../lib/types';

interface BrokerCompare {
  configured: boolean;
  fresh: boolean;
  note?: string;
  broker?: string;
  dukascopy?: { bid: number; ask: number; spread: number; timeMs: number };
  brokerQuote?: { bid: number; ask: number; spread: number; timeMs: number };
  midDifference?: number;
  spreadDifference?: number;
  brokerExecutionCostPerOz?: number;
  adjustedSetup?: { entryLow: number; entryHigh: number; stopLoss: number; tp1: number; tp2: number; note: string };
}

/** BUY / SELL / WAIT setup detail with the transparent score and the live signal lifecycle. */
export default function Radar() {
  const { settings, decision, active, token } = useStore();
  const lang = settings.language;
  const now = useTicker(1000);
  const configured = !!settings.backendUrl && !!token;
  const activeEntry = useApi<SignalEntry>(active ? `/api/signals/${active.signal.id}` : null, [active?.state]);
  const broker = useApi<BrokerCompare>(settings.brokerCompare ? '/api/broker/compare' : null, [decision?.timeMs]);
  const p = useStore((x) => x.quote?.precision ?? 2);
  const setup = decision?.setup ?? null;
  if (!configured) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      <StatusBanners />
      <DecisionHero decision={decision} active={active} now={now} />
      <ActionPanel setup={setup} decision={decision} active={active} now={now} lang={lang} />
      {setup && <ScoreBreakdown setup={setup} lang={lang} />}
      {setup && (
        <Card>
          <Title>{t('reasons', lang)}</Title>
          {setup.reasons.map((r, i) => <Bullet key={i}>{tx(r, lang)}</Bullet>)}
          {!!setup.risks.length && <Muted style={{ marginTop: 6 }}>{t('risks', lang)}</Muted>}
          {setup.risks.map((r, i) => <Bullet key={'k' + i} color={C.warn}>{tx(r, lang)}</Bullet>)}
          {decision?.action === 'WAIT' && <Muted style={{ marginTop: 6 }}>{lang === 'ur' ? 'Ye sab se acha candidate tha magar jaari nahi hua (upar WAIT ki wajah dekhein).' : 'Best candidate found, but not issued (see WAIT reasons above).'}</Muted>}
        </Card>
      )}
      {activeEntry.data && (
        <Card testID="active-audit">
          <Title>{t('auditHistory', lang)}</Title>
          {activeEntry.data.events.map((e) => (
            <Row key={e.seq} label={`${formatDateTime(e.timeMs, settings.timezone)} · ${e.type}`}
              value={e.fillPrice != null ? formatPrice(e.fillPrice, p) : e.toState ?? ''} />
          ))}
          <Button kind="secondary" title={t('signals', lang)} onPress={() => router.push(`/signal/${activeEntry.data!.signal.id}`)} />
        </Card>
      )}
      {settings.brokerCompare && (
        <Card testID="broker-card">
          <Title>{t('brokerCompare', lang)}</Title>
          {!broker.data?.fresh || !broker.data?.dukascopy ? (
            <Muted>{broker.data?.note ?? t('loading', lang)}</Muted>
          ) : (
            <View>
              <Row label="Dukascopy bid/ask" value={`${broker.data.dukascopy.bid} / ${broker.data.dukascopy.ask}`} mono />
              <Row label={`${broker.data.broker} bid/ask`} value={`${broker.data.brokerQuote?.bid} / ${broker.data.brokerQuote?.ask}`} mono />
              <Row label={lang === 'ur' ? 'Qeemat ka farq (mid)' : 'Price difference (mid)'} value={String(broker.data.midDifference)} mono />
              <Row label={lang === 'ur' ? 'Spread ka farq' : 'Spread difference'} value={String(broker.data.spreadDifference)} mono />
              <Row label={lang === 'ur' ? 'Broker kharcha / oz' : 'Broker execution cost / oz'} value={String(broker.data.brokerExecutionCostPerOz)} mono />
              {broker.data.adjustedSetup && (
                <>
                  <Row label={'Broker ' + t('entryZone', lang)} value={`${broker.data.adjustedSetup.entryLow} – ${broker.data.adjustedSetup.entryHigh}`} mono />
                  <Row label={'Broker SL / TP1 / TP2'} value={`${broker.data.adjustedSetup.stopLoss} / ${broker.data.adjustedSetup.tp1} / ${broker.data.adjustedSetup.tp2}`} mono />
                  <Muted>{broker.data.adjustedSetup.note}</Muted>
                </>
              )}
            </View>
          )}
        </Card>
      )}
      <Text style={{ color: C.muted, fontSize: 12 }}>{t('disclaimer', lang)}</Text>
    </ScrollView>
  );
}
