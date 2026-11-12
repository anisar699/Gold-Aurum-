import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { Badge, Bullet, Card, Loading, Muted, Row, s, Title } from '../components/ui';
import { C } from '../components/theme';
import { ConnectPrompt, StatusBanners } from '../components/trading';
import { useStore } from '../lib/store';
import { useApi, useTicker } from '../lib/hooks';
import { t, tx, type Lang } from '../lib/i18n';
import { formatAge, formatDateTime, formatPrice } from '../lib/format';
import type { KeyLevel, OrderBlock, Pool, Txt, Zone } from '../lib/types';

interface Resp {
  refPrice: number;
  dataAsOfMs: number;
  precision: number;
  nearestSupport: Zone | null;
  nearestResistance: Zone | null;
  strongestSupport: Zone | null;
  strongestResistance: Zone | null;
  zones: Zone[];
  keyLevels: KeyLevel[];
  orderBlocks: Record<string, OrderBlock[]>;
  liquidity: Record<string, Pool[]>;
  invalidation?: { price: number; rule: Txt };
}

function ZoneCard({ title, z, p, lang, testID }: { title: string; z: Zone | null; p: number; lang: Lang; testID?: string }) {
  const tz = useStore((x) => x.settings.timezone);
  const now = useTicker(30_000);
  if (!z) return null;
  const color = z.type === 'SUPPORT' ? C.up : C.down;
  return (
    <Card testID={testID}>
      <Title right={<Badge text={`${t('strength', lang)} ${Math.round(z.strength)}`} color={C.gold} />}>{title}</Title>
      <Text style={{ color, fontSize: 20, fontWeight: '800', fontVariant: ['tabular-nums'] }}>{formatPrice(z.low, p)} – {formatPrice(z.high, p)}</Text>
      <Row label={t('sourceTf', lang)} value={z.sourceTf} />
      <Row label={t('distance', lang)} value={formatPrice(z.distance, p)} mono />
      <Row label={t('lastTested', lang)} value={`${formatDateTime(z.lastTouchMs, tz)} (${formatAge(now - z.lastTouchMs)})`} />
      {z.reasons.map((r, i) => <Bullet key={i}>{tx(r, lang)}</Bullet>)}
    </Card>
  );
}

/** Automatic support/resistance radar with exact bounds, timeframe, age, strength and reasons. */
export default function Levels() {
  const { settings, token, candleVersion } = useStore();
  const lang = settings.language;
  const r = useApi<Resp>('/api/levels?limit=10', [candleVersion]);
  if (!settings.backendUrl || !token) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  const d = r.data;
  const p = d?.precision ?? 2;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      <StatusBanners cachedAt={r.cachedAt} />
      {!d && r.loading && <Loading text={t('loading', lang)} />}
      {r.error && !d && <Muted style={{ color: C.down }}>{r.error}</Muted>}
      {d && (
        <>
          <Muted>{lang === 'ur' ? 'Hawala qeemat' : 'Reference price'} {formatPrice(d.refPrice, p)} · {formatDateTime(d.dataAsOfMs, settings.timezone)}</Muted>
          <View style={{ height: 8 }} />
          <ZoneCard testID="nearest-resistance" title={t('nearestResistance', lang)} z={d.nearestResistance} p={p} lang={lang} />
          <ZoneCard testID="nearest-support" title={t('nearestSupport', lang)} z={d.nearestSupport} p={p} lang={lang} />
          {d.strongestResistance?.id !== d.nearestResistance?.id && <ZoneCard title={t('strongestResistance', lang)} z={d.strongestResistance} p={p} lang={lang} />}
          {d.strongestSupport?.id !== d.nearestSupport?.id && <ZoneCard title={t('strongestSupport', lang)} z={d.strongestSupport} p={p} lang={lang} />}
          <Card testID="zone-list">
            <Title>{t('allZones', lang)}</Title>
            {d.zones.map((z) => (
              <Row key={z.id} label={`${z.type === 'SUPPORT' ? 'S' : 'R'} · ${z.sourceTf} · ${Math.round(z.strength)}`}
                value={`${formatPrice(z.low, p)} – ${formatPrice(z.high, p)}`} valueColor={z.type === 'SUPPORT' ? C.up : C.down} mono />
            ))}
          </Card>
          <Card>
            <Title>{t('keyLevels', lang)}</Title>
            {d.keyLevels.map((k) => (
              <Row key={k.name} label={k.name.replace(/_/g, ' ')} value={formatPrice(k.price, p)} valueColor={k.side === 'BUY_SIDE' ? C.down : C.up} mono />
            ))}
          </Card>
          <Card>
            <Title>{lang === 'ur' ? 'Fresh order blocks aur liquidity' : 'Fresh order blocks & liquidity'}</Title>
            {Object.entries(d.orderBlocks).map(([tf, obs]) => obs.filter((o) => o.status === 'FRESH').map((o, i) => (
              <Row key={tf + i} label={`${tf} OB ${o.direction === 'LONG' ? 'demand' : 'supply'}`} value={`${formatPrice(o.low, p)} – ${formatPrice(o.high, p)}`}
                valueColor={o.direction === 'LONG' ? C.up : C.down} mono />
            )))}
            {Object.entries(d.liquidity).map(([tf, pools]) => pools.map((pl, i) => (
              <Row key={tf + 'l' + i} label={`${tf} ${pl.kind.replace('_', ' ')}`} value={formatPrice(pl.price, p)} mono />
            )))}
          </Card>
          {d.invalidation && (
            <Card>
              <Title>{t('invalidation', lang)}</Title>
              <Row label="SL" value={formatPrice(d.invalidation.price, p)} valueColor={C.down} mono />
              <Muted>{tx(d.invalidation.rule, lang)}</Muted>
            </Card>
          )}
        </>
      )}
    </ScrollView>
  );
}
