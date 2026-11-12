import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';
import { C } from './theme';

export function Card({ children, style, testID }: { children: React.ReactNode; style?: StyleProp<ViewStyle>; testID?: string }) {
  return <View testID={testID} style={[s.card, style]}>{children}</View>;
}

export function Title({ children, right }: { children: React.ReactNode; right?: React.ReactNode }) {
  return (
    <View style={s.titleRow}>
      <Text style={s.title}>{children}</Text>
      {right}
    </View>
  );
}

export function Row({ label, value, valueColor, mono, testID }: { label: string; value: React.ReactNode; valueColor?: string; mono?: boolean; testID?: string }) {
  return (
    <View style={s.row}>
      <Text style={s.label}>{label}</Text>
      {typeof value === 'string' || typeof value === 'number' ? (
        <Text testID={testID} style={[s.value, mono && s.mono, valueColor ? { color: valueColor } : null]}>{value}</Text>
      ) : (
        value
      )}
    </View>
  );
}

export function Badge({ text, color, testID, big }: { text: string; color: string; testID?: string; big?: boolean }) {
  return (
    <View testID={testID} style={[s.badge, { backgroundColor: color }, big && s.badgeBig]}>
      <Text style={[s.badgeText, big && s.badgeTextBig]}>{text}</Text>
    </View>
  );
}

export function Chip({ label, active, onPress, testID }: { label: string; active?: boolean; onPress?: () => void; testID?: string }) {
  return (
    <Pressable testID={testID} onPress={onPress} accessibilityRole="button" accessibilityState={{ selected: !!active }}
      style={[s.chip, active && s.chipActive]} hitSlop={6}>
      <Text style={[s.chipText, active && s.chipTextActive]}>{label}</Text>
    </Pressable>
  );
}

export function Button({ title, onPress, kind = 'primary', disabled, testID, loading }: {
  title: string; onPress: () => void; kind?: 'primary' | 'secondary' | 'danger'; disabled?: boolean; testID?: string; loading?: boolean;
}) {
  const bg = kind === 'primary' ? C.gold : kind === 'danger' ? C.down : C.cardAlt;
  return (
    <Pressable testID={testID} onPress={onPress} disabled={disabled || loading} accessibilityRole="button"
      style={({ pressed }) => [s.btn, { backgroundColor: bg, opacity: disabled ? 0.4 : pressed ? 0.8 : 1 }]}>
      {loading ? <ActivityIndicator color={kind === 'primary' ? C.bg : C.text} /> :
        <Text style={[s.btnText, { color: kind === 'primary' ? C.bg : C.text }]}>{title}</Text>}
    </Pressable>
  );
}

export function Banner({ text, color, testID }: { text: string; color: string; testID?: string }) {
  return (
    <View testID={testID} style={[s.banner, { borderColor: color }]}>
      <Text style={[s.bannerText, { color }]}>{text}</Text>
    </View>
  );
}

export function Muted({ children, style, testID }: { children: React.ReactNode; style?: StyleProp<TextStyle>; testID?: string }) {
  return <Text testID={testID} style={[s.muted, style]}>{children}</Text>;
}

export function Body({ children, style, testID }: { children: React.ReactNode; style?: StyleProp<TextStyle>; testID?: string }) {
  return <Text testID={testID} style={[s.body, style]}>{children}</Text>;
}

export function Loading({ text }: { text: string }) {
  return (
    <View style={s.loading}>
      <ActivityIndicator color={C.gold} />
      <Text style={s.muted}>{text}</Text>
    </View>
  );
}

export function Bullet({ children, color }: { children: React.ReactNode; color?: string }) {
  return (
    <View style={s.bulletRow}>
      <Text style={[s.bullet, color ? { color } : null]}>•</Text>
      <Text style={s.bulletText}>{children}</Text>
    </View>
  );
}

export const s = StyleSheet.create({
  card: { backgroundColor: C.card, borderRadius: 12, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: C.border },
  titleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  title: { color: C.gold, fontSize: 13, fontWeight: '700', letterSpacing: 0.6, textTransform: 'uppercase' },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 5 },
  label: { color: C.sub, fontSize: 13, flexShrink: 1, marginRight: 8 },
  value: { color: C.text, fontSize: 14, fontWeight: '600', textAlign: 'right', flexShrink: 1 },
  mono: { fontVariant: ['tabular-nums'] },
  badge: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6, alignSelf: 'flex-start' },
  badgeBig: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 8 },
  badgeText: { color: '#0B0B0D', fontSize: 11, fontWeight: '800' },
  badgeTextBig: { fontSize: 22 },
  chip: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 16, backgroundColor: C.cardAlt, marginRight: 6, marginBottom: 6,
    borderWidth: 1, borderColor: C.border, minHeight: 36, justifyContent: 'center' },
  chipActive: { backgroundColor: C.gold, borderColor: C.gold },
  chipText: { color: C.sub, fontSize: 13, fontWeight: '600' },
  chipTextActive: { color: C.bg },
  btn: { minHeight: 46, borderRadius: 10, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16, marginVertical: 4 },
  btnText: { fontSize: 15, fontWeight: '700' },
  banner: { borderWidth: 1, borderRadius: 10, padding: 10, marginBottom: 12, backgroundColor: '#17120A' },
  bannerText: { fontSize: 13, fontWeight: '700' },
  muted: { color: C.muted, fontSize: 12, lineHeight: 17 },
  body: { color: C.text, fontSize: 14, lineHeight: 21 },
  loading: { padding: 24, alignItems: 'center', gap: 8 },
  bulletRow: { flexDirection: 'row', marginVertical: 2 },
  bullet: { color: C.gold, marginRight: 6, fontSize: 14 },
  bulletText: { color: C.text, fontSize: 13, lineHeight: 19, flex: 1 },
  screen: { flex: 1, backgroundColor: C.bg },
  content: { padding: 12, paddingBottom: 40 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap' },
});
