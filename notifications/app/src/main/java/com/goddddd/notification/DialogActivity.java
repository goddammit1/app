package com.goddddd.notification;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class DialogActivity extends AppCompatActivity {
    @Override
    public void onBackPressed() {}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        setContentView(R.layout.activity_dialog);
        setFinishOnTouchOutside(false);

        // Title can be passed via intent extra (e.g. "ALERT from user42")
        String title = getIntent() != null ? getIntent().getStringExtra("title") : null;
        if (title != null && !title.isEmpty()) {
            TextView tv = findViewById(R.id.textView);
            if (tv != null) tv.setText(title);
        }

        final String alertId = getIntent() != null
                ? getIntent().getStringExtra("alertId") : null;
        final SessionManager session = new SessionManager(getApplicationContext());

        Button yes = findViewById(R.id.btnYes);
        Button no = findViewById(R.id.btnNo);
        yes.setOnClickListener(b -> {
            handleAnswer(alertId, session, true);
        });
        no.setOnClickListener(b -> {
            handleAnswer(alertId, session, false);
        });
    }

    private void handleAnswer(String alertId, SessionManager session, boolean ready) {
        if (alertId != null && session.isLoggedIn()) {
            RemoteUsers.postResponse(alertId, session.getLogin(), ready);
        }
        MyFirebaseMessagingService.stopAll(this);
        // Open report screen for this alert (skip if no alertId, e.g. legacy FCM)
        if (alertId != null && !alertId.isEmpty()) {
            try {
                Intent i = new Intent(getApplicationContext(), AlertDetailActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alertId);
                startActivity(i);
            } catch (Exception ignored) {}
        }
        finish();
    }
}
