<p align="center"><img src="docs/icon.png" width="120" alt="Sector 8 icon"></p>

# Sector 8

A moody, rain-soaked cyberpunk block puzzle for Android. Drag pieces onto an 8×8 grid and clear them. It has two modes, picked on the home screen.

### Link (main mode)

- **Pieces:** every square of a piece has its own color. Full rows don't clear.
- **Links:** drag across touching squares of one color (up, down or sideways) and let go to clear them. Longer links score much more.
- **Loops:** bring a link back on itself to make a loop. A loop clears every square of that color on the board.
- **Credits:** you earn 1 credit for every 100 points, paid out as you play. Credits carry over between games.
- **Shop:** open it from the button during a game to buy items.

| Item | Price | What it does |
| --- | --- | --- |
| Block destroyer | 5 for 100 credits | Remove any one square you tap. |
| Color wipe | 1 for 100 credits | Tap a square to clear every square of its color. |
| Shuffle | 1 for 50 credits | Move every square to a random spot. Colors stay the same. |

- **End of a game:** the game ends when no piece fits and no two matching squares touch. If you still own items, it waits for you to use one instead.

### Classic

Fill rows or columns to clear them. Clearing several at once multiplies the points, and clearing again within 3 moves builds a chain bonus.

### Both modes

- **Undo:** an optional button, turned on in **Settings**. Each undo deals three new pieces. In Link mode it also rolls back credits and items.
- **Tray:** a slot refills as soon as you use it.
- **Saving:** each mode keeps its own saved game, high scores and stats.
- **Sound and vibration:** a synth soundtrack, sound effects and vibration, each with an on/off switch in Settings.

## Install and updates

1. On your Android phone, open this repo's **Releases** page and download `Sector8-*.apk` from **Latest build**.
2. Open the file. If Android asks, allow your browser to install apps.
3. After that, **the app updates itself.** On launch, at most every 6 hours, it checks the Latest build release. You can also check from **Settings → Check for updates**. When a newer build exists, a banner on the home screen offers **Install**. The first time, Android asks you to allow Sector 8 to install updates.

Self-updating needs this repo to be **public**, because the app reads releases without signing in.

## How it's built

| Path | What it is |
| --- | --- |
| `app/src/main/assets/www/index.html` | The whole game (HTML, CSS and JS in one file). Edit this to change gameplay or look. |
| `app/src/main/java/.../MainActivity.java` | The Android shell: full-screen WebView, vibration, back button, and the update check, download and install. |
| `.github/workflows/build.yml` | Builds a signed APK on every push to `main` and puts it on the **Latest build** release. Older APKs are removed so the app always finds the newest. |

The version number is `1.0.<build number>`, so every build counts as newer than the last. Pushing a tag such as `v1.1.0` also creates a versioned release.

To build locally, open the folder in Android Studio and run **Build → Build APK(s)**. Or run `gradle assembleDebug` with Gradle 8.9 and JDK 17.

## Signing (one-time setup)

Release APKs are signed in GitHub Actions with a key stored as repository secrets, never in the code. Add these under **Settings → Secrets and variables → Actions**:

- `SIGNING_KEYSTORE_B64`: the keystore, base64-encoded
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Without them, builds still compile but only make a debug APK and publish nothing. Every release must use the same key, or phones will refuse to install a build over the old one.
