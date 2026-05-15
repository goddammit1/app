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
            // Re-subscribe to topic (init FCM)
            FirebaseMessaging.getInstance().subscribeToTopic("group1")
                    .addOnCompleteListener(task -> Log.d("BootReceiver", "subscribed to topic"));

            // Also fetch token (for debug)
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(t -> {
                        if (t.isSuccessful()) {
                            Log.d("BootReceiver", "token=" + t.getResult());
                        }
                    });

            // Restart inbox listener if user is logged in
            SessionManager sm = new SessionManager(context);
            if (sm.isLoggedIn()) {
                InboxService.start(context.getApplicationContext());
            }
        }
    }
}