import React, { useState } from 'react';
import { ScrollView, View } from 'react-native';
import { Badge, Bullet, Card, Chip, Loading, Muted, Row, s, Title } from '../components/ui';
import { C } from '../components/theme';
import { ConnectPrompt, StatusBanners } from '../components/trading';
import { useStore } from '../lib/store';
import { useApi } from '../lib/hooks';
import { t } from '../lib/i18n';
import { formatDateTime, formatPrice } from '../lib/format';
import type { TimeframeAnalysis } from '../lib/types';

type Resp = { dataAsOfMs: number; precision: number; timeframes: Record<string, TimeframeAnalysis> };
const ORDER = ['H4', 'H1', 'M15', 'M5', 'M1'];

/** Liquidity, order blocks, FVGs, BOS and CHoCH per timeframe (completed candles only). */
export default function StructureScreen() {
  const { settings, token, candleVersion } = useStore();
  const lang = settings.language;
  const tz = settings.timezone;
  const [tf, setTf] = useState('M15');
  const r = useApi<Resp>('/api/structure', [candleVersion]);
  if (!settings.backendUrl || !token) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  const a = r.data?.timeframes[tf];
  const p = r.data?.precision ?? 2;
  const trendColor = (x?: string) => (x === 'UP' ? C.up : x === 'DOWN' ? C.down : C.sub);
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      <StatusBanners cachedAt={r.cachedAt} />
      <Card>
        <Title>{t('trend', lang)}</Title>
        {ORDER.map((k) => {
          const x = r.data?.timeframes[k];
          return <Row key={k} label={k} value={x ? `${x.trend} · ${t('internal', lang)} ${x.internalTrend} · EMA ${x.emaAlignment}` : '—'} valueColor={trendColor(x?.trend)} />;
        })}
        {r.data && <Muted>{formatDateTime(r.data.dataAsOfMs, tz)}</Muted>}
      </Card>
      <View style={[s.wrap, { marginBottom: 8 }]}>
        {ORDER.map((k) => <Chip key={k} testID={'st-' + k} label={k} active={k === tf} onPress={() => setTf(k)} />)}
      </View>
      {!a && r.loading && <Loading text={t('loading', lang)} />}
      {r.error && !a && <Muted style={{ color: C.down }}>{r.error}</Muted>}
      {a && (
        <>
          <Card testID="structure-events">
            <Title>BOS / CHoCH</Title>
            {[...a.events].reverse().slice(0, 6).map((e, i) => (
              <Row key={'e' + i} label={`${formatDateTime(e.timeMs, tz)} · ${e.scope === 'EXTERNAL' ? 'ext' : 'int'}`}
                value={`${e.type === 'CHOCH' ? 'CHoCH' : 'BOS'} ${e.direction === 'LONG' ? '▲' : '▼'} ${formatPrice(e.level, p)}`}
                valueColor={e.direction === 'LONG' ? C.up : C.down} />
            ))}
            {[...a.internalEvents].reverse().slice(0, 4).map((e, i) => (
              <Row key={'i' + i} label={`${formatDateTime(e.timeMs, tz)} · int`}
                value={`${e.type === 'CHOCH' ? 'CHoCH' : 'BOS'} ${e.direction === 'LONG' ? '▲' : '▼'} ${formatPrice(e.level, p)}`}
                valueColor={e.direction === 'LONG' ? C.up : C.down} />
            ))}
            {!a.events.length && !a.internalEvents.length && <Muted>{t('none', lang)}</Muted>}
          </Card>
          <Card>
            <Title>{t('swings', lang)}</Title>
            <View style={s.wrap}>
              {a.recentSwings.slice(-10).map((sw, i) => (
                <View key={i} style={{ marginRight: 6, marginBottom: 6 }}>
                  <Badge text={`${sw.label} ${formatPrice(sw.price, p)}`} color={sw.high ? '#5D3434' : '#2E4D49'} />
                </View>
              ))}
            </View>
          </Card>
          <Card testID="order-blocks">
            <Title>{t('orderBlocks', lang)}</Title>
            {a.orderBlocks.map((o, i) => (
              <Row key={i} label={`${o.direction === 'LONG' ? 'Bullish' : 'Bearish'} · ${o.status} · ${o.displacementAtr.toFixed(1)}×ATR${o.withFvg ? ' · FVG' : ''}`}
                value={`${formatPrice(o.low, p)} – ${formatPrice(o.high, p)}`} valueColor={o.direction === 'LONG' ? C.up : C.down} mono />
            ))}
            {!a.orderBlocks.length && <Muted>{t('none', lang)}</Muted>}
          </Card>
          <Card>
            <Title>{t('fvgs', lang)}</Title>
            {a.fvgs.map((f, i) => (
              <Row key={i} label={`${f.direction === 'LONG' ? 'Bullish' : 'Bearish'} · ${f.status} ${Math.round(f.fillFraction * 100)}%`}
                value={`${formatPrice(f.low, p)} – ${formatPrice(f.high, p)}`} valueColor={f.direction === 'LONG' ? C.up : C.down} mono />
            ))}
            {!a.fvgs.length && <Muted>{t('none', lang)}</Muted>}
          </Card>
          <Card>
            <Title>{t('liquidityPools', lang)}</Title>
            {a.pools.map((pl, i) => (
              <Row key={i} label={`${pl.kind.replace('_', ' ')} (${pl.touches}×) · ${pl.side === 'BUY_SIDE' ? 'buy-side' : 'sell-side'}`} value={formatPrice(pl.price, p)} mono />
            ))}
            {!a.pools.length && <Muted>{t('none', lang)}</Muted>}
          </Card>
          <Card>
            <Title>{t('sweeps', lang)}</Title>
            {a.sweeps.map((sw, i) => (
              <Bullet key={i} color={sw.impliedDirection === 'LONG' ? C.up : C.down}>
                {`${formatDateTime(sw.timeMs, tz)} · ${sw.type.replace('_', ' ')} · ${sw.levelKind} ${formatPrice(sw.level, p)} → ${sw.impliedDirection === 'LONG' ? 'bullish' : 'bearish'}`}
              </Bullet>
            ))}
            {!a.sweeps.length && <Muted>{t('none', lang)}</Muted>}
          </Card>
          <Card>
            <Title>Indicators</Title>
            <Row label="EMA 20 / 50 / 200" value={`${formatPrice(a.ema20, p)} / ${formatPrice(a.ema50, p)} / ${formatPrice(a.ema200, p)}`} mono />
            <Row label="RSI 14" value={a.rsi?.toFixed(1) ?? '—'} mono />
            <Row label="MACD hist" value={a.macdHist?.toFixed(3) ?? '—'} mono />
            <Row label="ATR 14" value={a.atr?.toFixed(3) ?? '—'} mono />
            {a.partialData && <Muted style={{ color: C.warn }}>Recent candles partially observed (stream start / outage).</Muted>}
          </Card>
        </>
      )}
    </ScrollView>
  );
}
