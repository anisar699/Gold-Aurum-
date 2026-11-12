import React, { useState } from 'react';
import { RefreshControl, ScrollView, View } from 'react-native';
import { ActionPanel, ConnectPrompt, DecisionHero, SnapshotCard, StatusBanners } from '../../components/trading';
import { Bullet, Card, Muted, Row, s, Title } from '../../components/ui';
import { C } from '../../components/theme';
import { useStore } from '../../lib/store';
import { refreshCore, useTicker } from '../../lib/hooks';
import { biasLabel, t, tx } from '../../lib/i18n';
import { formatPrice, formatSigned } from '../../lib/format';

/** Live gold intelligence: the current decision must be understandable within three seconds. */
export default function Dashboard() {
  const { settings, quote, decision, active, token } = useStore();
  const lang = settings.language;
  const now = useTicker(1000);
  const [refreshing, setRefreshing] = useState(false);
  const configured = !!settings.backendUrl && !!token;
  const p = quote?.precision ?? 2;
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}
      refreshControl={<RefreshControl refreshing={refreshing} tintColor={C.gold} onRefresh={async () => {
        setRefreshing(true);
        await refreshCore();
        setRefreshing(false);
      }} />}>
      {!configured ? <ConnectPrompt /> : <StatusBanners />}
      <DecisionHero decision={decision} active={active} now={now} />
      <SnapshotCard quote={quote} now={now} />
      <ActionPanel setup={decision?.setup ?? null} decision={decision} active={active} now={now} lang={lang} />
      <Card testID="intel-card">
        <Title>{t('marketIntel', lang)}</Title>
        <Row label={t('htfTrend', lang)} value={decision ? `H4 ${biasLabel(decision.h4Bias, lang)} · H1 ${biasLabel(decision.h1Bias, lang)}` : '—'} />
        <Row label={t('score', lang)} value={decision?.setup ? `${decision.score}/100 (${decision.setup.strategyName})` : '—'} />
        <View style={{ marginTop: 6 }}>
          <Muted>{t('nearbyLiquidity', lang)}</Muted>
          {decision?.nearbyLiquidity.slice(0, 6).map((n, i) => (
            <Row key={i} label={n.name.replace(/_/g, ' ')} value={`${formatPrice(n.price, p)} (${formatSigned(n.distance, p)})`}
              valueColor={n.distance > 0 ? C.down : C.up} mono />
          ))}
        </View>
        {!!decision?.risks.length && (
          <View style={{ marginTop: 6 }}>
            <Muted>{t('risks', lang)}</Muted>
            {decision.risks.map((r, i) => <Bullet key={i} color={C.warn}>{tx(r, lang)}</Bullet>)}
          </View>
        )}
        {decision && <Muted style={{ marginTop: 8 }}>{tx(decision.summary, lang)}</Muted>}
      </Card>
      <Muted>{t('disclaimer', lang)}</Muted>
    </ScrollView>
  );
}
