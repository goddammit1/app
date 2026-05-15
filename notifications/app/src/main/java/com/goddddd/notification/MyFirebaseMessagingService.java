package com.goddddd.notification;

import android.content.Context;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives FCM messages (e.g. topic group1 broadcast) and triggers the alarm
 * via AlarmEngine. Individual / group alerts in this build are delivered via
 * Realtime DB and InboxService - not via FCM data-messages.
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = "ALERT!";
        String body = "Tap to acknowledge";
        if (message.getNotification() != null) {
            if (message.getNotification().getTitle() != null) {
                title = message.getNotification().getTitle();
            }
            if (message.getNotification().getBody() != null) {
                body = message.getNotification().getBody();
            }
        }
        AlarmEngine.trigger(getApplicationContext(), title, body);
    }

    /** Kept for backwards compatibility (DialogActivity calls this). */
    public static void stopAll(Context context) {
        AlarmEngine.stopAll(context);
    }
}