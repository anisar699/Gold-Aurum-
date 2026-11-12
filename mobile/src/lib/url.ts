/**
 * Backend URL validation. HTTPS is required for public hosts. Plain HTTP is only accepted for
 * private/local network addresses (home Wi-Fi backends), because the API token would otherwise travel in clear text.
 */
export interface UrlCheck { ok: boolean; url?: string; insecureLan?: boolean; error?: string }

export function isPrivateHost(host: string): boolean {
  const h = host.toLowerCase();
  if (h === 'localhost' || h.endsWith('.local')) return true;
  const m = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (!m) return false;
  const [a, b] = [Number(m[1]), Number(m[2])];
  return a === 10 || a === 127 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 100 && b >= 64 && b <= 127);
}

export function checkBackendUrl(input: string): UrlCheck {
  const raw = input.trim().replace(/\/+$/, '');
  if (!raw) return { ok: false, error: 'empty' };
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return { ok: false, error: 'invalid' };
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return { ok: false, error: 'scheme' };
  if (u.protocol === 'http:' && !isPrivateHost(u.hostname)) return { ok: false, error: 'https_required' };
  return { ok: true, url: raw, insecureLan: u.protocol === 'http:' };
}

export function wsUrl(base: string, token: string): string {
  return base.replace(/^http/, 'ws') + '/ws?token=' + encodeURIComponent(token);
}
