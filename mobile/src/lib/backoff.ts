/** Exponential backoff with full jitter, capped. attempt starts at 0. */
export function backoffDelay(attempt: number, baseMs = 1000, maxMs = 30_000, random: () => number = Math.random): number {
  const exp = Math.min(maxMs, baseMs * Math.pow(2, Math.max(0, attempt)));
  return Math.round(exp / 2 + random() * (exp / 2));
}
