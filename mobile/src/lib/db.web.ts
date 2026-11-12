import type { SignalEntry } from './types';

// Web fallback (used only for browser-based testing): localStorage with the same API as db.ts.
const ls = (): Storage | null => (typeof localStorage === 'undefined' ? null : localStorage);
const get = (k: string) => ls()?.getItem('aurum:' + k) ?? null;
const set = (k: string, v: string) => ls()?.setItem('aurum:' + k, v);

export async function getKv(key: string): Promise<string | null> {
  return get('kv:' + key);
}
export async function setKv(key: string, value: string): Promise<void> {
  set('kv:' + key, value);
}
export async function cachePut(key: string, json: string): Promise<void> {
  set('cache:' + key, JSON.stringify({ json, savedAt: Date.now() }));
}
export async function cacheGet(key: string): Promise<{ json: string; savedAt: number } | null> {
  const v = get('cache:' + key);
  return v ? JSON.parse(v) : null;
}
export async function journalUpsert(entries: SignalEntry[]): Promise<void> {
  const cur = await journalList(1000);
  const byId = new Map(cur.map((e) => [e.signal.id, e]));
  entries.forEach((e) => byId.set(e.signal.id, e));
  const all = [...byId.values()].sort((a, b) => b.signal.createdAtMs - a.signal.createdAtMs).slice(0, 1000);
  set('journal', JSON.stringify(all));
}
export async function journalList(limit: number): Promise<SignalEntry[]> {
  const v = get('journal');
  return v ? (JSON.parse(v) as SignalEntry[]).slice(0, limit) : [];
}
export async function crashAdd(entry: object): Promise<void> {
  const v = get('crash');
  const list = v ? JSON.parse(v) : [];
  list.unshift({ timeMs: Date.now(), entry });
  set('crash', JSON.stringify(list.slice(0, 50)));
}
export async function crashList(limit: number): Promise<{ timeMs: number; entry: Record<string, unknown> }[]> {
  const v = get('crash');
  return v ? JSON.parse(v).slice(0, limit) : [];
}
