// Pure formatting helpers. Prices are shown at the source's supported precision only (never extra digits).
// Time zone: Pakistan Standard Time is a fixed UTC+05:00 (no daylight saving), so it is computed exactly.

export type TimeZoneMode = 'PKT' | 'UTC';
const PKT_OFFSET_MS = 5 * 60 * 60 * 1000;

export function formatPrice(v: number | null | undefined, precision: number): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return v.toFixed(Math.max(0, Math.min(5, precision)));
}

export function formatSigned(v: number | null | undefined, digits: number): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return (v > 0 ? '+' : '') + v.toFixed(digits);
}

export function formatPct(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return (v * 100).toFixed(digits) + '%';
}

export function formatR(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—';
  return (v > 0 ? '+' : '') + v.toFixed(2) + 'R';
}

function shift(ms: number, tz: TimeZoneMode): Date {
  return new Date(ms + (tz === 'PKT' ? PKT_OFFSET_MS : 0));
}

const p2 = (n: number) => (n < 10 ? '0' + n : String(n));

/** "14:05:09 PKT" */
export function formatClock(ms: number, tz: TimeZoneMode, seconds = true): string {
  const d = shift(ms, tz);
  const t = p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()) + (seconds ? ':' + p2(d.getUTCSeconds()) : '');
  return `${t} ${tz}`;
}

/** "12 Nov 14:05 PKT" */
export function formatDateTime(ms: number | null | undefined, tz: TimeZoneMode): string {
  if (!ms || !Number.isFinite(ms)) return '—';
  const d = shift(ms, tz);
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${p2(d.getUTCDate())} ${months[d.getUTCMonth()]} ${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())} ${tz}`;
}

/** Precise human age of a quote: "0.8 s", "42 s", "3 min 05 s", "2 h 10 min". */
export function formatAge(ageMs: number | null | undefined): string {
  if (ageMs === null || ageMs === undefined || !Number.isFinite(ageMs)) return '—';
  const a = Math.max(0, ageMs);
  if (a < 10_000) return (a / 1000).toFixed(1) + ' s';
  if (a < 60_000) return Math.round(a / 1000) + ' s';
  if (a < 3_600_000) {
    const m = Math.floor(a / 60_000);
    return `${m} min ${p2(Math.floor((a % 60_000) / 1000))} s`;
  }
  if (a < 86_400_000) return `${Math.floor(a / 3_600_000)} h ${Math.floor((a % 3_600_000) / 60_000)} min`;
  return `${Math.floor(a / 86_400_000)} d ${Math.floor((a % 86_400_000) / 3_600_000)} h`;
}

export function formatCountdown(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0:00';
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${p2(s % 60)}`;
}
