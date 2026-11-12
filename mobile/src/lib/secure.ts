import * as SecureStore from 'expo-secure-store';

// The backend API token is kept in the Android Keystore-backed SecureStore, never in plain storage.
const KEY = 'aurum_api_token';

export async function getToken(): Promise<string> {
  return (await SecureStore.getItemAsync(KEY)) ?? '';
}

export async function setToken(token: string): Promise<void> {
  if (token) await SecureStore.setItemAsync(KEY, token);
  else await SecureStore.deleteItemAsync(KEY);
}
