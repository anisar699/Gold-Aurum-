import React from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { router, type Href } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { s } from '../../components/ui';
import { C } from '../../components/theme';
import { useStore } from '../../lib/store';
import { t, type StringKey } from '../../lib/i18n';

type IconName = React.ComponentProps<typeof Ionicons>['name'];
const ITEMS: { key: StringKey; href: Href; icon: IconName; testID: string }[] = [
  { key: 'structure', href: '/structure', icon: 'git-network-outline', testID: 'nav-structure' },
  { key: 'levels', href: '/levels', icon: 'reorder-four-outline', testID: 'nav-levels' },
  { key: 'calendar', href: '/calendar', icon: 'calendar-outline', testID: 'nav-calendar' },
  { key: 'signals', href: '/signals', icon: 'list-outline', testID: 'nav-signals' },
  { key: 'performance', href: '/performance', icon: 'trending-up-outline', testID: 'nav-performance' },
  { key: 'settings', href: '/settings', icon: 'settings-outline', testID: 'nav-settings' },
];

export default function More() {
  const lang = useStore((x) => x.settings.language);
  return (
    <ScrollView style={s.screen} contentContainerStyle={s.content}>
      {ITEMS.map((it) => (
        <Pressable key={it.key} testID={it.testID} accessibilityRole="button" onPress={() => router.push(it.href)}
          style={({ pressed }) => [st.item, pressed && { opacity: 0.7 }]}>
          <Ionicons name={it.icon} size={22} color={C.gold} />
          <Text style={st.text}>{t(it.key, lang)}</Text>
          <View style={{ flex: 1 }} />
          <Ionicons name="chevron-forward" size={18} color={C.muted} />
        </Pressable>
      ))}
    </ScrollView>
  );
}

const st = StyleSheet.create({
  item: { flexDirection: 'row', alignItems: 'center', gap: 12, backgroundColor: C.card, borderRadius: 12, padding: 16, marginBottom: 10,
    borderWidth: 1, borderColor: C.border, minHeight: 56 },
  text: { color: C.text, fontSize: 16, fontWeight: '600' },
});
