## v1.0.1 — Reliable online presence

### Bug fixes
- **Online status now survives network changes.** Previously, after toggling
  Wi-Fi, switching mobile data, enabling/disabling a VPN, or any other
  reconnect, the user would stay "offline" until the app was manually
  reopened. The presence flag is now driven by Firebase's
  `.info/connected` path, so it is re-asserted on every reconnect.

### Internal
- `RemoteUsers.attachPresence(login)` / `detachPresence()` replace the
  one-shot `setOnline(true)` call from `InboxService` lifecycle.
- `onDisconnect()` handler is re-armed on every reconnect, ensuring the
  server-side fallback to `online=false` always exists.

### Install
Download `notifications-v1.0.1.apk` and install it on Android 7.0+
(minSdk 24). The in-app updater (`UpdateChecker`) will also pick this
release up automatically.