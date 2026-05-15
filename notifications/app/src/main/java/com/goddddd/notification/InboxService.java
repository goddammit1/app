package com.goddddd.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayDeque;
import java.util.Deque;

public class InboxService extends Service implements AlarmEngine.DismissCallback {

    private static final String TAG = "InboxService";
    private static final String FG_CHANNEL_ID = "inbox_listener";
    private static final int FG_NOTIF_ID = 42;

    private DatabaseReference inboxRef;
    private ChildEventListener listener;
    private String myLogin;
    private boolean primed = false;

    private final Deque<PendingMsg> queue = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class PendingMsg {
        final String key;
        final String from;
        final String text;
        final String alertId;
        PendingMsg(String key, String from, String text, String alertId) {
            this.key = key; this.from = from; this.text = text; this.alertId = alertId;
        }
    }

    /** Safe start: catches all exceptions so the service start never crashes the app. */
    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, InboxService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start InboxService", t);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, InboxService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startInForeground();
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            // If we cannot go foreground, stop self so the system does not kill the app
            stopSelf();
            return;
        }
        AlarmEngine.addDismissListener(this);

        // Online status follows this service's lifetime:
        //  - service alive (foreground or background) => user is online (can receive alerts)
        //  - service dead (swiped out / killed)       => user is offline
        //
        // attachPresence() listens to Firebase ".info/connected" so the
        // online flag is automatically re-asserted after every reconnect
        // (Wi-Fi toggle, VPN toggle, Doze recovery, etc). Without this we
        // would only set online=true once, and the server-side
        // onDisconnect would flip us to "false" on the first network blip
        // and never flip back.
        try {
            SessionManager sm = new SessionManager(this);
            if (sm.isLoggedIn()) {
                RemoteUsers.attachPresence(sm.getLogin());
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            SessionManager sm = new SessionManager(this);
            if (!sm.isLoggedIn()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            String login = sm.getLogin();
            if (login == null || login.equals(myLogin)) {
                return START_STICKY;
            }
            detach();
            myLogin = login;
            attach();
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand failed", t);
        }
        return START_STICKY;
    }

    private void attach() {
        primed = false;
        synchronized (this) { queue.clear(); }
        try {
            inboxRef = RemoteUsers.inboxRef(myLogin);
        } catch (Throwable t) {
            Log.e(TAG, "inboxRef failed", t);
            return;
        }
        listener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String prevKey) {
                if (!primed) return;
                enqueue(snapshot);
                processQueueIfFree();
            }

            @Override public void onChildChanged(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {}
            @Override public void onChildMoved(@NonNull DataSnapshot s, @Nullable String p) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "inbox cancelled: " + error.getMessage());
            }
        };

        inboxRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot s : snapshot.getChildren()) enqueue(s);
                primed = true;
                inboxRef.addChildEventListener(listener);
                processQueueIfFree();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                primed = true;
                inboxRef.addChildEventListener(listener);
            }
        });
    }

    private synchronized void enqueue(DataSnapshot snapshot) {
        String key = snapshot.getKey();
        if (key == null) return;
        for (PendingMsg m : queue) {
            if (key.equals(m.key)) return;
        }
        String from = snapshot.child("from").getValue(String.class);
        String text = snapshot.child("text").getValue(String.class);
        String alertId = snapshot.child("alertId").getValue(String.class);
        queue.addLast(new PendingMsg(key, from, text, alertId));
    }

    private void processQueueIfFree() {
        mainHandler.post(() -> {
            if (AlarmEngine.isActive()) return;
            PendingMsg msg;
            synchronized (this) {
                msg = queue.pollFirst();
            }
            if (msg == null) return;

            if (inboxRef != null) inboxRef.child(msg.key).removeValue();

            String title = "ALERT"
                    + (msg.from != null && !msg.from.isEmpty() ? " from " + msg.from : "");
            String body = (msg.text != null && !msg.text.isEmpty())
                    ? msg.text : "Tap to acknowledge";

            boolean started = AlarmEngine.trigger(getApplicationContext(), title, body, msg.alertId);
            if (!started) {
                synchronized (this) { queue.addFirst(msg); }
            }
        });
    }

    @Override
    public void onDismissed() {
        mainHandler.postDelayed(this::processQueueIfFree, 400);
    }

    private void detach() {
        if (inboxRef != null && listener != null) {
            inboxRef.removeEventListener(listener);
        }
        inboxRef = null;
        listener = null;
        synchronized (this) { queue.clear(); }
    }

    @Override
    public void onDestroy() {
        // Try to mark offline before tearing everything down.
        try {
            RemoteUsers.detachPresence();
        } catch (Throwable ignored) {}
        AlarmEngine.removeDismissListener(this);
        detach();
        super.onDestroy();
    }

    /**
     * Called when the user swipes the app away from Recents. Most OEMs kill
     * the service immediately after, so we explicitly flip the status now.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            RemoteUsers.detachPresence();
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            if (nm.getNotificationChannel(FG_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        FG_CHANNEL_ID,
                        "Background listener",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new NotificationCompat.Builder(this, FG_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Notifications active")
                .setContentText("Waiting for incoming messages")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+ we must specify the type for foreground services
            startForeground(FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_NOTIF_ID, n);
        }
    }
}