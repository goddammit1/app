# 1.0.2

## Highlights

* **New Settings screen.** Full redesign of the bottom-sheet that opens
  from the gear tile in the menu:
  * a "hero" profile card with the user's avatar, display name and a
    little pencil chip in the corner that opens Profile;
  * an asymmetric 3-card grid for permissions: **Battery** and
    **Display** stacked on the left, a tall **Notifications** card on
    the right;
  * a mirrored second grid with **Self-test** (tall, left) and two new
    tiles on the right - **Updates** (manual update check with Toast
    feedback when there is nothing to install) and **Lock screen**
    (`USE_FULL_SCREEN_INTENT` system page on Android 14+).
  * Both grids share a 50% Guideline so the vertical seam between left
    and right columns lines up exactly between rows.
* **Footer "back" button** pinned to the bottom of the sheet, reusing
  the heart mark rotated 90 degrees so it visually echoes the
  open-menu affordance. The sheet can no longer be swiped down or
  dismissed by touching outside - the footer is the only exit.

## Polish

* **Status panel on the home screen** now uses the bigger 8dp white
  corner dot to mark "me" (the previous self-ring looked cluttered);
  the unused `bg_slot_self_ring.xml` asset was removed.
* **Online dot in the users list** finally pokes out of the avatar
  circle Discord-style. The avatar bitmap is clipped to a circle on
  its own ImageView, while the surrounding FrameLayout no longer
  clips its children, so the dot can overflow the circle.
* **Home screen background** now matches the menu sheet
  (`@color/bg_panel`) instead of pitch black, so the menu and the
  home screen feel like one surface.
* **Heart handle on the menu sheet is now a button** - tap it to
  close the menu. Opens and closes are now symmetrical.

## Internal

* `UpdateChecker.forceCheckAsync(Activity)` added for the manual
  "Updates" tile: it bypasses the rate-limit and the "skip this
  version" preference and surfaces a Toast when no update is
  available.
* New drawables: `bg_settings_card_outlined`, `bg_settings_card`,
  `bg_settings_footer`, `bg_edit_chip`, `bg_avatar_square`,
  `ic_edit`, `ic_battery`, `ic_layers`, `ic_lock_screen`,
  `ic_refresh`.
* New strings under `settings_*` and `menu_close`.