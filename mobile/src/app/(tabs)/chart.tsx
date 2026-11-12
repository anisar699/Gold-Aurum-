import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { router, useFocusEffect, useNavigation } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { ChartView, type ChartHandle } from '../../components/ChartView';
import { Chip, Muted } from '../../components/ui';
import { C } from '../../components/theme';
import { ConnectPrompt } from '../../components/trading';
import { useStore } from '../../lib/store';
import { api } from '../../lib/api';
import { displayStatus } from '../../lib/freshness';
import { t } from '../../lib/i18n';
import { INDICATOR_KEYS, type IndicatorKey } from '../../lib/settings';
import { TIMEFRAMES, type CandlesResponse, type Timeframe } from '../../lib/types';

const LABELS: Record<IndicatorKey, string> = {
  ema20: 'EMA20', ema50: 'EMA50', ema200: 'EMA200', vwap: 'VWAP', volume: 'Volume', rsi: 'RSI14', macd: 'MACD', atr: 'ATR14',
  bidask: 'Bid/Ask', zones: 'S/R zones', ob: 'Order blocks', fvg: 'FVG', structure: 'BOS/CHoCH', liquidity: 'Liquidity',
  keyLevels: 'PDH/PDL/PWH/PWL', sessions: 'Session H/L', signal: 'Signal SL/TP',
};

interface ChartData { tf: Timeframe; candles: CandlesResponse; overlays: Record<string, unknown> }

async function fetchChart(tf: Timeframe): Promise<ChartData> {
  const [candles, overlays] = await Promise.all([
    api.get<CandlesResponse>(`/api/candles?tf=${tf}&limit=400`),
    api.get<Record<string, unknown>>(`/api/overlays?tf=${tf}`),
  ]);
  return { tf, candles, overlays };
}

export default function ChartScreen() {
  const { settings, token, quote, candleVersion, conn, serverOffsetMs } = useStore();
  const lang = settings.language;
  const configured = !!settings.backendUrl && !!token;
  const chart = useRef<ChartHandle>(null);
  const [ready, setReady] = useState(false);
  const [tf, setTf] = useState<Timeframe>(settings.defaultTf);
  const [showOverlays, setShowOverlays] = useState(false);
  const [full, setFull] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notes, setNotes] = useState('');
  const navigation = useNavigation();

  const apply = useCallback((d: ChartData, fit: boolean) => {
    chart.current?.send({ type: 'data', tf: d.tf, precision: d.candles.precision, rows: d.candles.rows, indicators: d.candles.indicators,
      volumeAvailable: d.candles.rows.some((r) => r[9] > 0), fit });
    chart.current?.send({ type: 'overlays', data: d.overlays });
    setNotes(d.candles.volumeNote);
    setError(null);
  }, []);

  // (Re)load on chart ready, timeframe/timezone change, every completed server candle, and screen focus.
  const [focusTick, setFocusTick] = useState(0);
  useFocusEffect(useCallback(() => {
    setFocusTick((n) => n + 1);
  }, []));
  const lastKey = useRef('');
  useEffect(() => {
    if (!ready || !configured) return;
    let cancelled = false;
    const key = `${tf}|${settings.timezone}`;
    const fit = key !== lastKey.current;
    lastKey.current = key;
    if (fit) chart.current?.send({ type: 'init', tz: settings.timezone, toggles: settings.indicators });
    fetchChart(tf)
      .then((d) => { if (!cancelled) apply(d, fit); })
      .catch((e) => { if (!cancelled) setError(String((e as Error).message ?? e)); });
    return () => {
      cancelled = true;
    };
  }, [ready, configured, tf, settings.timezone, candleVersion, focusTick]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!ready) return;
    chart.current?.send({ type: 'toggles', toggles: settings.indicators });
  }, [ready, settings.indicators]);

  useEffect(() => {
    if (!ready || !quote || quote.kind !== 'QUOTE') return;
    chart.current?.send({ type: 'quote', bid: quote.bid, ask: quote.ask, timeMs: quote.timeMs });
    chart.current?.send({ type: 'status', status: displayStatus(quote, conn, Date.now(), serverOffsetMs) });
  }, [ready, quote, conn, serverOffsetMs]);

  useEffect(() => {
    navigation.setOptions({ headerShown: !full, tabBarStyle: full ? { display: 'none' } : undefined });
  }, [full, navigation]);

  const toggle = (k: IndicatorKey) => useStore.getState().updateSettings({ indicators: { ...settings.indicators, [k]: !settings.indicators[k] } });

  if (!configured) return <View style={st.screen}><View style={{ padding: 12 }}><ConnectPrompt /></View></View>;

  return (
    <View style={st.screen}>
      {!full && (
        <View style={st.toolbar}>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 8 }}>
            {TIMEFRAMES.map((x) => <Chip key={x} testID={'tf-' + x} label={x} active={x === tf} onPress={() => setTf(x)} />)}
          </ScrollView>
          <View style={st.actions}>
            <Pressable testID="toggle-overlays" accessibilityLabel={t('overlays', lang)} onPress={() => setShowOverlays((v) => !v)} hitSlop={8} style={st.iconBtn}>
              <Ionicons name="layers-outline" size={22} color={showOverlays ? C.gold : C.sub} />
            </Pressable>
            <Pressable testID="fullscreen" accessibilityLabel={t('fullscreen', lang)} onPress={() => setFull(true)} hitSlop={8} style={st.iconBtn}>
              <Ionicons name="expand-outline" size={22} color={C.sub} />
            </Pressable>
            <Pressable testID="ask-ai-chart" accessibilityLabel={t('askAiChart', lang)} hitSlop={8} style={st.iconBtn}
              onPress={() => router.push({ pathname: '/ai', params: { tf } })}>
              <Ionicons name="sparkles-outline" size={22} color={C.gold} />
            </Pressable>
          </View>
        </View>
      )}
      {showOverlays && !full && (
        <View style={st.overlayPanel}>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap' }}>
            {INDICATOR_KEYS.map((k) => <Chip key={k} testID={'ov-' + k} label={LABELS[k]} active={settings.indicators[k]} onPress={() => toggle(k)} />)}
          </View>
          {!!notes && <Muted>{notes}</Muted>}
        </View>
      )}
      {error && <Text style={st.error}>{error}</Text>}
      <View style={{ flex: 1 }}>
        <ChartView ref={chart} onReady={() => setReady(true)} onError={(m) => setError(m)} />
        {full && (
          <Pressable testID="exit-fullscreen" onPress={() => setFull(false)} style={st.exitFull} hitSlop={10}>
            <Ionicons name="contract-outline" size={22} color={C.text} />
          </Pressable>
        )}
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg },
  toolbar: { flexDirection: 'row', alignItems: 'center', paddingTop: 8, borderBottomWidth: 1, borderBottomColor: C.border },
  actions: { flexDirection: 'row', paddingRight: 8 },
  iconBtn: { padding: 8, minWidth: 40, minHeight: 40, alignItems: 'center', justifyContent: 'center' },
  overlayPanel: { padding: 10, borderBottomWidth: 1, borderBottomColor: C.border, backgroundColor: C.card },
  error: { color: C.down, padding: 8, fontSize: 12 },
  exitFull: { position: 'absolute', right: 12, bottom: 36, backgroundColor: 'rgba(20,20,24,0.85)', borderRadius: 22, padding: 10 },
});
