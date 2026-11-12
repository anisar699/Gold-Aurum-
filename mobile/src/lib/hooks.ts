import { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { api, ApiError, getCached } from './api';
import { connection } from './ws';
import { useStore } from './store';
import { notifyAlert, notifyLocal, initNotifications } from './notifications';
import type { ActiveSignal, AlertMsg, Decision, Quote } from './types';
import { t } from './i18n';

export async function refreshCore(): Promise<void> {
  try {
    const q = await api.get<Quote>('/api/quote');
    useStore.getState().setQuote(q);
  } catch {
    /* connection state is tracked by the api layer */
  }
  try {
    const d = await api.get<{ decision: Decision; activeSignal?: ActiveSignal }>('/api/decision');
    useStore.getState().setDecision(d.decision, d.activeSignal ?? null);
  } catch {
    /* 503 while history loads, or offline */
  }
}

/** Starts/stops the live connection whenever the backend configuration changes. */
export function useLiveConnection(): void {
  const ready = useStore((s) => s.ready);
  const url = useStore((s) => s.settings.backendUrl);
  const token = useStore((s) => s.token);
  useEffect(() => {
    if (!ready || !url || !token) {
      connection.stop();
      return;
    }
    connection.restart();
    refreshCore();
    initNotifications().catch(() => undefined);
    const unsub = connection.subscribe((m) => {
      if (m.type === 'alert') notifyAlert(m.data as AlertMsg).catch(() => undefined);
      if (m.type === 'hello' || m.type === 'signal') refreshCore();
    });
    const id = setInterval(refreshCore, 30_000);
    return () => {
      unsub();
      clearInterval(id);
      connection.stop();
    };
  }, [ready, url, token]);

  // Warn the user when the backend is unreachable or rejects the token: live signals are no longer valid.
  const offlineSince = useStore((s) => s.offlineSince);
  const authFailed = useStore((s) => s.conn.authFailed);
  const lang = useStore((s) => s.settings.language);
  useEffect(() => {
    if (!url || !token) return;
    if (authFailed) {
      notifyLocal('auth', 'AURUM XAU', t('authFailed', lang)).catch(() => undefined);
      return;
    }
    if (!offlineSince) return;
    const id = setTimeout(() => {
      if (useStore.getState().offlineSince === offlineSince) {
        notifyLocal('offline:' + offlineSince, 'AURUM XAU', t('backendOffline', lang)).catch(() => undefined);
      }
    }, 30_000);
    return () => clearTimeout(id);
  }, [offlineSince, authFailed, url, token, lang]);
}

export interface ApiState<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  cachedAt: number | null;
  reload: () => void;
}

/** Fetches on focus (and when deps change) with offline-cache fallback. */
export function useApi<T>(path: string | null, deps: unknown[] = []): ApiState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [cachedAt, setCachedAt] = useState<number | null>(null);
  const alive = useRef(true);
  const configured = useStore((s) => !!s.settings.backendUrl && !!s.token);

  const depsKey = JSON.stringify(deps);
  const load = useCallback(async () => {
    if (!path || !configured) return;
    setLoading(true);
    try {
      const r = await getCached<T>(path);
      if (!alive.current) return;
      setData(r.data);
      setCachedAt(r.cachedAt);
      setError(null);
    } catch (e) {
      if (!alive.current) return;
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      if (alive.current) setLoading(false);
    }
    // depsKey is an intentional extra trigger for reloads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, configured, depsKey]);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load]),
  );

  return { data, error, loading, cachedAt, reload: load };
}

/** Re-render every `ms` (for countdowns and quote ages). */
export function useTicker(ms = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}
