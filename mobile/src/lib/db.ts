import * as SQLite from 'expo-sqlite';
import type { SignalEntry } from './types';

// On-device SQLite: settings, API response cache (offline view), signal journal mirror and crash log.
let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

function db(): Promise<SQLite.SQLiteDatabase> {
  if (!dbPromise) {
    dbPromise = (async () => {
      const d = await SQLite.openDatabaseAsync('aurum.db');
      await d.execAsync(`
        PRAGMA journal_mode = WAL;
        CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL, saved_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS journal (id TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL, state TEXT NOT NULL, json TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS crash_log (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, json TEXT NOT NULL);
      `);
      return d;
    })();
  }
  return dbPromise;
}

export async function getKv(key: string): Promise<string | null> {
  const r = await (await db()).getFirstAsync<{ value: string }>('SELECT value FROM kv WHERE key = ?', key);
  return r ? r.value : null;
}

export async function setKv(key: string, value: string): Promise<void> {
  await (await db()).runAsync(
    'INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at',
    key, value, Date.now());
}

export async function cachePut(key: string, json: string): Promise<void> {
  await (await db()).runAsync(
    'INSERT INTO cache (key, json, saved_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET json = excluded.json, saved_at = excluded.saved_at',
    key, json, Date.now());
}

export async function cacheGet(key: string): Promise<{ json: string; savedAt: number } | null> {
  const r = await (await db()).getFirstAsync<{ json: string; saved_at: number }>('SELECT json, saved_at FROM cache WHERE key = ?', key);
  return r ? { json: r.json, savedAt: r.saved_at } : null;
}

export async function journalUpsert(entries: SignalEntry[]): Promise<void> {
  const d = await db();
  await d.withTransactionAsync(async () => {
    for (const e of entries) {
      await d.runAsync(
        'INSERT INTO journal (id, created_at, state, json) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET state = excluded.state, json = excluded.json',
        e.signal.id, e.signal.createdAtMs, e.state, JSON.stringify(e));
    }
  });
}

export async function journalList(limit: number): Promise<SignalEntry[]> {
  const rows = await (await db()).getAllAsync<{ json: string }>('SELECT json FROM journal ORDER BY created_at DESC LIMIT ?', limit);
  return rows.map((r) => JSON.parse(r.json) as SignalEntry);
}

export async function crashAdd(entry: object): Promise<void> {
  await (await db()).runAsync('INSERT INTO crash_log (time_ms, json) VALUES (?, ?)', Date.now(), JSON.stringify(entry));
}

export async function crashList(limit: number): Promise<{ timeMs: number; entry: Record<string, unknown> }[]> {
  const rows = await (await db()).getAllAsync<{ time_ms: number; json: string }>('SELECT time_ms, json FROM crash_log ORDER BY id DESC LIMIT ?', limit);
  return rows.map((r) => ({ timeMs: r.time_ms, entry: JSON.parse(r.json) }));
}
