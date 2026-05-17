package com.goddddd.notification;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

/**
 * Polls the foreground app via {@link UsageStatsManager} and reports a
 * tri-state "game presence" into Firebase
 * ({@code users/<login>/gameState}):
 *
 * <pre>
 *   "playing"    - the tracked game is on screen right now
 *   "minimized"  - the game was on screen recently (within
 *                  {@link #MINIMIZED_WINDOW_MS}) but isn't now;
 *                  the process is likely still alive in RAM but
 *                  the user is doing something else
 *   null         - the game hasn't been touched recently; treat
 *                  the user as fully available
 * </pre>
 *
 * Design points:
 *
 *   * One game only. {@link #TRACKED_PACKAGE} is hard-coded
 *     ({@value #TRACKED_PACKAGE}); we have no plans to track anything
 *     else right now, so a list/whitelist would be overkill.
 *   * Cheap to run. We ask UsageStatsManager only for the last
 *     {@link #QUERY_WINDOW_MS} ms of events, every
 *     {@value #POLL_INTERVAL_MS} ms. We write to Firebase ONLY when
 *     the computed state changes (e.g. playing -> minimized).
 *   * Permission-aware. If PACKAGE_USAGE_STATS is not granted (the
 *     special permission that the user must enable manually from
 *     "Settings > Apps > Special access > Usage access"), we simply
 *     don't start - no exceptions, no DB writes. The caller can check
 *     {@link #hasUsageAccess(Context)} to decide whether to even bother.
 *   * Bound to the foreground service lifecycle. Owned by
 *     {@link InboxService}: started in onCreate, stopped in onDestroy.
 */
public final class GameWatcher {

    private static final String TAG = "GameWatcher";

    /**
     * The single package we care about. Mobile Legends: Bang Bang
     * (global build). If you ever need to track a different game,
     * change this constant - nothing else needs to be touched.
     */
    static final String TRACKED_PACKAGE = "com.mobile.legends";

    // ---- Game states written to Firebase ---------------------------------
    /** Game is on screen right now (last FG event &gt; last BG event). */
    public static final String STATE_PLAYING = "playing";
    /** Game was on screen recently but isn't now. */
    public static final String STATE_MINIMIZED = "minimized";

    /** How often we ask UsageStatsManager. 5s is a good balance
     *  between "shows up quickly" and "doesn't burn battery". */
    private static final long POLL_INTERVAL_MS = 5_000L;

    /** How far back we look in UsageStatsManager events on each poll.
     *  Must be at least as large as MINIMIZED_WINDOW_MS so we can see
     *  the "user opened the game 9 minutes ago and then went to chat"
     *  pattern. Events are cheap (UsageStatsManager keeps them in a
     *  ring buffer), so a wide window is fine. */
    private static final long QUERY_WINDOW_MS = 12L * 60 * 1000;

    /** "Minimized" lifetime: how long after the last foreground event
     *  for the game we keep reporting MINIMIZED. After this, we drop
     *  the flag entirely (state -&gt; null). 10 minutes was chosen as a
     *  compromise between "long enough to cover a quick mid-match chat
     *  reply" and "short enough that we don't lie about people who
     *  forgot to close the game an hour ago". */
    static final long MINIMIZED_WINDOW_MS = 10L * 60 * 1000;

    /** How long readers should trust the "playing" state after the
     *  writer last refreshed gameTs. If a phone goes to sleep or the
     *  service dies, the flag becomes stale and clients stop showing
     *  "Playing X" without us needing to actively clear anything. */
    public static final long PLAYING_TTL_MS = 60L * 1000;

    /** Same idea for the minimized state. We refresh gameTs every
     *  time we write, so as long as the watcher is alive and we
     *  haven't crossed MINIMIZED_WINDOW_MS the reader still sees a
     *  fresh timestamp. If the writer dies, reader will let the
     *  state go stale after this. */
    public static final long MINIMIZED_TTL_MS = MINIMIZED_WINDOW_MS;

