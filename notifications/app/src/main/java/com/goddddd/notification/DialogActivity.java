package com.goddddd.notification;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

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

    private void handleAnswer(final String alertId, final SessionManager session,
                              final boolean ready) {
        // Always tear the alarm down first, regardless of bookkeeping.
        MyFirebaseMessagingService.stopAll(this);

        if (!session.isLoggedIn() || alertId == null || alertId.isEmpty()) {
            finish();
            return;
        }
        final String me = session.getLogin();

        // Per-alert response goes into history regardless of selfTest.
        RemoteUsers.postResponse(alertId, me, ready);

        // The persistent status node must NOT be written for self-test
        // alerts: those are a diagnostic feature and should leave the
        // user's real "ready / not ready" untouched. Resolve the alert's
        // selfTest flag from RTDB before deciding.
        RemoteUsers.alertsRef().child(alertId).child("selfTest")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Boolean st = snap.getValue(Boolean.class);
                        if (!Boolean.TRUE.equals(st)) {
                            RemoteUsers.setStatus(me, ready);
                        }
                        finish();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        // If we cannot check, err on the safe side and
                        // skip the status write so a self-test never
                        // poisons real-world readiness.
                        finish();
                    }
                });
    }
}
