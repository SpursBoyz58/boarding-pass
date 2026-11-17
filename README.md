# Jetlag

Fly a shared world of simulated flights, collect airports, stamps and airlines.

The whole game lives in `app/src/main/assets/index.html`; the Android app is a full-screen WebView around it.

## Getting the APK

Every push to `main` builds a debug APK with GitHub Actions and publishes it as a release.

1. On your phone, open this repo's **Releases** page and tap the newest `jetlag-N.apk`.
2. Open the download. Android will ask to allow installs from your browser the first time — allow it.
3. Tap **Install**. New builds install over the old one and keep your progress.

## Notes

- The APK plays in solo mode: progress is saved on the phone. The shared multiplayer world runs in the Claude artifact.
- Fonts load from Google Fonts when online; offline the game falls back to system fonts.
