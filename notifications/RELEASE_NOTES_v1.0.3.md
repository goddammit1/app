# 1.0.3

## New: Mobile Legends presence

Discord-style "playing right now" / "left it minimized" status for the
one game we currently track - **Mobile Legends: Bang Bang**
(`com.mobile.legends`).

In the **Users online** list, under a friend's display name, you now
see one of three things:

* **"Playing Mobile Legends"** - the game is on screen for them right
  now. They're in a match. Don't disturb unless it's important.
* **"Mobile Legends in background"** *(dimmer)* - the game's process
  is still alive in RAM, but they switched to something else
  (browser, messenger, our app, home screen). They're available; if
  you need them, they can answer.
* **Nothing** - they haven't touched Mobile Legends in the last
  ~10 minutes.

## How to enable on your phone

1. Open the menu -> **Settings** (gear).
2. Scroll to the bottom - there's a new wide card **"Game status"**
   with a gamepad icon.
3. Tap it. The system **Usage access** page opens. Find Notifications
   in the list and turn it on.
4. That's it. The next time you open Mobile Legends, your friends will
   see "Playing Mobile Legends" under your name within ~5 seconds.

## How it actually works

* A new component **`GameWatcher`** lives inside the existing
  `InboxService` foreground service. It polls
  `UsageStatsManager` every 5 seconds and classifies the foreground
  app into three states (`playing` / `minimized` / `null`).
* Writes to Firebase only happen on **state changes** - sitting in
  the same match for an hour produces exactly one DB write.
* Per-state TTLs on the reader side:
  * `playing` - trusted for 60 seconds after the last write. If your
    phone sleeps mid-match, your friends see the badge disappear
    after a minute, not stay forever.
  * `minimized` - trusted for 10 minutes after the last write.
* The watcher is bound to the service lifecycle: on logout / process
  kill / Firebase disconnect, the flag is cleared (via on-disconnect
  hook), so a stale "Playing X" never sticks around for a logged-out
  user.

## Privacy

* We only ever look up **one specific package** -
  `com.mobile.legends`. We don't enumerate or track anything else on
  the device.
* The only data ever written to Firebase is a string
  (`"playing"`/`"minimized"`/`null`) plus a timestamp at
  `users/<login>/gameState` / `users/<login>/gameTs`. No bitmaps, no
  durations, no per-match data, no third-party transfers.
* `PACKAGE_USAGE_STATS` cannot be granted programmatically - the user
  has to enable it manually from system Settings, and they can revoke
  it from the same page at any time. Without that permission the app
  silently does not write anything.

## Other changes

* `RELEASE_NOTES_v1.0.2.md` already shipped the Settings redesign and
  the heart-as-close button. This release builds on top of that - no
  visual regressions to the Settings sheet, just the new card at the
  bottom.
* Added `ENVIRONMENT.md` and `DESIGN_FILES.md` to the repo root.
  They're internal docs for collaborators (versions / dependencies
  and a guide to which files are safe to touch for visual work).
* Existing `users/<login>/inGame` boolean from v1.0.2 dev builds (if
  any) is overwritten with `null` on the next `setGameState()` write,
  so DB stays clean.

## Internal

* New: `GameWatcher.java`, `ic_gamepad.xml`.
* Changed: `RemoteUsers.java` (new `setGameState` + `UserInfo.gameState`),
  `InboxService.java` (owns/stops the watcher), `MainActivity.java`
  (new `openUsageAccessSettings()` for the Settings tile),
  `UsersListActivity.java` (sub-line with two visual states),
  `bottom_sheet_settings.xml` (third row),
  `strings.xml` (`settings_gametrack_*`, `users_list_playing`,
  `users_list_minimized`, `tracked_game_name`),
  `AndroidManifest.xml` (`PACKAGE_USAGE_STATS`).
* `versionCode 4 -> 5`, `versionName 1.0.2 -> 1.0.3`.