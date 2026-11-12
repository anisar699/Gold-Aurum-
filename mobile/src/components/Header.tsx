import React from 'react';
import { useTicker } from '../lib/hooks';
import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useStore } from '../lib/store';
import { displayStatus, statusColor } from '../lib/freshness';
import { formatAge, formatClock } from '../lib/format';
import { statusLabel } from '../lib/i18n';
import { C } from './theme';

/** Brand + live connection status + session + Pakistan time. Re-renders every second so ages stay accurate. */
export function Header() {
  const insets = useSafeAreaInsets();
  const { quote, conn, serverOffsetMs, settings } = useStore();
  const now = useTicker(1000);
  const status = displayStatus(quote, conn, now, serverOffsetMs);
  const age = quote?.timeMs ? now + serverOffsetMs - quote.timeMs : null;
  return (
    <View style={[st.wrap, { paddingTop: insets.top + 6 }]}>
      <View style={st.left}>
        <Text style={st.brand}>AURUM <Text style={st.xau}>XAU</Text></Text>
        <Text style={st.pair}>XAU/USD · {quote?.session?.replace(/_/g, ' ') ?? '—'}</Text>
      </View>
      <View style={st.right}>
        <View testID="feed-status" style={[st.status, { backgroundColor: statusColor(status) }]}>
          <Text style={st.statusText}>{statusLabel(status)}</Text>
        </View>
        <Text style={st.clock}>{formatClock(now, settings.timezone)}</Text>
        <Text style={st.age}>{age !== null ? 'quote ' + formatAge(age) : conn.wsOpen ? 'connected' : 'offline'}</Text>
      </View>
    </View>
  );
}

const st = StyleSheet.create({
  wrap: { backgroundColor: C.bg, paddingHorizontal: 12, paddingBottom: 8, flexDirection: 'row', justifyContent: 'space-between',
    borderBottomWidth: 1, borderBottomColor: C.border },
  left: { flexShrink: 1 },
  brand: { color: C.gold, fontSize: 18, fontWeight: '900', letterSpacing: 1 },
  xau: { color: C.text },
  pair: { color: C.sub, fontSize: 11, marginTop: 2 },
  right: { alignItems: 'flex-end' },
  status: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 5 },
  statusText: { color: '#0B0B0D', fontSize: 11, fontWeight: '900' },
  clock: { color: C.text, fontSize: 12, marginTop: 3, fontVariant: ['tabular-nums'] },
  age: { color: C.muted, fontSize: 10, fontVariant: ['tabular-nums'] },
});
