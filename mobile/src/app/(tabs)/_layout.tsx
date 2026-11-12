import React from 'react';
import type { ColorValue } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Tabs } from 'expo-router/js-tabs';
import { Header } from '../../components/Header';
import { C } from '../../components/theme';
import { useStore } from '../../lib/store';
import { t } from '../../lib/i18n';

type IconName = React.ComponentProps<typeof Ionicons>['name'];

export default function TabLayout() {
  const lang = useStore((s) => s.settings.language);
  const icon = (name: IconName) => function TabIcon({ color, size }: { color: ColorValue; size: number }) {
    return <Ionicons name={name} color={color as string} size={size} />;
  };
  return (
    <Tabs
      screenOptions={{
        header: () => <Header />,
        tabBarActiveTintColor: C.gold,
        tabBarInactiveTintColor: C.muted,
        tabBarStyle: { backgroundColor: '#0E0E11', borderTopColor: C.border, height: 62, paddingBottom: 8, paddingTop: 6 },
        tabBarLabelStyle: { fontSize: 11, fontWeight: '600' },
        sceneStyle: { backgroundColor: C.bg },
      }}>
      <Tabs.Screen name="index" options={{ title: t('dashboard', lang), tabBarIcon: icon('speedometer-outline'), tabBarButtonTestID: 'tab-dashboard' }} />
      <Tabs.Screen name="chart" options={{ title: t('chart', lang), tabBarIcon: icon('stats-chart-outline'), tabBarButtonTestID: 'tab-chart' }} />
      <Tabs.Screen name="radar" options={{ title: t('radar', lang), tabBarIcon: icon('radio-outline'), tabBarButtonTestID: 'tab-radar' }} />
      <Tabs.Screen name="ai" options={{ title: t('ai', lang), tabBarIcon: icon('sparkles-outline'), tabBarButtonTestID: 'tab-ai' }} />
      <Tabs.Screen name="more" options={{ title: t('more', lang), tabBarIcon: icon('grid-outline'), tabBarButtonTestID: 'tab-more' }} />
    </Tabs>
  );
}
