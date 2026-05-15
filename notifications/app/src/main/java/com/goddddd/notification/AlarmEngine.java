package com.goddddd.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Common alarm logic: sound + vibration + overlay + system notification.
 *
 * Duplicate strategy: while isActive() == true, new trigger() calls do nothing.
 * When the user dismisses via stopAll(), the flag is reset and DismissCallback
 * listeners are notified so InboxService can process the next queued message.
 */
public class AlarmEngine {

    public static final String CHANNEL_ID = "high_priority_alarm";
    public static final int NOTIF_ID = 1;

    private static MediaPlayer mediaPlayer;
    private static Vibrator vibrator;
    private static View overlayView;
    private static WindowManager windowManager;

    private static volatile boolean active = false;

    private static final List<DismissCallback> dismissCallbacks = new ArrayList<>();

    public interface DismissCallback {
        void onDismissed();
    }

    public static synchronized boolean isActive() {
        return active;
    }

    public static synchronized void addDismissListener(DismissCallback cb) {
        if (cb != null && !dismissCallbacks.contains(cb)) dismissCallbacks.add(cb);
    }

    public static synchronized void removeDismissListener(DismissCallback cb) {
        dismissCallbacks.remove(cb);
    }

    /** Triggers an alarm. If one is already active, returns false. */
    public static synchronized boolean trigger(Context appCtx, String title, String body) {
        return trigger(appCtx, title, body, null);
    }

    /** Same as {@link #trigger(Context, String, String)} but also passes an alertId
     *  so the DialogActivity / overlay can post the user's response back. */
    public static synchronized boolean trigger(Context appCtx, String title, String body,
                                               String alertId) {
        if (active) return false;
        active = true;

        PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "alarm:engine");
            wl.acquire(10_000);
        }
        try {
            NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureChannel(nm);
            startAlarmEffects(appCtx);

            Intent fsIntent = new Intent(appCtx, DialogActivity.class);
            fsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            fsIntent.putExtra("title", title);
            fsIntent.putExtra("body", body);
            if (alertId != null) fsIntent.putExtra("alertId", alertId);
            PendingIntent fsi = PendingIntent.getActivity(appCtx, 0, fsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = buildNotification(appCtx, fsi, title, body);
            if (nm != null) nm.notify(NOTIF_ID, b.build());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(appCtx)) {
                try {
                    appCtx.startActivity(fsIntent);
                } catch (Exception ignored) {}
            } else {
                showOverlayDialog(appCtx, title, body, alertId);
            }
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
        return true;
    }

    public static void stopAll(Context context) {
        boolean wasActive;
        List<DismissCallback> toNotify;
        synchronized (AlarmEngine.class) {
            wasActive = active;
            active = false;
            toNotify = new ArrayList<>(dismissCallbacks);
        }
        stopAlarmInternals(context);
        if (wasActive) {
            for (DismissCallback cb : toNotify) {
                try { cb.onDismissed(); } catch (Exception ignored) {}
            }
        }
    }

    private static void stopAlarmInternals(Context context) {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        removeOverlay();
        NotificationManager nm = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private static NotificationCompat.Builder buildNotification(Context ctx,
                                                                PendingIntent fsi,
                                                                String title,
                                                                String body) {
        String safeTitle = title != null ? title : "ALERT!";
        String safeBody = body != null ? body : "Tap to acknowledge";
        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                // BigTextStyle lets the full message be visible when the user
                // pulls down the notification, even if it doesn't fit in one line.
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(safeTitle)
                        .bigText(safeBody))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fsi, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                        "Alarm", NotificationManager.IMPORTANCE_HIGH);
                c.setBypassDnd(true);
                c.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                c.setSound(null, null);
                nm.createNotificationChannel(c);
            }
        }
    }

    private static void startAlarmEffects(Context ctx) {
        stopAlarmInternals(ctx);
        try {
            // 1) custom sound picked by user; 2) fallback to bundled alarm
            Uri uri = SoundPrefs.getCustomAlarmUri(ctx);
            if (uri == null) {
                uri = Uri.parse("android.resource://" + ctx.getPackageName()
                        + "/" + R.raw.alarm);
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(ctx, uri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 1000, 500, 1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private static void showOverlayDialog(Context ctx, String title, String body,
                                          final String alertId) {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            try {
                if (overlayView != null) return;
                LayoutInflater inflater = (LayoutInflater) ctx
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                overlayView = inflater.inflate(R.layout.overlay_dialog, null);
                Button yes = overlayView.findViewById(R.id.btnYesOverlay);
                Button no = overlayView.findViewById(R.id.btnNoOverlay);
                TextView titleTv = overlayView.findViewById(R.id.overlayTitle);
                TextView messageTv = overlayView.findViewById(R.id.overlayMessage);
                titleTv.setText(title != null ? title : "ALERT!");
                messageTv.setText(body != null ? body : "Tap to acknowledge.");

                final SessionManager sm = new SessionManager(ctx.getApplicationContext());
                yes.setOnClickListener(v -> handleOverlayAnswer(ctx, sm, alertId, true));
                no.setOnClickListener(v -> handleOverlayAnswer(ctx, sm, alertId, false));

                windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                int layoutFlag;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                } else {
                    layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
                }
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutFlag,
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP;
                params.y = 100;
                if (windowManager != null) {
                    windowManager.addView(overlayView, params);
                }
            } catch (Exception e) {
                try {
                    Intent i = new Intent(ctx.getApplicationContext(), DialogActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    i.putExtra("title", title);
                    i.putExtra("body", body);
                    if (alertId != null) i.putExtra("alertId", alertId);
                    ctx.startActivity(i);
                } catch (Exception ignored) {}
            }
        });
    }

    /** Same as DialogActivity.handleAnswer but for the system overlay path. */
    private static void handleOverlayAnswer(Context ctx, SessionManager sm,
                                            String alertId, boolean ready) {
        if (alertId != null && sm.isLoggedIn()) {
            RemoteUsers.postResponse(alertId, sm.getLogin(), ready);
        }
        stopAll(ctx.getApplicationContext());
        if (alertId != null && !alertId.isEmpty()) {
            try {
                Intent i = new Intent(ctx.getApplicationContext(), AlertDetailActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alertId);
                ctx.getApplicationContext().startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private static void removeOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager.removeView(overlayView);
            }
        } catch (Exception ignored) {}
        overlayView = null;
        windowManager = null;
    }
}