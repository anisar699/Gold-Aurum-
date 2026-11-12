import React, { useEffect, useState } from 'react';
import { ScrollView, Switch, Text, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { Badge, Banner, Button, Card, Chip, Muted, Row, s, Title } from '../components/ui';
import { C } from '../components/theme';
import { useStore } from '../lib/store';
import { api, probe } from '../lib/api';
import { checkBackendUrl } from '../lib/url';
import { useApi } from '../lib/hooks';
import { t, type Lang } from '../lib/i18n';
import { formatDateTime } from '../lib/format';
import { getPushStatus, registerPush } from '../lib/notifications';
import * as db from '../lib/db';
import { connection } from '../lib/ws';
import { TIMEFRAMES, type StrategySettings } from '../lib/types';

function Toggle({ label, value, onChange, testID }: { label: string; value: boolean; onChange: (v: boolean) => void; testID?: string }) {
  return (
    <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', minHeight: 44 }}>
      <Text style={{ color: C.text, fontSize: 14, flex: 1 }}>{label}</Text>
      <Switch testID={testID} value={value} onValueChange={onChange} trackColor={{ true: C.gold, false: C.border }} thumbColor={C.text} />
    </View>
  );
}

const NUM_FIELDS: { key: keyof StrategySettings; label: string; ur: string; step: number }[] = [
  { key: 'maxSpread', label: 'Max spread (USD/oz)', ur: 'Zyada se zyada spread (USD/oz)', step: 0.05 },
  { key: 'riskPercent', label: 'Risk % per trade (max 1%)', ur: 'Fi trade risk % (max 1%)', step: 0.1 },
  { key: 'accountEquity', label: 'Account equity (USD)', ur: 'Account equity (USD)', step: 100 },
  { key: 'minNetRR', label: 'Minimum net R:R', ur: 'Kam az kam net R:R', step: 0.1 },
  { key: 'maxHoldMinutes', label: 'Max holding (min)', ur: 'Zyada se zyada holding (min)', step: 1 },
  { key: 'setupValidityMinutes', label: 'Setup validity (min)', ur: 'Setup ki muddat (min)', step: 1 },
  { key: 'maxDailyTrades', label: 'Daily trade limit', ur: 'Rozana trade limit', step: 1 },
  { key: 'maxConsecutiveLosses', label: 'Pause after consecutive losses', ur: 'Itne musalsal loss ke baad pause', step: 1 },
  { key: 'newsBlackoutMinutes', label: 'News blackout ± (min)', ur: 'News blackout ± (min)', step: 5 },
  { key: 'minScore', label: 'Minimum setup score', ur: 'Kam az kam setup score', step: 5 },
  { key: 'slippage', label: 'Slippage per fill (USD/oz)', ur: 'Slippage fi fill (USD/oz)', step: 0.01 },
  { key: 'commissionPerLot', label: 'Commission per lot (USD)', ur: 'Commission fi lot (USD)', step: 1 },
  { key: 'contractSizeOz', label: 'Contract size (oz per lot)', ur: 'Contract size (oz fi lot)', step: 1 },
];

function StrategySettingsCard({ lang }: { lang: Lang }) {
  const r = useApi<StrategySettings>('/api/settings');
  if (!r.data) return <Card><Title>{t('riskSettings', lang)}</Title><Muted>{r.error ?? t('loading', lang)}</Muted></Card>;
  return <StrategyForm key={JSON.stringify(r.data)} initial={r.data} lang={lang} />;
}

function StrategyForm({ initial, lang }: { initial: StrategySettings; lang: Lang }) {
  const [draft, setDraft] = useState<StrategySettings>(initial);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);
  const set = (k: keyof StrategySettings, v: unknown) => setDraft({ ...draft, [k]: v } as StrategySettings);
  const round = (v: number) => Math.round(v * 1000) / 1000;
  return (
    <Card testID="strategy-settings">
      <Title>{t('riskSettings', lang)}</Title>
      {NUM_FIELDS.map((f) => (
        <View key={f.key} style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 44 }}>
          <Text style={{ color: C.sub, fontSize: 13, flex: 1 }}>{lang === 'ur' ? f.ur : f.label}</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <Chip label="−" onPress={() => set(f.key, round((draft[f.key] as number) - f.step))} />
            <Text testID={'set-' + f.key} style={{ color: C.text, minWidth: 56, textAlign: 'center', fontVariant: ['tabular-nums'] }}>{String(draft[f.key])}</Text>
            <Chip label="+" onPress={() => set(f.key, round((draft[f.key] as number) + f.step))} />
          </View>
        </View>
      ))}
      <Muted>{lang === 'ur' ? 'Strategies' : 'Strategies'}</Muted>
      <View style={s.wrap}>
        {[['LSR', 'Liquidity Sweep'], ['BRR', 'Breakout Retest'], ['MPB', 'Momentum Pullback']].map(([id, name]) => {
          const on = draft.enabledStrategies.includes(id);
          return <Chip key={id} label={name} active={on} onPress={() => set('enabledStrategies', on ? draft.enabledStrategies.filter((x) => x !== id) : [...draft.enabledStrategies, id])} />;
        })}
      </View>
      <Muted>News impact</Muted>
      <View style={s.wrap}>
        {(['High', 'Medium', 'Low'] as const).map((x) => <Chip key={x} label={x} active={draft.newsMinImpact === x} onPress={() => set('newsMinImpact', x)} />)}
      </View>
      <Toggle label={lang === 'ur' ? 'TP1 ke baad stop breakeven par' : 'Move stop to breakeven after TP1'} value={draft.moveStopToBreakeven} onChange={(v) => set('moveStopToBreakeven', v)} />
      <Toggle label={lang === 'ur' ? 'RANGE bias mein bhi setups (kam score)' : 'Allow setups in RANGE bias (penalised)'} value={draft.allowRangeBias} onChange={(v) => set('allowRangeBias', v)} />
      <Toggle label={lang === 'ur' ? 'Calendar na mile to signals band' : 'Block signals if calendar unavailable'} value={draft.newsFailClosed} onChange={(v) => set('newsFailClosed', v)} />
      <Button testID="save-strategy" title={t('save', lang)} onPress={async () => {
        try {
          const saved = await api.put<StrategySettings>('/api/settings', draft);
          setDraft(saved);
          setMsg({ text: t('saved', lang), ok: true });
        } catch (e) {
          setMsg({ text: String((e as Error).message), ok: false });
        }
      }} />
      {msg && <Text style={{ color: msg.ok ? C.up : C.down }}>{msg.text}</Text>}
      <Muted>{lang === 'ur' ? 'Contract size aur lot step apne broker se tasdeeq karein. Gold ka koi universal pip value nahi.' : 'Verify contract size and lot step with your broker; gold has no universal pip value.'}</Muted>
    </Card>
  );
}

