import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { Badge, Banner, Card, Loading, Muted, Row, s, Title } from '../components/ui';
import { C } from '../components/theme';
import { ConnectPrompt } from '../components/trading';
import { useStore } from '../lib/store';
import { useApi, useTicker } from '../lib/hooks';
import { t, tx } from '../lib/i18n';
import { formatDateTime } from '../lib/format';
import type { EconomicEvent, Txt } from '../lib/types';

interface Resp {
  source: { provider: string; available: boolean; events: number; lastSuccessMs: number; lastError: string };
  gate: { blocked: boolean; reason: Txt | null; calendarAvailable: boolean; upcomingRisks: Txt[] };
  blackoutMinutes: number;
  events: EconomicEvent[];
}

const impactColor = (i: string) => (i === 'High' ? C.down : i === 'Medium' ? C.warn : C.muted);

/** Gold-relevant US macro events from the configured calendar source. Values are never invented. */
export default function Calendar() {
  const { settings, token } = useStore();
  const lang = settings.language;
  const r = useApi<Resp>('/api/calendar');
  const now = useTicker(30_000);
  if (!settings.backendUrl || !token) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  const d = r.data;
  const events = (d?.events ?? []).filter((e) => e.goldRelevant || e.impact === 'High');
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      {r.cachedAt && <Banner text={`${t('cachedData', lang)} ${formatDateTime(r.cachedAt, settings.timezone)}`} color={C.warn} />}
      {!d && r.loading && <Loading text={t('loading', lang)} />}
      {d && (
        <>
          <Card testID="news-gate">
            <Title right={<Badge text={d.gate.blocked ? 'BLOCKED' : 'CLEAR'} color={d.gate.blocked ? C.down : C.up} />}>{t('newsBlackout', lang)}</Title>
            <Muted>±{d.blackoutMinutes} min · {d.source.provider}</Muted>
            {d.gate.reason && <Text style={{ color: C.down, marginTop: 4 }}>{tx(d.gate.reason, lang)}</Text>}
            {!d.source.available && <Text style={{ color: C.warn, marginTop: 4 }}>{t('calendarUnavailable', lang)}{d.source.lastError ? ': ' + d.source.lastError : ''}</Text>}
            <Muted style={{ marginTop: 4 }}>{t('macroNotConfigured', lang)}</Muted>
          </Card>
          {events.length === 0 && <Muted>{t('none', lang)}</Muted>}
          {events.map((e) => {
            const released = e.timeMs <= now;
            return (
              <Card key={e.id} testID="calendar-event">
                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Text style={{ color: C.text, fontWeight: '700', fontSize: 15, flex: 1 }}>{e.country} · {e.title}</Text>
                  <Badge text={e.impact || '—'} color={impactColor(e.impact)} />
                </View>
                <Row label={formatDateTime(e.timeMs, settings.timezone)} value={released ? t('released', lang) : t('upcoming', lang)}
                  valueColor={released ? C.sub : C.gold} />
                <Row label={t('previous', lang)} value={e.previous || t('notProvided', lang)} />
                <Row label={t('forecast', lang)} value={e.forecast || t('notProvided', lang)} />
                <Row label={t('actual', lang)} value={e.actual || t('notProvided', lang)} />
                <Muted style={{ marginTop: 4 }}>{t('goldImplication', lang)}: {tx(e.implication, lang)}</Muted>
              </Card>
            );
          })}
          <Muted>Source: {d.source.provider}. {lang === 'ur' ? 'Qeematein source ke mutabiq; app khud koi value nahi banati.' : 'Values exactly as published by the source; the app never invents values.'}</Muted>
        </>
      )}
    </ScrollView>
  );
}
