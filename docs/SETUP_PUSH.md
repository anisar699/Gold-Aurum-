# Remote push notifications (optional)

AURUM XAU always shows **local** notifications for alerts received over the live WebSocket while the app is running.
To receive alerts while the app is closed, Android needs Firebase Cloud Messaging (FCM) via the Expo push service:

1. Create a free Expo account and run `npx eas-cli@latest init` inside `mobile/` — this adds `extra.eas.projectId` to the app config.
2. Create a Firebase project, add an Android app with package `com.aurumxau.goldintel`, download `google-services.json`
   into `mobile/` and add `"googleServicesFile": "./google-services.json"` under `expo.android` in `mobile/app.json`.
3. Upload the FCM V1 service-account key to Expo: `npx eas-cli@latest credentials` → Android → Push Notifications.
4. Rebuild the APK. On first launch the app registers its Expo push token with the backend (Health & Settings shows
   "Remote push: registered").
5. Optional: create an Expo access token and set `AURUM_EXPO_ACCESS_TOKEN` on the backend.

Android may still delay notifications under aggressive battery optimisation; exclude AURUM XAU from battery
optimisation if alerts arrive late. The backend, not the phone, monitors the market, so missed pushes never change
signal outcomes.
