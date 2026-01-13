package com.goddddd.notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessaging;
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootReceiver", "BOOT_COMPLETED received");
            // Переподписка на топик (инициализация FCM)
            FirebaseMessaging.getInstance().subscribeToTopic("group1")
                    .addOnCompleteListener(task -> Log.d("BootReceiver", "subscribed to topic"));
            // Можно также получить токен для отладки
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(t -> {
                        if (t.isSuccessful()) {
                            Log.d("BootReceiver", "FCM token after boot: " + t.getResult());
                        }
                    });
        }
    }
}