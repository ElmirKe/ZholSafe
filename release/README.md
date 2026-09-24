# ZholSafe — Android APK for the team

`ZholSafe-debug.apk` — debug build of branch `final` (commit `c791aa9`), 66 MB.
SHA-256: `efe30c5d352a0563bbf1ccdb436df449a41342af753e0b3ea27b752a6f854b47`

## Install
1. Copy the APK to an Android phone (Android 8.0+) and open it.
2. Allow installing from this source when Android asks.
3. On first start allow camera access.

## What to try
- **Camera: driver** (default): face the front camera and keep still while it says
  "Калибровка…" (~3 s) — it learns YOUR normal eyes (works for narrow eyes too) and head
  position. Then close your eyes for 2 s → red screen + siren + "Проснитесь!".
- **Cameras: both**: road + driver at the same time (works on phones with concurrent cameras,
  e.g. OnePlus CPH2573; other phones show the missing side in grey).
- Look down for 3 s → "Смотрите на дорогу". Yawn → "Признаки усталости".
- Cover the camera → grey "Лицо не видно" (never green).
- **Язык** button: Русский → Қазақша → English.

## Known limits
- Debug build, not for Google Play.
- No road model inside: "Camera: road" reports the model as missing. Push an exported model with
  `scripts/install-android.sh <export-dir>` (see docs/STAGE4_4_ANDROID_DRIVER_APP.md).
- Tested on one phone (OnePlus CPH2573, Android 16): both cameras, face tracking, calibration,
  road model. Closed-eye alarm after the new calibration not yet confirmed — please report.
