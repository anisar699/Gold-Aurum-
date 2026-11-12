import { useStore } from './store';
import * as db from './db';

export class ApiError extends Error {
  constructor(public status: number, message: string, public offline = false) {
    super(message);
  }
}

export interface ApiResult<T> {
  data: T;
  cachedAt: number | null; // non-null when served from the offline cache
}

const TIMEOUT_MS = 15_000;

async function request<T>(method: string, path: string, body?: unknown, timeoutMs = TIMEOUT_MS): Promise<T> {
  const { settings, token } = useStore.getState();
  if (!settings.backendUrl) throw new ApiError(0, 'Backend not configured', true);
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  let res: Response;
  try {
    res = await fetch(settings.backendUrl + path, {
      method,
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
      signal: ctrl.signal,
    });
  } catch (e) {
    useStore.getState().setConn({ restOk: false, lastError: String((e as Error).message ?? e) });
    throw new ApiError(0, (e as Error).name === 'AbortError' ? 'Request timed out' : 'Network error', true);
  } finally {
    clearTimeout(timer);
  }
  if (res.status === 401) {
    useStore.getState().setConn({ authFailed: true, restOk: false });
    throw new ApiError(401, 'API token rejected');
  }
  useStore.getState().setConn({ restOk: true, authFailed: false });
  const text = await res.text();
  if (!res.ok) {
    let msg = text;
    try {
      msg = JSON.parse(text).error ?? text;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, msg);
  }
  return (text ? JSON.parse(text) : null) as T;
}

/** GET with offline fallback to the last cached response (clearly flagged via cachedAt). */
export async function getCached<T>(path: string): Promise<ApiResult<T>> {
  try {
    const data = await request<T>('GET', path);
    db.cachePut(path, JSON.stringify(data)).catch(() => undefined);
    return { data, cachedAt: null };
  } catch (e) {
    if (e instanceof ApiError && e.offline) {
      const c = await db.cacheGet(path).catch(() => null);
      if (c) return { data: JSON.parse(c.json) as T, cachedAt: c.savedAt };
    }
    throw e;
  }
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown, timeoutMs?: number) => request<T>('POST', path, body, timeoutMs),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  /** Raw text GET (CSV export). */
  text: async (path: string): Promise<string> => {
    const { settings, token } = useStore.getState();
    const res = await fetch(settings.backendUrl + path, { headers: { Authorization: 'Bearer ' + token } });
    if (!res.ok) throw new ApiError(res.status, 'HTTP ' + res.status);
    return res.text();
  },
};

/** Unauthenticated health probe used by the Connect button. */
export async function probe(url: string, token: string): Promise<{ ok: boolean; status: number; message: string }> {
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 10_000);
    const res = await fetch(url + '/api/health/full', { headers: { Authorization: 'Bearer ' + token }, signal: ctrl.signal });
    clearTimeout(timer);
    if (res.status === 401) return { ok: false, status: 401, message: 'token' };
    return { ok: res.ok, status: res.status, message: res.ok ? 'ok' : 'HTTP ' + res.status };
  } catch (e) {
    return { ok: false, status: 0, message: String((e as Error).message ?? e) };
  }
}