interface Health {
  version: string;
  serverTimeMs: number;
  config: Record<string, unknown>;
  market: { status: string; provider: { name: string; kind: string; connected: boolean; lastError: string; lastErrorAtMs: number; reconnects: number; requests: number; rateLimitedResponses: number; details: Record<string, unknown> };
    historyLoaded: boolean; historyError: string; lastTickAgeMs: number; sanitizer: Record<string, number>; candleCounts: Record<string, number>; m1GapsLast24h: number; largestGapMinutes: number };
  news: { provider: string; available: boolean; events: number; lastError: string };
  aiConfigured: boolean;
  wsClients: number;
  pushTokens: number;
}

export default function SettingsScreen() {
  const { settings, token, conn, updateSettings } = useStore();
  const lang = settings.language;
  const [url, setUrl] = useState(settings.backendUrl);
  const [tok, setTok] = useState(token);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  const configured = !!settings.backendUrl && !!token;
  const health = useApi<Health>(configured ? '/api/health/full' : null, [conn.wsOpen]);
  const [crashes, setCrashes] = useState<{ timeMs: number; entry: Record<string, unknown> }[]>([]);
  const push = getPushStatus();
  useEffect(() => {
    db.crashList(5).then(setCrashes).catch(() => undefined);
  }, []);

  const connect = async () => {
    const chk = checkBackendUrl(url);
    if (!chk.ok) {
      setResult({ ok: false, text: chk.error === 'https_required' ? t('httpsRequired', lang) : t('invalidUrl', lang) });
      return;
    }
    setBusy(true);
    const p = await probe(chk.url!, tok.trim());
    setBusy(false);
    if (!p.ok) {
      setResult({ ok: false, text: p.status === 401 ? t('authFailed', lang) : `${t('error', lang)}: ${p.message}` });
      return;
    }
    await useStore.getState().setToken(tok.trim());
    await updateSettings({ backendUrl: chk.url! });
    connection.restart();
    setResult({ ok: true, text: `${t('saved', lang)}${chk.insecureLan ? ' · ' + t('insecureLan', lang) : ''}` });
  };

  const h = health.data;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
      <Card testID="connection-card">
        <Title right={<Badge text={conn.wsOpen ? 'WS OPEN' : conn.authFailed ? 'AUTH FAILED' : 'WS CLOSED'} color={conn.wsOpen ? C.up : C.down} />}>{t('connection', lang)}</Title>
        <Text style={{ color: C.sub, fontSize: 12 }}>{t('backendUrl', lang)}</Text>
        <TextInput testID="backend-url" value={url} onChangeText={setUrl} autoCapitalize="none" autoCorrect={false} keyboardType="url"
          placeholder="https://your-backend.example.com" placeholderTextColor={C.muted}
          style={{ color: C.text, backgroundColor: C.cardAlt, borderRadius: 8, padding: 10, marginVertical: 4, minHeight: 44 }} />
        <Text style={{ color: C.sub, fontSize: 12 }}>{t('apiToken', lang)}</Text>
        <TextInput testID="api-token" value={tok} onChangeText={setTok} autoCapitalize="none" autoCorrect={false} secureTextEntry
          placeholder="••••••••" placeholderTextColor={C.muted}
          style={{ color: C.text, backgroundColor: C.cardAlt, borderRadius: 8, padding: 10, marginVertical: 4, minHeight: 44 }} />
        <Button testID="connect" title={t('connect', lang)} onPress={connect} loading={busy} />
        {result && <Text testID="connect-result" style={{ color: result.ok ? C.up : C.down, marginTop: 4 }}>{result.text}</Text>}
        {conn.lastError ? <Muted>{conn.lastError} · retries {conn.attempts}</Muted> : null}
        <Muted>{lang === 'ur' ? 'Token phone ke encrypted SecureStore mein mehfooz hota hai.' : 'The token is stored in the encrypted SecureStore on this phone.'}</Muted>
      </Card>

      {h && (
        <Card testID="health-card">
          <Title right={<Badge text={h.market.status.replace('_', ' ')} color={h.market.status === 'LIVE' ? C.up : h.market.status === 'DEMO_DATA' ? C.gold : C.warn} />}>{t('providerHealth', lang)}</Title>
          <Row label="Mode" value={String(h.config.dataMode)} />
          <Row label="Provider" value={h.market.provider.name} />
          <Row label="Connected" value={h.market.provider.connected ? 'yes' : 'no'} valueColor={h.market.provider.connected ? C.up : C.down} />
          <Row label="Last tick age" value={h.market.lastTickAgeMs >= 0 ? `${(h.market.lastTickAgeMs / 1000).toFixed(1)} s` : '—'} />
          <Row label="History" value={h.market.historyLoaded ? 'loaded' : 'loading'} />
          {!!h.market.historyError && <Muted style={{ color: C.down }}>{h.market.historyError}</Muted>}
          <Row label="Requests / 429s" value={`${h.market.provider.requests} / ${h.market.provider.rateLimitedResponses}`} />
          <Row label="Reconnects" value={String(h.market.provider.reconnects)} />
          {!!h.market.provider.lastError && <Muted style={{ color: C.warn }}>{h.market.provider.lastError} ({formatDateTime(h.market.provider.lastErrorAtMs, settings.timezone)})</Muted>}
          <Row label="Ticks accepted" value={String(h.market.sanitizer.accepted ?? 0)} />
          <Row label="Dropped dup/out-of-order/invalid/spike" value={`${h.market.sanitizer.duplicatesDropped ?? 0}/${h.market.sanitizer.outOfOrderDropped ?? 0}/${h.market.sanitizer.invalidDropped ?? 0}/${h.market.sanitizer.spikesQuarantined ?? 0}`} />
          <Row label="M1 gaps (24h) / largest" value={`${h.market.m1GapsLast24h} / ${h.market.largestGapMinutes} min`} />
          <Row label="Calendar" value={`${h.news.provider} · ${h.news.available ? 'OK' : 'unavailable'}`} />
          <Row label="AI" value={h.aiConfigured ? String(h.config.aiProvider) + ' ' + String(h.config.aiModel) : t('ruleBased', lang)} />
          <Row label="Server version / time" value={`${h.version} · ${formatDateTime(h.serverTimeMs, settings.timezone)}`} />
        </Card>
      )}

      <Card testID="app-settings">
        <Title>{t('appSettings', lang)}</Title>
        <Muted>{t('language', lang)}</Muted>
        <View style={s.wrap}>
          <Chip testID="lang-ur" label="Roman Urdu" active={lang === 'ur'} onPress={() => updateSettings({ language: 'ur' })} />
          <Chip testID="lang-en" label="English" active={lang === 'en'} onPress={() => updateSettings({ language: 'en' })} />
        </View>
        <Muted>{t('timezone', lang)}</Muted>
        <View style={s.wrap}>
          <Chip testID="tz-pkt" label="PKT (UTC+5)" active={settings.timezone === 'PKT'} onPress={() => updateSettings({ timezone: 'PKT' })} />
          <Chip testID="tz-utc" label="UTC" active={settings.timezone === 'UTC'} onPress={() => updateSettings({ timezone: 'UTC' })} />
        </View>
        <Muted>{t('defaultTf', lang)}</Muted>
        <View style={s.wrap}>
          {TIMEFRAMES.map((x) => <Chip key={x} label={x} active={settings.defaultTf === x} onPress={() => updateSettings({ defaultTf: x })} />)}
        </View>
        <Muted>{t('aiMode', lang)}</Muted>
        <View style={s.wrap}>
          <Chip label={t('quick', lang)} active={settings.aiMode === 'QUICK'} onPress={() => updateSettings({ aiMode: 'QUICK' })} />
          <Chip label={t('advanced', lang)} active={settings.aiMode === 'ADVANCED'} onPress={() => updateSettings({ aiMode: 'ADVANCED' })} />
        </View>
        <Muted>{t('chartAppearance', lang)}</Muted>
        <View style={s.wrap}>
          <Chip label="Classic" active={settings.chartTheme === 'classic'} onPress={() => updateSettings({ chartTheme: 'classic' })} />
          <Chip label="Gold" active={settings.chartTheme === 'gold'} onPress={() => updateSettings({ chartTheme: 'gold' })} />
        </View>
        <Toggle label={t('brokerCompare', lang)} value={settings.brokerCompare} onChange={(v) => updateSettings({ brokerCompare: v })} />
        <Muted>{lang === 'ur' ? 'Chart indicators Chart screen ke layers button se badlein.' : 'Chart indicators are toggled from the Chart screen layers button.'}</Muted>
      </Card>

      <Card>
        <Title>{t('alertPrefs', lang)}</Title>
        {(['signals', 'zones', 'news', 'spread', 'feed'] as const).map((k) => (
          <Toggle key={k} label={k} value={settings.alerts[k]} onChange={(v) => updateSettings({ alerts: { ...settings.alerts, [k]: v } })} />
        ))}
        <Row label={t('pushStatus', lang)} value={push.status} />
        {!!push.detail && <Muted>{push.detail}</Muted>}
        <Button kind="secondary" title={t('notifications', lang)} onPress={() => registerPush()} />
      </Card>

      {configured && <StrategySettingsCard lang={lang} />}

      <Card>
        <Title>{t('crashLog', lang)}</Title>
        {crashes.length === 0 && <Muted>{t('none', lang)}</Muted>}
        {crashes.map((c, i) => <Muted key={i}>{formatDateTime(c.timeMs, settings.timezone)} · {String(c.entry.message)}</Muted>)}
        <Row label={t('version', lang)} value={Constants.expoConfig?.version ?? '—'} />
      </Card>
      {conn.authFailed && <Banner text={t('authFailed', lang)} color={C.down} />}
      <Muted>{t('disclaimer', lang)}</Muted>
    </ScrollView>
  );
}
