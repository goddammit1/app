package com.goddddd.notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.widget.Button;
import android.widget.TextView;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
public class MyFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "high_priority_alarm";
    private static final int NOTIF_ID = 1;
    public static MediaPlayer mediaPlayer;
    public static Vibrator vibrator;
    // Overlay-related (static so stopAll/removeOverlay can access it from anywhere)
    private static View overlayView;
    private static WindowManager windowManager;
    @Override
    public void onMessageReceived(RemoteMessage message) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = null;
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "alarm:fcm");
            wl.acquire(10000);
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            ensureChannel(nm);
            startAlarmEffects();
            // Попробуем показать overlay (если разрешено), иначе - фолбэк: startActivity + fullScreenIntent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // нет разрешения — показываем уведомление с fullScreenIntent и пытаемся запустить Activity (фолбэк)
                Intent fsIntent = new Intent(this, DialogActivity.class);
                fsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent fsi = PendingIntent.getActivity(this, 0, fsIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = buildNotification(fsi);
                if (nm != null) nm.notify(NOTIF_ID, b.build());
                // Пытаемся запустить Activity (может быть блокировано на некоторых версиях)
                try {
                    startActivity(fsIntent);
                } catch (Exception ignored) {}
            } else {
                // Есть разрешение на overlay — показываем overlay-диалог, и также уведомление (heads-up)
                Intent fsIntent = new Intent(this, DialogActivity.class);
                fsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent fsi = PendingIntent.getActivity(this, 0, fsIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = buildNotification(fsi);
                if (nm != null) nm.notify(NOTIF_ID, b.build());
                showOverlayDialog();
            }
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }
    private NotificationCompat.Builder buildNotification(PendingIntent fsi) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("ТРЕВОГА!")
                .setContentText("Нажмите, чтобы ответить")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fsi, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true);
    }
    private void startAlarmEffects() {
        // Останавливаем, если что-то уже работает
        stopAll(this);
        // 1. Звук
        try {
            // Указываем путь к файлу в res/raw
            Uri customSoundUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alarm);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getApplicationContext(), customSoundUri); // Используем наш URI
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
        // 2. Вибрация
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 1000, 500, 1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }
    public static void stopAll(Context context) {
        // Остановить звук
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        // Остановить вибрацию
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        // Убрать overlay если есть
        removeOverlay();
        // Убрать уведомление из шторки
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIF_ID);
        }
    }
    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH);
                c.setBypassDnd(true);
                c.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                c.setSound(null, null);
                nm.createNotificationChannel(c);
            }
        }
    }
    private void showOverlayDialog() {
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            try {
                if (overlayView != null) return; // уже показан
                LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
                overlayView = inflater.inflate(R.layout.overlay_dialog, null);
                Button yes = overlayView.findViewById(R.id.btnYesOverlay);
                Button no = overlayView.findViewById(R.id.btnNoOverlay);
                TextView title = overlayView.findViewById(R.id.overlayTitle);
                TextView message = overlayView.findViewById(R.id.overlayMessage);
                title.setText("ТРЕВОГА!");
                message.setText("Нажмите кнопку для подтверждения.");
                yes.setOnClickListener(v -> {
                    stopAll(getApplicationContext());
                });
                no.setOnClickListener(v -> {
                    stopAll(getApplicationContext());
                });
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
                params.y = 100; // отступ сверху (можно подправить)
                if (windowManager != null) {
                    windowManager.addView(overlayView, params);
                }
            } catch (Exception e) {
                // Если что-то пошло не так с overlay — фолбэк: запускаем Activity
                try {
                    Intent i = new Intent(getApplicationContext(), DialogActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        });
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