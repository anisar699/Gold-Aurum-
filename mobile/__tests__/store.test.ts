jest.mock('expo-sqlite', () => ({ openDatabaseAsync: jest.fn() }));
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(async () => ''), setItemAsync: jest.fn(), deleteItemAsync: jest.fn() }));

import { useStore } from '../src/lib/store';
import type { AlertMsg, Decision, Quote } from '../src/lib/types';

const alert = (key: string): AlertMsg => ({
  dedupeKey: key, type: 'SIGNAL_ARMED', severity: 'info', timeMs: 1, title: { code: 'a', en: 'a', ur: 'a' },
  body: { code: 'b', en: 'b', ur: 'b' }, signalId: 'S1',
});

describe('store message reducer', () => {
  test('quote, decision, alert dedupe and candle version', () => {
    const st = useStore.getState();
    st.applyMessage({ type: 'quote', ts: Date.now() + 5_000, data: { status: 'LIVE', bid: 1, ask: 2 } as unknown as Quote });
    expect(useStore.getState().quote?.bid).toBe(1);
    expect(useStore.getState().serverOffsetMs).toBeGreaterThan(4_000);
    expect(useStore.getState().conn.lastMessageAtMs).toBeGreaterThan(0);

    st.applyMessage({ type: 'alert', ts: Date.now(), data: alert('k1') });
    st.applyMessage({ type: 'alert', ts: Date.now(), data: alert('k1') });
    st.applyMessage({ type: 'alert', ts: Date.now(), data: alert('k2') });
    expect(useStore.getState().alerts.map((a) => a.dedupeKey)).toEqual(['k2', 'k1']);

    const v = useStore.getState().candleVersion;
    st.applyMessage({ type: 'candle', ts: Date.now(), data: {} });
    expect(useStore.getState().candleVersion).toBe(v + 1);

    st.applyMessage({ type: 'decision', ts: Date.now(), data: { action: 'WAIT', activeSignalId: null } as unknown as Decision });
    expect(useStore.getState().decision?.action).toBe('WAIT');
    expect(useStore.getState().active).toBeNull();
  });

  test('offlineSince set when both links fail, cleared on recovery', () => {
    const st = useStore.getState();
    st.setConn({ wsOpen: false, restOk: false });
    expect(useStore.getState().offlineSince).not.toBeNull();
    st.setConn({ restOk: true });
    expect(useStore.getState().offlineSince).toBeNull();
  });
});
