# Building the Android APK

The APK contains no secrets. All credentials stay on the backend.

## Option 1 — EAS cloud build (easiest, no Android SDK on your PC)

```bash
cd mobile
npm install
npx eas-cli@latest login          # free Expo account
npx eas-cli@latest build -p android --profile preview
```

`eas.json` is configured so `preview` and `production` produce an installable **.apk** (`production-aab` produces an
.aab for the Play Store). EAS stores a private signing key for you; keep using the same Expo account so updates install
over the previous version.

## Option 2 — GitHub Actions

Every push runs `.github/workflows/ci.yml`; the `android-apk` job uploads `aurum-xau-release-apk` as a workflow artifact
(Actions tab → latest run → Artifacts).

## Option 3 — Local build (Linux/macOS/Windows with Android SDK)

Requirements: Node 22, Java 21 (JDK 17+ works with AGP 9), Android SDK with
`platforms;android-37.0`, `build-tools;37.0.0`, `ndk;27.1.12297006`, `cmake;3.22.1`.

```bash
cd mobile
npm ci                                       # also regenerates the inlined chart page (postinstall)
npx expo prebuild --platform android --clean # generates android/ from app.json (do not edit android/ by hand)
cd android
./gradlew assembleRelease
# APK: mobile/android/app/build/outputs/apk/release/app-release.apk
```

Notes:
- The build targets `arm64-v8a` (virtually all phones from the last ~8 years). For very old 32-bit phones add
  `"armeabi-v7a"` to `buildArchs` in `mobile/app.json` (`expo-build-properties` plugin).
- Locally built release APKs are signed with the Expo template's public **debug keystore**. That is fine for installing
  on your own phone, but anyone could sign an APK with the same key, so only install APKs you built or downloaded from
  your own CI. For distribution use EAS (private key) or create your own keystore and configure it per the React Native
  "Publishing to Google Play" guide.
- If Gradle reports HTTP 429 from Maven Central, wait a few minutes and re-run with `--max-workers=1`; already
  downloaded artifacts are cached.

## Install on the phone

Copy the APK to the phone, open it, allow "Install unknown apps" for your file manager when Android asks, and install.
On first launch allow notifications.

## Verify an APK (optional)

```bash
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump badging app-release.apk | head -3
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app-release.apk
```
