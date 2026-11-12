import React, { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { router } from 'expo-router';
import { Badge, Banner, Button, Card, Chip, Loading, Muted, s } from '../components/ui';
import { C, stateColor } from '../components/theme';
import { ConnectPrompt } from '../components/trading';
import { useStore } from '../lib/store';
import { useApi } from '../lib/hooks';
import * as db from '../lib/db';
import { t } from '../lib/i18n';
import { formatDateTime, formatPrice, formatR } from '../lib/format';
import { exportCsv, exportPdf } from '../lib/export';
import type { SignalEntry } from '../lib/types';


/** Complete chronological ledger, including rejected and expired setups. */
export default function Signals() {
  const { settings, token } = useStore();
  const lang = settings.language;
  const [filter, setFilter] = useState<'ALL' | 'ISSUED' | 'REJECTED'>('ALL');
  const [msg, setMsg] = useState<string | null>(null);
  const r = useApi<SignalEntry[]>('/api/signals?limit=300');
  const [local, setLocal] = useState<SignalEntry[] | null>(null);

  useEffect(() => {
    if (r.data) db.journalUpsert(r.data).catch(() => undefined);
    else if (r.error) db.journalList(300).then(setLocal).catch(() => undefined);
  }, [r.data, r.error]);

  if (!settings.backendUrl || !token) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  const all = r.data ?? local ?? [];
  const list = all.filter((e) => filter === 'ALL' || (filter === 'REJECTED' ? e.state === 'REJECTED' : e.state !== 'REJECTED'));
  const source = all[0]?.signal.source ?? 'PAPER';
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      {r.cachedAt && <Banner text={`${t('cachedData', lang)} ${formatDateTime(r.cachedAt, settings.timezone)}`} color={C.warn} />}
      {!r.data && local && <Banner text={`${t('cachedData', lang)} (journal)`} color={C.warn} />}
      <View style={s.wrap}>
        {(['ALL', 'ISSUED', 'REJECTED'] as const).map((f) => (
          <Chip key={f} label={f === 'ALL' ? t('all', lang) : f === 'REJECTED' ? t('rejected', lang) : 'BUY/SELL'} active={filter === f} onPress={() => setFilter(f)} />
        ))}
      </View>
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <View style={{ flex: 1 }}>
          <Button kind="secondary" title={t('exportCsv', lang)} onPress={() => exportCsv(source).then(() => setMsg('CSV ✓')).catch((e) => setMsg(String(e)))} />
        </View>
        <View style={{ flex: 1 }}>
          <Button kind="secondary" title={t('exportPdf', lang)} onPress={() => exportPdf(t('signals', 'en'), null, list).catch((e) => setMsg(String(e)))} />
        </View>
      </View>
      {msg && <Muted>{msg}</Muted>}
      {r.loading && !all.length && <Loading text={t('loading', lang)} />}
      {!r.loading && !list.length && <Muted style={{ marginTop: 12 }}>{t('noSignals', lang)}</Muted>}
      {list.map((e) => (
        <Pressable key={e.signal.id} testID="signal-row" onPress={() => router.push(`/signal/${e.signal.id}`)} accessibilityRole="button">
          <Card>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
              <Text style={{ color: e.signal.direction === 'LONG' ? C.up : C.down, fontWeight: '800', fontSize: 16 }}>
                {e.signal.direction === 'LONG' ? 'BUY' : 'SELL'} · {e.signal.strategy}
              </Text>
              <Badge text={e.state} color={stateColor(e.state)} />
            </View>
            <Text style={{ color: C.sub, fontSize: 12, marginTop: 4 }}>
              {formatDateTime(e.signal.createdAtMs, settings.timezone)} · score {e.signal.score} · {e.signal.source}
            </Text>
            <Text style={{ color: C.text, fontSize: 13, marginTop: 4, fontVariant: ['tabular-nums'] }}>
              E {formatPrice(e.signal.entryRef, 2)} · SL {formatPrice(e.signal.stopLoss, 2)} · TP2 {formatPrice(e.signal.tp2, 2)}
              {e.result?.r != null ? ` · ${formatR(e.result.r)}` : ''}
            </Text>
          </Card>
        </Pressable>
      ))}
    </ScrollView>
  );
}