    private final Context appCtx;
    private final String login;
    private final UsageStatsManager usageStats;
    private final Handler handler;
    private boolean running;
    /** Last value we successfully wrote to Firebase. Used as a "dirty"
     *  check so identical updates don't hit the DB. */
    private String lastReportedState;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                pollOnce();
            } catch (Throwable t) {
                // Should never throw - but if UsageStatsManager misbehaves
                // on some OEM, we still want the next tick to run.
                Log.w(TAG, "poll failed", t);
            }
            if (running) handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public GameWatcher(Context ctx, String login) {
        this.appCtx = ctx.getApplicationContext();
        this.login = login;
        this.usageStats = (UsageStatsManager)
                this.appCtx.getSystemService(Context.USAGE_STATS_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Check whether the user has granted the Usage Access special
     *  permission. Used by Settings UI to decide what to do on tap
     *  and by the watcher itself to avoid starting if it can't read
     *  the foreground app. */
    public static boolean hasUsageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            AppOpsManager aom = (AppOpsManager)
                    ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = aom.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.getPackageName());
            } else {
                //noinspection deprecation
                mode = aom.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Begin polling. If usage access isn't granted or the system
     * service is missing, this is a no-op. Idempotent.
     */
    public void start() {
        if (running) return;
        if (usageStats == null) return;
        if (!hasUsageAccess(appCtx)) {
            Log.i(TAG, "no usage access - watcher idle");
            return;
        }
        running = true;
        lastReportedState = null;
        handler.post(tick);
    }

    /**
     * Stop polling. Also writes {@code gameState=null} to Firebase
     * if our last reported state was non-null, so we don't leave
     * the user falsely "playing" / "minimized" after logout / service kill.
     */
    public void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        if (lastReportedState != null) {
            try {
                RemoteUsers.setGameState(login, null);
            } catch (Throwable ignored) {}
        }
        lastReportedState = null;
    }

    /**
     * Single sample: query usage events in the recent window, find
     * the most recent foreground / background events for our package,
     * and classify the current state.
     */
    private void pollOnce() {
        if (usageStats == null) return;
        long now = System.currentTimeMillis();
        UsageEvents events = usageStats.queryEvents(now - QUERY_WINDOW_MS, now);
        if (events == null) return;

        long lastForegroundTs = -1L;
        long lastBackgroundTs = -1L;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (!TRACKED_PACKAGE.equals(e.getPackageName())) continue;
            int type = e.getEventType();
            // ACTIVITY_RESUMED / ACTIVITY_PAUSED are the modern names
            // for MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND (same numeric
            // values 1 / 2). Comparing against the older constants
            // works on every API level we target.
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundTs = Math.max(lastForegroundTs, e.getTimeStamp());
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                lastBackgroundTs = Math.max(lastBackgroundTs, e.getTimeStamp());
            }
        }

        // Classify:
        //   no FG events at all          -> null  (game wasn't open recently)
        //   FG >= BG                     -> playing
        //   FG <  BG  AND  FG fresh      -> minimized
        //   FG <  BG  AND  FG too old    -> null
        String newState;
        if (lastForegroundTs <= 0) {
            newState = null;
        } else if (lastForegroundTs >= lastBackgroundTs) {
            newState = STATE_PLAYING;
        } else if ((now - lastForegroundTs) < MINIMIZED_WINDOW_MS) {
            newState = STATE_MINIMIZED;
        } else {
            newState = null;
        }

        // Diff against last successful write. equal() is null-safe.
        if (!equal(newState, lastReportedState)) {
            try {
                RemoteUsers.setGameState(login, newState);
                lastReportedState = newState;
            } catch (Throwable t) {
                // Don't mark as reported - we'll retry on next tick.
                Log.w(TAG, "setGameState failed", t);
            }
        }
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}