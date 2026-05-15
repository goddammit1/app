# Notifications

A small Android app for sending instant, high-priority **alerts** to a private
group of users — like a personal "general muster" tool.

Sender picks a recipient (or fires a broadcast), receivers' phones wake up,
play a custom sound, vibrate, and show a full-screen confirmation dialog
("Ready / Not ready"). The sender then sees a live report of who responded
and how.

The app is open and fully self-hosted on **Firebase Realtime Database** —
no Cloud Functions, no Blaze plan, no payments required.

---

## Features

- **Registration & login** with passwords stored as salted SHA-256 in Firebase
  Realtime DB (so your account works after a re-install / on a new phone).
- **Send to a specific user**, **broadcast to everyone**, or **self-test**.
- **Alarm** = looping sound (custom user-picked, or bundled) + vibration
  + system overlay + lock-screen full-screen Activity (`DialogActivity`).
- **Reports**: every alert is saved for 24 h. Per-alert detail page with three
  color-coded sections: ?? Ready, ?? Not ready, ? No response.
- **Self-test** writes exactly one entry per user (previous is auto-deleted),
  so it doesn't clutter reports.
- **Online / Offline** indicator per user. A user is *online* while the in-app
  foreground service is alive (= app open or in background). Closing the app
  from Recents marks them offline.
- **Dark Material theme** end-to-end.
- **Self-update** from GitHub Releases — see below.

---

## Tech stack

| | |
|---|---|
| Language | Java |
| Min SDK / Target SDK | 24 / 34 |
| Gradle | 8.7 (Java 17) |
| Android Gradle Plugin | 8.3.2 |
| Backend | Firebase Realtime Database + Firebase Cloud Messaging (topic `group1`) |
| UI | AppCompat + Material Components, ConstraintLayout |

---

## Project structure

```
app/src/main/java/com/goddddd/notification/
    LoginActivity / RegisterActivity      - auth (passwords in Firebase)
    MainActivity                          - home screen
    UsersListActivity                     - pick recipient OR view online/offline
    ReportsActivity / AlertDetailActivity - per-user list + color report
    DialogActivity                        - full-screen "Ready/Not ready" prompt
    InboxService                          - foreground service: listens for alerts,
                                            owns the online/offline flag
    AlarmEngine                           - sound + vibration + overlay + system
                                            notification (with BigTextStyle)
    MyFirebaseMessagingService            - FCM topic 'group1' fallback
    RemoteUsers                           - all Realtime DB operations
    PasswordHash, SessionManager          - auth helpers
    SoundPrefs                            - custom alarm sound storage
    BootReceiver                          - restarts subscriptions after reboot
    UpdateChecker                         - self-update from GitHub Releases
```

Firebase Realtime DB layout (relevant nodes):

```
users/<login>             { createdAt, online, passHash, salt, lastSelfTestAlertId }
inbox/<login>/<pushId>    { from, text, ts, alertId }    -- delivered then deleted
alerts/<id>               {
    from, text, ts, selfTest?,
    recipients: { login1: true, login2: true, ... },
    responses:  { login1: { ready: true, ts }, ... }
}
```

Retention: regular alerts older than 24 h are removed; self-test entries are
overwritten on each new self-test.

---

## Building from source

### Prerequisites
- **Android Studio Hedgehog (2023.1)** or newer, OR plain `gradlew`.
- **JDK 17** (path used in dev: `C:\Program Files\Java\jdk-17.0.3`).
- A **Firebase project** with Realtime Database enabled.

### One-time Firebase setup

1. Create a Firebase project. Add an Android app with the package
   `com.goddddd.notification`.
2. Download the generated `google-services.json` and place it at
   `app/google-services.json` (overwriting the sample).
3. Enable **Realtime Database** in the Firebase Console. Note your DB URL.
4. Open `app/src/main/java/com/goddddd/notification/RemoteUsers.java` and set
   `DATABASE_URL` to your URL.
5. In the **Rules** tab of Realtime Database, paste:
   ```json
   {
     "rules": { ".read": true, ".write": true }
   }
   ```
   (Open access — adequate for a private app. For production add Firebase Auth.)

### Build

```bash
# debug
./gradlew assembleDebug

# release — needs keystore.properties (see below)
./gradlew assembleRelease
```

APK is produced at `app/build/outputs/apk/<debug|release>/app-<variant>.apk`.

### Release signing

Create `keystore.properties` in the repo root (NOT committed):

```properties
storeFile=../release.keystore
storePassword=<your-store-pass>
keyAlias=<alias>
keyPassword=<your-key-pass>
```

Generate the keystore once:

```bash
keytool -genkeypair -v -keystore release.keystore -alias notifications \
    -keyalg RSA -keysize 2048 -validity 10000
```

If `keystore.properties` is missing, `assembleRelease` still builds but the
APK is unsigned (debug-only).

---

## Self-update

The app checks GitHub Releases on every cold start (rate-limited to 1 minute).

### How it works

- `UpdateChecker.fetchLatest()` calls
  `https://api.github.com/repos/goddammit1/app/releases/latest`.
- Compares `tag_name` (`v1.0.1` or `1.0.1`) against `BuildConfig.VERSION_NAME`.
- If newer, shows a dialog. **Update** -> downloads the first `.apk` asset to
  `cacheDir/updates/app-update.apk`, then opens the system installer via
  `FileProvider` + `ACTION_VIEW`.
- The user has three buttons in the dialog: **Update**, **Later**, **Skip this
  version** (remembers tag in SharedPreferences).
- First install: Android asks for "Install unknown apps" permission for this
  app. The checker opens the right Settings screen automatically.

### Release checklist (every time you ship)

1. In `app/build.gradle` bump:
   ```groovy
   versionCode 3        // +1
   versionName "1.0.1"  // semantic
   ```
2. Build:
   ```bash
   ./gradlew assembleRelease
   ```
3. Rename `app/build/outputs/apk/release/app-release.apk` to
   something like `notifications-1.0.1.apk` (purely cosmetic).
4. On GitHub: **Releases ? Draft a new release**:
   - **Tag**: `v1.0.1` (must match the new versionName, with optional `v`)
   - **Title**: anything, e.g. `1.0.1`
   - **Description**: changelog (shown to users in the update dialog!)
   - **Attach**: the `.apk` file. **Must end with `.apk`.**
   - Click **Publish release**.
5. Existing users on `v1.0.0` will get the update prompt within ~1 minute of
   opening the app.

### Important
- If you fork the repo, change `REPO_OWNER` / `REPO_NAME` in
  `UpdateChecker.java`.
- Don't lose `release.keystore` — Android refuses to install updates signed by
  a different key.

---

## Permissions used

| Permission | Why |
|---|---|
| `INTERNET` | Realtime DB / FCM / updates |
| `POST_NOTIFICATIONS` | Show the alert notification on Android 13+ |
| `USE_FULL_SCREEN_INTENT` | Wake the lock screen on incoming alert |
| `VIBRATE` | Alarm vibration |
| `WAKE_LOCK` | Keep CPU running long enough to play the alarm |
| `SYSTEM_ALERT_WINDOW` | Optional: nicer in-app overlay (asked on demand) |
| `RECEIVE_BOOT_COMPLETED` | Re-subscribe to FCM topic after reboot |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | The inbox listener service |
| `REQUEST_INSTALL_PACKAGES` | Install downloaded updates |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Asked once for reliable delivery |

---

## License

Personal / private use. No license declared, all rights reserved by the author.