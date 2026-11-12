import React, { useEffect } from 'react';
import { View } from 'react-native';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { ErrorBoundary } from '../components/ErrorBoundary';
import { Loading } from '../components/ui';
import { C } from '../components/theme';
import { useStore } from '../lib/store';
import { useLiveConnection } from '../lib/hooks';
import { installGlobalHandler } from '../lib/crash';
import { t } from '../lib/i18n';

function Boot() {
  const ready = useStore((s) => s.ready);
  const lang = useStore((s) => s.settings.language);
  useEffect(() => {
    installGlobalHandler();
    useStore.getState().load();
  }, []);
  useLiveConnection();
  if (!ready) {
    return (
      <View style={{ flex: 1, backgroundColor: C.bg, justifyContent: 'center' }}>
        <Loading text="AURUM XAU" />
      </View>
    );
  }
  const header = { headerStyle: { backgroundColor: C.bg }, headerTintColor: C.gold, headerTitleStyle: { color: C.text } };
  return (
    <>
      <StatusBar style="light" />
      <Stack screenOptions={{ ...header, contentStyle: { backgroundColor: C.bg } }}>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="structure" options={{ title: t('structure', lang) }} />
        <Stack.Screen name="levels" options={{ title: t('levels', lang) }} />
        <Stack.Screen name="calendar" options={{ title: t('calendar', lang) }} />
        <Stack.Screen name="signals" options={{ title: t('signals', lang) }} />
        <Stack.Screen name="signal/[id]" options={{ title: t('auditHistory', lang) }} />
        <Stack.Screen name="performance" options={{ title: t('performance', lang) }} />
        <Stack.Screen name="settings" options={{ title: t('settings', lang) }} />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  return (
    <ErrorBoundary>
      <Boot />
    </ErrorBoundary>
  );
}
