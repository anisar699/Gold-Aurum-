import { Platform } from 'react-native';
import * as Notifications from 'expo-notifications';
import { AndroidImportance } from 'expo-notifications';
import * as Device from 'expo-device';
import Constants from 'expo-constants';
import { api } from './api';
import { tx } from './i18n';
import { useStore } from './store';
import type { AlertMsg } from './types';

let initialised = false;
export type PushStatus = 'registered' | 'unavailable' | 'denied' | 'not-configured' | 'pending';
let pushStatus: PushStatus = 'pending';
let pushDetail = '';

export function getPushStatus() {
  return { status: pushStatus, detail: pushDetail };
}

export async function initNotifications(): Promise<void> {
  if (initialised || Platform.OS === 'web') return;
  initialised = true;
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: true,
      shouldSetBadge: false,
    }),
  });
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('signals', {
      name: 'Trade signals & alerts',
      importance: AndroidImportance.HIGH,
      vibrationPattern: [0, 250, 150, 250],
      lightColor: '#D4AF37',
    });
    await Notifications.setNotificationChannelAsync('status', {
      name: 'Connection status',
      importance: AndroidImportance.DEFAULT,
    });
  }
  const perm = await Notifications.getPermissionsAsync();
  let granted = perm.granted;
  if (!granted && perm.canAskAgain) granted = (await Notifications.requestPermissionsAsync()).granted;
  if (!granted) {
    pushStatus = 'denied';
    return;
  }
  await registerPush();
}

/**
 * Remote push via the Expo push service needs an EAS project ID and Firebase (FCM) credentials in the build.
 * Without them we fall back to local notifications driven by the live WebSocket connection.
 */
export async function registerPush(): Promise<void> {
  if (Platform.OS === 'web' || !Device.isDevice) {
    pushStatus = 'unavailable';
    pushDetail = 'Not a physical device';
    return;
  }
  const projectId = (Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined)?.eas?.projectId;
  if (!projectId) {
    pushStatus = 'not-configured';
    pushDetail = 'No EAS projectId/FCM in this build: alerts arrive while the app is running (WebSocket).';
    return;
  }
  try {
    const token = (await Notifications.getExpoPushTokenAsync({ projectId })).data;
    await api.post('/api/push/register', { token, platform: Platform.OS, language: useStore.getState().settings.language });
    pushStatus = 'registered';
    pushDetail = token.slice(0, 22) + '…';
  } catch (e) {
    pushStatus = 'not-configured';
    pushDetail = String((e as Error).message ?? e);
  }
}

const TYPE_PREF: Record<string, keyof ReturnType<typeof useStore.getState>['settings']['alerts']> = {
  ZONE_APPROACH: 'zones',
  NEWS_SOON: 'news',
  SPREAD_HIGH: 'spread',
  FEED_DOWN: 'feed',
  FEED_UP: 'feed',
};

const shown = new Set<string>();

/** Show a local notification for a backend alert (deduplicated by the backend key, and again locally). */
export async function notifyAlert(a: AlertMsg): Promise<void> {
  if (Platform.OS === 'web' || shown.has(a.dedupeKey)) return;
  shown.add(a.dedupeKey);
  const { settings } = useStore.getState();
  const pref = a.type.startsWith('SIGNAL_') ? 'signals' : TYPE_PREF[a.type];
  if (pref && !settings.alerts[pref]) return;
  await Notifications.scheduleNotificationAsync({
    content: {
      title: tx(a.title, settings.language),
      body: tx(a.body, settings.language),
      data: { signalId: a.signalId, type: a.type },
    },
    trigger: Platform.OS === 'android' ? { channelId: a.type.startsWith('FEED') ? 'status' : 'signals' } : null,
  });
}

/** Local warning when the phone loses the backend (backend offline / auth failure). */
export async function notifyLocal(key: string, title: string, body: string): Promise<void> {
  if (Platform.OS === 'web' || shown.has(key)) return;
  shown.add(key);
  await Notifications.scheduleNotificationAsync({
    content: { title, body },
    trigger: Platform.OS === 'android' ? { channelId: 'status' } : null,
  });
}
