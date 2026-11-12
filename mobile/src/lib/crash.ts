import { Platform } from 'react-native';
import Constants from 'expo-constants';
import * as db from './db';
import { api } from './api';

/** Records a crash locally (always) and best-effort to the backend's /api/client-errors. */
export function reportCrash(error: unknown, extra: Record<string, string> = {}): void {
  const e = error instanceof Error ? error : new Error(String(error));
  const entry = {
    message: e.message,
    stack: (e.stack ?? '').slice(0, 4000),
    platform: Platform.OS,
    appVersion: Constants.expoConfig?.version ?? 'unknown',
    timeMs: Date.now(),
    ...extra,
  };
  db.crashAdd(entry).catch(() => undefined);
  api.post('/api/client-errors', entry).catch(() => undefined);
}

let installed = false;

/** Global JS error handler (uncaught exceptions outside React rendering). */
export function installGlobalHandler(): void {
  if (installed) return;
  installed = true;
  const g = globalThis as unknown as { ErrorUtils?: { getGlobalHandler: () => (e: unknown, fatal?: boolean) => void; setGlobalHandler: (h: (e: unknown, fatal?: boolean) => void) => void } };
  if (!g.ErrorUtils) return;
  const prev = g.ErrorUtils.getGlobalHandler();
  g.ErrorUtils.setGlobalHandler((err, fatal) => {
    reportCrash(err, { fatal: String(!!fatal) });
    prev(err, fatal);
  });
}
