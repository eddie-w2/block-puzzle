<p align="center"><img src="docs/icon.png" width="120" alt="Sector 8 icon"></p>

# Sector 8

A moody, rain-soaked cyberpunk block puzzle for Android. Drag pieces onto an 8×8 grid and fill rows or columns to clear them.

- **Unlimited undo.** Each undo rewinds the board and score and deals you three fresh random pieces.
- **Always three pieces.** A tray slot refills the moment you use it.
- **Combos and chains.** Clearing several lines at once multiplies the points. Clearing again within 3 moves builds a chain bonus. Clearing the whole board adds +500.
- **Save and resume.** Your run is saved after every move.
- **Records.** Lifetime stats and a top 10 are kept on the phone.
- **Sound and haptics.** Synth soundtrack and effects generated in code, plus vibration. Music, sound and vibration each have an on/off switch.

## Install on your phone

1. On your Android phone, open this repo's **Releases** page and download the newest `Sector8-*.apk`. The **Latest build** release always has the newest one.
2. Open the downloaded file. If Android asks, allow your browser or file manager to install unknown apps.
3. Later builds install over the old one as updates, and your saves are kept.

## How it's built

| Path | What it is |
| --- | --- |
| `app/src/main/assets/www/index.html` | The whole game (HTML, CSS and JS in one file). Edit this to change gameplay or look. |
| `app/src/main/java/.../MainActivity.java` | The Android shell: full-screen WebView, native vibration bridge, back button handling. |
| `.github/workflows/build.yml` | Builds a signed APK on every push to `main` and attaches it to a release. |

Every push to `main` triggers a GitHub Actions build. The APK appears under **Actions → the run → Artifacts** and on the **Latest build** release. Pushing a tag such as `v1.1.0` also creates a versioned release.

To build locally, open the folder in Android Studio and run **Build → Build APK(s)**. Or run `gradle assembleRelease` with Gradle 8.9 and JDK 17.

## Signing

Release builds are signed with `app/signing/sector8.jks`. Its passwords are in `app/signing/signing.properties`. Use the same key for every build, or phones will refuse to install a new build over the old one.

**This is only safe while the repo is private.** Before making it public:

1. Add these repository secrets: `SIGNING_KEYSTORE_B64` (output of `base64 -w0 app/signing/sector8.jks`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
2. Delete `app/signing/` from the repo.

The workflow uses the secrets automatically when they exist.
