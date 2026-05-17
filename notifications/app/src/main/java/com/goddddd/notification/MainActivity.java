package com.goddddd.notification;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home screen, redesigned UX.
 *
 *   - Title at the top.
 *   - Five status slots inside a rounded dark panel:
 *         each slot represents one "answered" user, painted white
 *         (ready) or red (not_ready). The current user's slot has a
 *         tiny white dot in the top-right corner so they can find
 *         themselves on the panel; long-pressing it clears the user's
 *         status (back to pending).
 *   - A thin response timer just under the slots: it lives while a
 *         broadcast is in flight (60s global window). Until the window
 *         expires nobody can press Send.
 *   - A single drag-handle icon at the bottom opens the menu sheet.
 *
 * Lock lifecycle:
 *   - When the watcher receives an active lock we kick a local timer to
 *     "now + remaining" and a {@link #lockExpiryTick} runnable. After
 *     the runnable fires we re-apply lock state as if Firebase pushed
 *     an empty value, even if it didn't. That fixes the "Send stays
 *     disabled forever" symptom seen when the lock node lingers in the
 *     DB without anyone overwriting it.
 *   - When the statuses watcher updates, we check whether every
 *     recipient of the in-flight alert already has a status with
 *     {@code ts >= lockTs}. If yes, we release the lock early so Send
 *     becomes available immediately.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 1001;

    /** How long a slot stays on the panel after the user answered. */
    private static final long PANEL_TTL_MS = 60L * 60 * 1000;

    private SessionManager session;

    // UI ---------------------------------------------------------------
    private View[] slots;
    private ImageView[] slotAvatars;
    private ProgressBar timerBar;
    private ImageButton btnMenu;

    // Sheets -----------------------------------------------------------
    private BottomSheetDialog menuSheet;
    private BottomSheetDialog settingsSheet;
    private View tileSendInSheet;
    private enum SheetToReopen { NONE, MENU, SETTINGS }
    private SheetToReopen pendingSheetOnResume = SheetToReopen.NONE;

    // Firebase watchers -----------------------------------------------
    private DatabaseReference statusesRef;
    private ValueEventListener statusesListener;
    private DatabaseReference lockRef;
    private ValueEventListener lockListener;
    private ObjectAnimator timerAnimator;

    private final List<String> slotLogins = new ArrayList<>();
    private boolean broadcastActive = false;

    /** Alert id and start ts of the current broadcast, if any. */
    private String currentLockAlertId;
    private long currentLockTs;

    /** Local handler used to force-recompute lock state when its window
     *  has expired (Firebase may not push an event for that case). */
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable lockExpiryTick = () -> applyLock(null);

    private final ActivityResultLauncher<String[]> pickSoundLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onSoundPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);

        session = new SessionManager(this);
        if (!session.isLoggedIn()) {
            goToLogin();
            return;
        }

        setContentView(R.layout.activity_main);
        InboxService.start(getApplicationContext());

        slots = new View[]{
                findViewById(R.id.slot1),
                findViewById(R.id.slot2),
                findViewById(R.id.slot3),
                findViewById(R.id.slot4),
                findViewById(R.id.slot5),
        };
        slotAvatars = new ImageView[]{
                findViewById(R.id.slot1Avatar),
                findViewById(R.id.slot2Avatar),
                findViewById(R.id.slot3Avatar),
                findViewById(R.id.slot4Avatar),
                findViewById(R.id.slot5Avatar),
        };
        timerBar = findViewById(R.id.timerBar);
        timerBar.setVisibility(View.INVISIBLE);

        for (int i = 0; i < slots.length; i++) {
            final int idx = i;
            slots[i].setOnLongClickListener(v -> {
                if (idx >= slotLogins.size()) return false;
                String login = slotLogins.get(idx);
                if (login == null) return false;
                String me = session.getLogin();
                if (me == null || !me.equalsIgnoreCase(login)) return false;
                confirmClearMyStatus();
                return true;
            });
        }

        TextView title = findViewById(R.id.titleText);
        title.setOnLongClickListener(v -> {
            confirmLogout();
            return true;
        });

        btnMenu = findViewById(R.id.btnMenu);
        btnMenu.setOnClickListener(v -> showMainMenuSheet());

        slots[0].getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int w = slots[0].getWidth();
                        if (w <= 0) return;
                        slots[0].getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        for (View s : slots) {
                            s.getLayoutParams().height = w;
                            s.requestLayout();
                        }
                    }
                });

        FirebaseMessaging.getInstance().subscribeToTopic("group1");
        requestNotificationPermission();
        UpdateChecker.checkAsync(this);
    }

    // ------------------------------------------------------------------
    // Firebase watchers lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        attachStatusesWatcher();
        attachLockWatcher();
    }

    @Override
    protected void onStop() {
        detachStatusesWatcher();
        detachLockWatcher();
        cancelTimer();
        uiHandler.removeCallbacks(lockExpiryTick);
        super.onStop();
    }

    // ---- statuses --------------------------------------------------------

    private void attachStatusesWatcher() {
        if (statusesRef != null) return;
        statusesRef = RemoteUsers.statusesRef();
        statusesListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                renderPanel(snapshot);
                maybeReleaseLockIfAllAnswered(snapshot);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                renderPanelIdle();
            }
        };
        statusesRef.addValueEventListener(statusesListener);
    }

    private void detachStatusesWatcher() {
        if (statusesRef != null && statusesListener != null) {
            try { statusesRef.removeEventListener(statusesListener); } catch (Throwable ignored) {}
        }
        statusesRef = null;
        statusesListener = null;
    }

    private void renderPanel(DataSnapshot snap) {
        long now = System.currentTimeMillis();
        long panelCutoff = now - PANEL_TTL_MS;

        List<Entry> entries = new ArrayList<>();
        for (DataSnapshot s : snap.getChildren()) {
            String login = s.getKey();
            if (login == null) continue;
            String state = s.child("state").getValue(String.class);
            Long ts = s.child("ts").getValue(Long.class);
            if (state == null || ts == null) continue;
            if (ts < panelCutoff) continue;
            boolean ready = "ready".equalsIgnoreCase(state);
            entries.add(new Entry(login, ready, ts));
        }
        Collections.sort(entries, (a, b) -> Long.compare(a.ts, b.ts));

        slotLogins.clear();
        String me = session.getLogin();
        for (int i = 0; i < slots.length; i++) {
            View slot = slots[i];
            ImageView avatarView = slotAvatars[i];
            if (i < entries.size()) {
                Entry e = entries.get(i);
                slotLogins.add(e.login);

                // Background = dark slot; the ring (and "me" inner ring,
                // if applicable) live on the foreground so they stay
                // visible on top of the avatar bitmap.
                slot.setBackgroundResource(R.drawable.bg_slot_idle);

                int ringRes = e.ready
                        ? R.drawable.bg_slot_ring_white
                        : R.drawable.bg_slot_ring_red;
                // For "me" we layer a small white corner badge on top of
                // the regular ring so the user can spot themselves on
                // the panel. The badge is intentionally tiny so it does
                // not look like a second border.
                if (me != null && me.equalsIgnoreCase(e.login)) {
                    Drawable[] layers = new Drawable[]{
                            ContextCompat.getDrawable(this, ringRes),
                            ContextCompat.getDrawable(this, R.drawable.bg_slot_self_dot)
                    };
                    slot.setForeground(new LayerDrawable(layers));
                } else {
                    slot.setForeground(
                            ContextCompat.getDrawable(this, ringRes));
                }
                fetchAvatarFor(e.login, avatarView);
            } else {
                slotLogins.add(null);
                slot.setBackgroundResource(R.drawable.bg_slot_idle);
                slot.setForeground(null);
                avatarView.setImageDrawable(null);
                avatarView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Ask Firebase for the user's avatarTs, then ask {@link AvatarCache}
     * for the actual bitmap. The two-step lookup is intentional: avatarTs
     * is small and cheap to read frequently, while the JPEG payload is
     * fetched only when it actually changed.
     *
     * If the user has no avatar set, the slot stays without an overlay
     * image (the colored outline remains the only indicator).
     */
    private void fetchAvatarFor(final String login, final ImageView view) {
        if (login == null) return;
        // Tag the view with the login currently expected, so async
        // callbacks for stale slots don't overwrite the right one.
        view.setTag(login);
        RemoteUsers.usersRef().child(login)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Boolean has = snap.child("hasAvatar").getValue(Boolean.class);
                        Long ts = snap.child("avatarTs").getValue(Long.class);
                        if (!Boolean.TRUE.equals(has) || ts == null) {
                            if (login.equals(view.getTag())) {
                                view.setImageDrawable(null);
                                view.setVisibility(View.GONE);
                            }
                            return;
                        }
                        AvatarCache.request(login, ts, bm -> {
                            // Make sure the slot hasn't been reassigned
                            // to a different user since we asked.
                            if (!login.equals(view.getTag())) return;
                            if (bm != null) {
                                view.setImageBitmap(bm);
                                view.setVisibility(View.VISIBLE);
                            } else {
                                view.setImageDrawable(null);
                                view.setVisibility(View.GONE);
                            }
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    private void renderPanelIdle() {
        slotLogins.clear();
        for (int i = 0; i < slots.length; i++) {
            slotLogins.add(null);
            slots[i].setBackgroundResource(R.drawable.bg_slot_idle);
            slots[i].setForeground(null);
            if (slotAvatars != null && slotAvatars[i] != null) {
                slotAvatars[i].setImageDrawable(null);
                slotAvatars[i].setVisibility(View.GONE);
            }
        }
    }

    private static class Entry {
        final String login;
        final boolean ready;
        final long ts;
        Entry(String login, boolean ready, long ts) {
            this.login = login; this.ready = ready; this.ts = ts;
        }
    }

    /**
     * Release the broadcast lock early if every recipient of the current
     * in-flight alert has answered (any status, ready or not_ready) since
     * the broadcast started.
     */
    private void maybeReleaseLockIfAllAnswered(DataSnapshot statusesSnap) {
        if (!broadcastActive || currentLockAlertId == null) return;
        final String alertId = currentLockAlertId;
        final long lockTs = currentLockTs;

        RemoteUsers.alertsRef().child(alertId).child("recipients")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot recSnap) {
                        if (!recSnap.exists()) return;
                        boolean all = true;
                        for (DataSnapshot r : recSnap.getChildren()) {
                            String login = r.getKey();
                            if (login == null) continue;
                            Long ts = statusesSnap.child(login).child("ts")
                                    .getValue(Long.class);
                            if (ts == null || ts < lockTs) { all = false; break; }
                        }
                        if (all) RemoteUsers.releaseBroadcastLock();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    // ---- broadcast lock --------------------------------------------------

    private void attachLockWatcher() {
        if (lockRef != null) return;
        lockRef = RemoteUsers.broadcastLockRef();
        lockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                applyLock(snapshot);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                applyLock(null);
            }
        };
        lockRef.addValueEventListener(lockListener);
    }

    private void detachLockWatcher() {
        if (lockRef != null && lockListener != null) {
            try { lockRef.removeEventListener(lockListener); } catch (Throwable ignored) {}
        }
        lockRef = null;
        lockListener = null;
    }

    private void applyLock(DataSnapshot snap) {
        long now = System.currentTimeMillis();
        boolean active = false;
        long lockTs = 0L;
        String alertId = null;
        if (snap != null && snap.exists()) {
            Boolean a = snap.child("active").getValue(Boolean.class);
            Long ts = snap.child("ts").getValue(Long.class);
            String aid = snap.child("alertId").getValue(String.class);
            if (Boolean.TRUE.equals(a) && ts != null
                    && (now - ts) < RemoteUsers.BROADCAST_WINDOW_MS) {
                active = true;
                lockTs = ts;
                alertId = aid;
            }
        }
        broadcastActive = active;
        currentLockAlertId = alertId;
        currentLockTs = lockTs;
        updateSendTileEnabled();

        uiHandler.removeCallbacks(lockExpiryTick);
        if (active) {
            startTimer(now - lockTs);
            // Belt-and-braces: recompute when the 60s window passes, in
            // case Firebase has nothing new to push to us.
            long delay = RemoteUsers.BROADCAST_WINDOW_MS - (now - lockTs) + 100;
            if (delay > 0) uiHandler.postDelayed(lockExpiryTick, delay);
        } else {
            cancelTimer();
            timerBar.setVisibility(View.INVISIBLE);
        }
    }

    private void updateSendTileEnabled() {
        if (tileSendInSheet == null) return;
        tileSendInSheet.setEnabled(!broadcastActive);
        tileSendInSheet.setAlpha(broadcastActive ? 0.45f : 1f);
    }

    // ---- timer -----------------------------------------------------------

    private void startTimer(long elapsedMs) {
        cancelTimer();
        long remaining = RemoteUsers.BROADCAST_WINDOW_MS - elapsedMs;
        if (remaining <= 0) {
            timerBar.setVisibility(View.INVISIBLE);
            return;
        }
        int startProgress = (int) (1000 * remaining / RemoteUsers.BROADCAST_WINDOW_MS);
        timerBar.setMax(1000);
        timerBar.setProgress(startProgress);
        timerBar.setVisibility(View.VISIBLE);

        timerAnimator = ObjectAnimator.ofInt(timerBar, "progress", startProgress, 0);
        timerAnimator.setDuration(remaining);
        timerAnimator.setInterpolator(new LinearInterpolator());
        timerAnimator.start();
    }

    private void cancelTimer() {
        if (timerAnimator != null) {
            try { timerAnimator.cancel(); } catch (Throwable ignored) {}
            timerAnimator = null;
        }
    }

    // ------------------------------------------------------------------
    // Menu / Settings sheets
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        switch (pendingSheetOnResume) {
            case MENU:
                pendingSheetOnResume = SheetToReopen.NONE;
                showMainMenuSheet();
                break;
            case SETTINGS:
                pendingSheetOnResume = SheetToReopen.NONE;
                showSettingsSheet();
                break;
            default:
                break;
        }
    }

    @Override
    protected void onPause() {
        if (menuSheet != null && menuSheet.isShowing()) menuSheet.dismiss();
        if (settingsSheet != null && settingsSheet.isShowing()) settingsSheet.dismiss();
        super.onPause();
    }

    private void showMainMenuSheet() {
        menuSheet = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_menu, null, false);
        menuSheet.setContentView(root);

        // The heart on top of the menu sheet doubles as the "close"
        // button now: the user opens the menu by tapping the heart on
        // the home screen, so closing it via the same mark feels
        // natural. We just dismiss the sheet - no special teardown
        // because onDismissListener already clears tileSendInSheet.
        View menuHandle = root.findViewById(R.id.menuHandle);
        if (menuHandle != null) {
            menuHandle.setOnClickListener(v -> menuSheet.dismiss());
        }

        tileSendInSheet = root.findViewById(R.id.tileSend);
        updateSendTileEnabled();
        tileSendInSheet.setOnClickListener(v -> {
            if (broadcastActive) {
                Toast.makeText(MainActivity.this,
                        "Broadcast in progress, wait for the timer",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            doBroadcast();
        });

        root.findViewById(R.id.tileUsers).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.MENU;
            openOnlineUsers();
        });
        root.findViewById(R.id.tileHistory).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.MENU;
            openReports();
        });
        View bell = root.findViewById(R.id.tileBell);
        bell.setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.MENU;
            openSoundChooser();
        });
        bell.setOnLongClickListener(v -> {
            confirmResetSound();
            return true;
        });
        root.findViewById(R.id.tileGear).setOnClickListener(v -> {
            menuSheet.dismiss();
            showSettingsSheet();
        });

        menuSheet.setOnDismissListener(d -> tileSendInSheet = null);
        menuSheet.show();
    }

    private void showSettingsSheet() {
        settingsSheet = new BottomSheetDialog(this);
        View root = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_settings, null, false);
        settingsSheet.setContentView(root);

        // Settings is a full-screen "page", not a peeked sheet:
        //   * the only legitimate way to dismiss it is the footer "back"
        //     button - so we disable the swipe-down-to-hide gesture
        //     (setHideable=false), drag handle (setDraggable=false), and
        //     outside-touch dismiss (setCanceledOnTouchOutside=false);
        //   * the sheet occupies the full screen height. The layout
        //     itself owns the geometry: a vertical LinearLayout with a
        //     ScrollView (weight=1) on top and a fixed-height footer
        //     pinned below it. The ScrollView holds the header, the
        //     profile card and both 3-tile grids; the footer stays
        //     visible at the bottom of the sheet at all times. We do
        //     NOT clamp the sheet to a fraction of the screen anymore -
        //     the previous attempt simply truncated the layout, which
        //     is what the user noticed as "the screen just gets
        //     cropped".
        settingsSheet.setCanceledOnTouchOutside(false);
        settingsSheet.setOnShowListener(d -> {
            View bs = settingsSheet.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bs == null) return;
            com.google.android.material.bottomsheet.BottomSheetBehavior<View> b =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.from(bs);
            b.setHideable(false);
            b.setSkipCollapsed(true);
            b.setDraggable(false);
            b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

            // Make the bottom-sheet container take the entire available
            // screen height. Combined with the layout's
            // android:layout_height="match_parent" this gives us a
            // proper full-screen sheet where the inner ScrollView is
            // bounded by the screen and the footer is pinned to the
            // very bottom.
            android.view.ViewGroup.LayoutParams lp = bs.getLayoutParams();
            if (lp != null) {
                lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                bs.setLayoutParams(lp);
            }
        });

        // ---- Profile card: bind the user's display name + avatar.
        final ImageView profileAvatarImage = root.findViewById(R.id.profileAvatarImage);
        final ImageView profileAvatarPlaceholder = root.findViewById(R.id.profileAvatarPlaceholder);
        final android.widget.TextView profileName = root.findViewById(R.id.profileName);
        bindProfileCard(profileAvatarImage, profileAvatarPlaceholder, profileName);

        // ---- Tile: edit chip on the profile card -> open ProfileActivity.
        View.OnClickListener openProfile = v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            settingsSheet.dismiss();
            startActivity(new Intent(this, ProfileActivity.class));
        };
        // Both the chip itself and the surrounding card open the profile,
        // so users can tap anywhere on the hero card to edit.
        root.findViewById(R.id.btnProfileEdit).setOnClickListener(openProfile);
        root.findViewById(R.id.cardProfile).setOnClickListener(openProfile);

        // ---- Permissions grid (row 1).
        root.findViewById(R.id.tileBatteryPerm).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            openBatteryOptimization();
        });
        root.findViewById(R.id.tileOverlayPerm).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            openOverlaySettings();
        });
        root.findViewById(R.id.tileNotifPerm).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            openNotificationsPermission();
        });

        // ---- Row 2.
        root.findViewById(R.id.tileSelfTest).setOnClickListener(v -> runSelfTest());
        root.findViewById(R.id.tileCheckUpdates).setOnClickListener(v -> {
            // Explicit user-triggered check: bypass rate-limit and skip
            // flag, and surface a Toast when there is nothing new.
            UpdateChecker.forceCheckAsync(MainActivity.this);
        });
        root.findViewById(R.id.tileLockScreen).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            openLockScreenPermission();
        });

        // ---- Row 3: Game tracking (Mobile Legends).
        root.findViewById(R.id.tileGameTrack).setOnClickListener(v -> {
            pendingSheetOnResume = SheetToReopen.SETTINGS;
            openUsageAccessSettings();
        });

        // ---- Footer "back" button: explicitly dismiss the settings
        // sheet so the existing onDismissListener logic re-opens the
        // main menu sheet (the user expects to "go back" to where they
        // came from).
        root.findViewById(R.id.footerBack).setOnClickListener(v ->
                settingsSheet.dismiss());

        settingsSheet.setOnDismissListener(d -> {
            if (pendingSheetOnResume != SheetToReopen.SETTINGS
                    && !isFinishing() && !isDestroyed()) {
                showMainMenuSheet();
            }
        });

        settingsSheet.show();
    }

    /**
     * Load the current user's avatar + display name into the Settings
     * profile card. Identical to {@link UsersListActivity} two-step
     * lookup: the user node tells us whether an avatar exists, then
     * {@link AvatarCache} delivers the actual bitmap.
     *
     * The placeholder view always stays underneath the bitmap view,
     * which is left invisible until the cache hands a bitmap back.
     */
    private void bindProfileCard(final ImageView image,
                                 final ImageView placeholder,
                                 final android.widget.TextView nameView) {
        final String me = session.getLogin();
        if (me == null) return;
        // Sensible default before Firebase replies: show the login.
        nameView.setText(me);
        image.setVisibility(View.GONE);
        placeholder.setVisibility(View.VISIBLE);
        image.setTag(me);

        RemoteUsers.usersRef().child(me)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String dn = snap.child("displayName").getValue(String.class);
                        if (dn != null && !dn.trim().isEmpty()) {
                            nameView.setText(dn.trim());
                        }
                        Boolean has = snap.child("hasAvatar").getValue(Boolean.class);
                        Long ts = snap.child("avatarTs").getValue(Long.class);
                        if (!Boolean.TRUE.equals(has) || ts == null) return;
                        AvatarCache.request(me, ts, bm -> {
                            // The sheet may have been dismissed in the
                            // meantime; tag check prevents drawing into
                            // a now-recycled ImageView slot.
                            if (!me.equals(image.getTag())) return;
                            if (bm != null) {
                                image.setImageBitmap(bm);
                                image.setVisibility(View.VISIBLE);
                                placeholder.setVisibility(View.GONE);
                            }
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    /**
     * Open the system screen that controls whether the app may show
     * full-screen UI over the lock screen (USE_FULL_SCREEN_INTENT).
     *
     *   * Android 14+ (API 34, V): dedicated settings page
     *     {@code Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT}.
     *     We point it at our own package so the user lands on the
     *     correct toggle.
     *   * Older Android: there is no system page for this - the
     *     behavior is on by default once the permission is in the
     *     manifest (which it is). Tell the user nothing special is
     *     required.
     */
    /**
     * Open the system "Usage access" page so the user can grant
     * PACKAGE_USAGE_STATS to this app. The watcher in
     * {@link InboxService} starts producing inGame updates once that
     * permission is granted (next service start - i.e. relog or
     * device unlock will re-attach the watcher).
     *
     *   * Already granted -> Toast and stay put.
     *   * Not granted -> ACTION_USAGE_ACCESS_SETTINGS. Pre-API 22 we
     *     don't even have that screen, so we just inform the user.
     */
    private void openUsageAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            pendingSheetOnResume = SheetToReopen.NONE;
            Toast.makeText(this, R.string.settings_gametrack_unsupported,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (GameWatcher.hasUsageAccess(this)) {
            pendingSheetOnResume = SheetToReopen.NONE;
            Toast.makeText(this, R.string.settings_gametrack_already_granted,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // Some OEMs reject the package-scoped variant - fall back
            // to the global Usage Access page where the user can find
            // our app manually.
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception inner) {
                Toast.makeText(this,
                        getString(R.string.settings_open_failed, inner.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openLockScreenPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent intent = new Intent(
                        "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"));
                    return;
                } catch (Exception inner) {
                    Toast.makeText(this,
                            getString(R.string.settings_open_failed, inner.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        pendingSheetOnResume = SheetToReopen.NONE;
        Toast.makeText(this, R.string.settings_lockscreen_unsupported,
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void doBroadcast() {
        if (menuSheet != null) menuSheet.dismiss();
        BusyOverlay.show(this, "Sending...");
        RemoteUsers.broadcastMessage(session.getLogin(), "",
                new RemoteUsers.SendCallback() {
                    @Override public void onSuccess(String alertId) {
                        BusyOverlay.hide(MainActivity.this);
                    }
                    @Override public void onError(String message) {
                        BusyOverlay.hide(MainActivity.this);
                        Toast.makeText(MainActivity.this,
                                "Send failed: " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void confirmClearMyStatus() {
        new AlertDialog.Builder(this)
                .setTitle("Reset your status?")
                .setMessage("You will get the next broadcast like everyone else.")
                .setPositiveButton("Reset", (d, w) -> {
                    String me = session.getLogin();
                    if (me != null) RemoteUsers.clearStatus(me);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openOnlineUsers() {
        Intent i = new Intent(this, UsersListActivity.class);
        i.putExtra(UsersListActivity.EXTRA_VIEW_ONLY, true);
        startActivity(i);
    }

    private void openReports() {
        startActivity(new Intent(this, ReportsActivity.class));
    }

    private void runSelfTest() {
        String me = session.getLogin();
        Toast.makeText(this,
                "Self-test in 3s. Lock the screen now.",
                Toast.LENGTH_LONG).show();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> RemoteUsers.sendMessage(me, me, "self-test",
                        new RemoteUsers.SendCallback() {
                            @Override public void onSuccess(String alertId) {}
                            @Override public void onError(String message) {
                                Toast.makeText(MainActivity.this,
                                        "Self-test error: " + message,
                                        Toast.LENGTH_LONG).show();
                            }
                        }), 3000);
    }

    // ---- Permissions ------------------------------------------------------

    private void openNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                // Already granted - tell the user and stay put.
                pendingSheetOnResume = SheetToReopen.NONE;
                Toast.makeText(this, R.string.settings_already_granted,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF);
            return;
        }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            pendingSheetOnResume = SheetToReopen.NONE;
            Toast.makeText(this, R.string.settings_already_granted,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        openAppNotificationSettings();
    }

    private void openAppNotificationSettings() {
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.settings_open_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this,
                    "Overlay permission not needed on this Android version",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            pendingSheetOnResume = SheetToReopen.NONE;
            Toast.makeText(this, R.string.settings_already_granted,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception inner) {
                Toast.makeText(this,
                        getString(R.string.settings_open_failed, inner.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this,
                    "Battery optimization N/A on this Android version",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            pendingSheetOnResume = SheetToReopen.NONE;
            Toast.makeText(this, R.string.settings_already_granted,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(fallback);
            } catch (Exception inner) {
                Toast.makeText(this,
                        getString(R.string.settings_open_failed, inner.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---- Logout -----------------------------------------------------------

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.logout_title)
                .setMessage(R.string.logout_msg)
                .setPositiveButton(R.string.logout_yes, (d, w) -> {
                    pendingSheetOnResume = SheetToReopen.NONE;
                    InboxService.stop(getApplicationContext());
                    session.logout();
                    Toast.makeText(this, R.string.logout_done, Toast.LENGTH_SHORT).show();
                    goToLogin();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------
    // Custom alarm sound (Bell tile)
    // ------------------------------------------------------------------

    private void openSoundChooser() {
        try {
            pickSoundLauncher.launch(new String[]{"audio/*"});
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.sound_save_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmResetSound() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sound_reset)
                .setPositiveButton(R.string.sound_reset, (d, w) -> {
                    SoundPrefs.clearCustomAlarm(this);
                    Toast.makeText(this, R.string.sound_reset_done,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onSoundPicked(Uri uri) {
        if (uri == null) return;
        BusyOverlay.show(this, "Saving sound...");
        try {
            String displayName = "alarm";
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String n = c.getString(idx);
                        if (n != null && !n.isEmpty()) displayName = n;
                    }
                }
            } catch (Exception ignored) {}

            File outFile = new File(getFilesDir(), SoundPrefs.CUSTOM_FILE_NAME);
            if (outFile.exists()) outFile.delete();

            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(outFile)) {
                if (in == null) throw new Exception("Cannot read file");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            SoundPrefs.setCustomAlarm(this, outFile.getAbsolutePath(), displayName);
            Toast.makeText(this, getString(R.string.sound_saved, displayName),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.sound_save_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        } finally {
            BusyOverlay.hide(this);
        }
    }

    // ------------------------------------------------------------------
    // First-run permission prompt
    // ------------------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
        }
    }
}