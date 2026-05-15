package com.goddddd.notification;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_NOTIF = 1001;

    private SessionManager session;
    private TextView soundStatus;

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

        TextView welcomeText = findViewById(R.id.welcomeText);
        welcomeText.setText(getString(R.string.main_welcome, session.getLogin()));

        MaterialButton btnLogout = findViewById(R.id.btnLogout);
        btnLogout.setOnClickListener(v -> confirmLogout());

        MaterialButton btnPickUser = findViewById(R.id.btnPickUser);
        btnPickUser.setOnClickListener(v ->
                startActivity(new Intent(this, UsersListActivity.class)));

        MaterialButton btnBroadcast = findViewById(R.id.btnBroadcast);
        btnBroadcast.setOnClickListener(v -> askBroadcast());

        MaterialButton btnOnlineUsers = findViewById(R.id.btnOnlineUsers);
        btnOnlineUsers.setOnClickListener(v -> {
            Intent i = new Intent(this, UsersListActivity.class);
            i.putExtra(UsersListActivity.EXTRA_VIEW_ONLY, true);
            startActivity(i);
        });

        MaterialButton btnReports = findViewById(R.id.btnReports);
        btnReports.setOnClickListener(v ->
                startActivity(new Intent(this, ReportsActivity.class)));

        // Custom alarm sound picker
        MaterialButton btnPickSound = findViewById(R.id.btnPickSound);
        soundStatus = findViewById(R.id.soundStatus);
        btnPickSound.setOnClickListener(v -> openSoundChooser());
        btnPickSound.setOnLongClickListener(v -> {
            confirmResetSound();
            return true;
        });
        updateSoundStatus();

        // Discreet "request overlay" link in the bottom-left.
        TextView btnRequestOverlay = findViewById(R.id.btnRequestOverlay);
        btnRequestOverlay.setOnClickListener(v -> openOverlaySettings());

        // Discreet self-test: sends an alert to yourself after a 3 second delay,
        // so you can lock the screen and see the alert appear.
        TextView selfTest = findViewById(R.id.btnSelfTest);
        selfTest.setOnClickListener(v -> {
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
        });

        FirebaseMessaging.getInstance().subscribeToTopic("group1");
        requestNotificationPermission();
        // Overlay / FSI / battery optimization - optional, do not force user

        // Check for app updates on GitHub Releases (offers an in-app install).
        UpdateChecker.checkAsync(this);
    }

    private void askBroadcast() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Alert text (optional)");
        new AlertDialog.Builder(this)
                .setTitle("Send to everyone")
                .setMessage("All registered users will get this alert.")
                .setView(input)
                .setPositiveButton("Send", (d, w) -> {
                    String text = input.getText().toString().trim();
                    RemoteUsers.broadcastMessage(session.getLogin(), text,
                            new RemoteUsers.SendCallback() {
                                @Override
                                public void onSuccess(String alertId) {
                                    Toast.makeText(MainActivity.this,
                                            "Broadcast sent",
                                            Toast.LENGTH_SHORT).show();
                                    openReportFor(alertId);
                                }
                                @Override
                                public void onError(String message) {
                                    Toast.makeText(MainActivity.this,
                                            "Broadcast error: " + message,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this,
                    "Overlay permission not needed on this Android version",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission already granted",
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
            } catch (Exception ignored) {
                Toast.makeText(this,
                        "Open: Settings -> Apps -> Notifications -> 3 dots -> Allow display over other apps",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.logout_title)
                .setMessage(R.string.logout_msg)
                .setPositiveButton(R.string.logout_yes, (d, w) -> {
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

    /** Opens the per-alert report screen by id. */
    private void openReportFor(String alertId) {
        if (alertId == null || alertId.isEmpty()) return;
        Intent i = new Intent(this, AlertDetailActivity.class);
        i.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alertId);
        startActivity(i);
    }

    // -------- Custom alarm sound --------

    private void updateSoundStatus() {
        String name = SoundPrefs.getCustomAlarmName(this);
        if (soundStatus != null) {
            soundStatus.setText(getString(R.string.sound_picked,
                    name != null && !name.isEmpty()
                            ? name
                            : getString(R.string.sound_default)));
        }
    }

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
                    updateSoundStatus();
                    Toast.makeText(this, R.string.sound_reset_done,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onSoundPicked(Uri uri) {
        if (uri == null) return;
        try {
            // 1) Read display name from the picker
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

            // 2) Copy the file into our internal storage. We always overwrite
            //    the same target file so we never accumulate leftovers.
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
            updateSoundStatus();
            Toast.makeText(this, getString(R.string.sound_saved, displayName),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.sound_save_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

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