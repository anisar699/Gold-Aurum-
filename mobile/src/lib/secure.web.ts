// Web build is for testing only; it keeps the token in sessionStorage (cleared when the tab closes).
const KEY = 'aurum_api_token';

export async function getToken(): Promise<string> {
  return typeof sessionStorage === 'undefined' ? '' : sessionStorage.getItem(KEY) ?? '';
}

export async function setToken(token: string): Promise<void> {
  if (typeof sessionStorage === 'undefined') return;
  if (token) sessionStorage.setItem(KEY, token);
  else sessionStorage.removeItem(KEY);
}
