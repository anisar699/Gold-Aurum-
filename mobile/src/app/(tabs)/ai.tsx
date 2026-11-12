import React, { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, Text, TextInput, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { ConnectPrompt, StatusBanners } from '../../components/trading';
import { Banner, Body, Button, Card, Chip, Muted, s, Title } from '../../components/ui';
import { C } from '../../components/theme';
import { useStore } from '../../lib/store';
import { api, ApiError } from '../../lib/api';
import { t } from '../../lib/i18n';
import { formatDateTime } from '../../lib/format';
import { TIMEFRAMES, type AiAnswer, type Timeframe } from '../../lib/types';

/** AI analyst: explanations generated only from the engine's verified, timestamped data. */
export default function AiScreen() {
  const params = useLocalSearchParams<{ tf?: string }>();
  const { settings, token } = useStore();
  const lang = settings.language;
  const [mode, setMode] = useState<'QUICK' | 'ADVANCED'>(settings.aiMode);
  const [tf, setTf] = useState<Timeframe>((TIMEFRAMES as string[]).includes(params.tf ?? '') ? (params.tf as Timeframe) : settings.defaultTf);
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<AiAnswer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const configured = !!settings.backendUrl && !!token;

  const ask = async (question: string, includePerformance = false) => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.post<AiAnswer>('/api/ai/ask', { question, mode, language: lang, timeframe: tf, includePerformance }, 90_000);
      setAnswer(r);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  if (!configured) return <ScrollView style={s.screen} contentContainerStyle={s.content}><ConnectPrompt /></ScrollView>;
  return (
    <KeyboardAvoidingView style={s.screen} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
        <StatusBanners />
        <Card>
          <Title>{t('ai', lang)}</Title>
          <View style={s.wrap}>
            <Chip testID="ai-quick" label={t('quick', lang)} active={mode === 'QUICK'} onPress={() => setMode('QUICK')} />
            <Chip testID="ai-advanced" label={t('advanced', lang)} active={mode === 'ADVANCED'} onPress={() => setMode('ADVANCED')} />
          </View>
          <Muted>{t('timeframe', lang)} ({t('askAiChart', lang)})</Muted>
          <View style={[s.wrap, { marginTop: 4 }]}>
            {TIMEFRAMES.map((x) => <Chip key={x} label={x} active={x === tf} onPress={() => setTf(x)} />)}
          </View>
          <TextInput
            testID="ai-input"
            value={q}
            onChangeText={setQ}
            placeholder={t('askPlaceholder', lang)}
            placeholderTextColor={C.muted}
            multiline
            maxLength={1000}
            style={{ color: C.text, backgroundColor: C.cardAlt, borderRadius: 10, padding: 10, minHeight: 70, marginTop: 6, textAlignVertical: 'top' }}
          />
          <Button testID="ai-send" title={t('send', lang)} onPress={() => ask(q)} disabled={!q.trim()} loading={busy} />
          <Button testID="ai-explain" kind="secondary" title={t('explainNow', lang)} onPress={() => ask('')} loading={busy} />
          <Button testID="ai-review" kind="secondary" title={t('reviewSignals', lang)} onPress={() => ask('', true)} loading={busy} />
          <Muted>{t('aiDisclaimer', lang)}</Muted>
        </Card>
        {error && <Banner text={error} color={C.down} />}
        {answer && (
          <Card testID="ai-answer">
            <Title right={<Text style={{ color: answer.fallback ? C.warn : C.gold, fontSize: 11, fontWeight: '700' }}>
              {answer.fallback ? t('ruleBased', lang) : `${answer.engine} · ${answer.model}`}</Text>}>
              {answer.mode}
            </Title>
            {answer.warnings.map((w, i) => <Banner key={i} text={w} color={C.warn} />)}
            {answer.unverifiedNumbers.length > 0 && (
              <Banner text={`${t('unverified', lang)}: ${answer.unverifiedNumbers.join(', ')}`} color={C.down} />
            )}
            <Body testID="ai-text">{answer.text}</Body>
            <Muted style={{ marginTop: 8 }}>
              {formatDateTime(answer.generatedAtMs, settings.timezone)} · data {formatDateTime(answer.dataAsOfMs, settings.timezone)} · {answer.feedStatus}
            </Muted>
          </Card>
        )}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
