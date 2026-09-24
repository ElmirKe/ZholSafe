# ZholSafe — Android APK for the team

`ZholSafe-debug.apk` — debug build of branch `final` (commit `0b3efbd`), 66 MB.
SHA-256: `3ed67e0ab0cab9491ff1f312ff74a15048a4d01ad221b9f3d16eb18ab34aab8f`

## Install
1. Copy the APK to an Android phone (Android 8.0+) and open it.
2. Allow installing from this source when Android asks.
3. On first start allow camera access.

## What to try
- **Camera: driver** (default): face the front camera, wait ~2 s for calibration, then close your
  eyes for 2 s → red screen + siren + "Проснитесь!".
- Look down for 3 s → "Смотрите на дорогу". Yawn → "Признаки усталости".
- Cover the camera → grey "Лицо не видно" (never green).
- **Язык** button: Русский → Қазақша → English.

## Known limits
- Debug build, not for Google Play.
- No road model inside: "Camera: road" reports the model as missing. Push an exported model with
  `scripts/install-android.sh <export-dir>` (see docs/STAGE4_4_ANDROID_DRIVER_APP.md).
- Not yet tested on a real phone — please report what works and what does not.
