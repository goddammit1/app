package com.goddddd.notification;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Profile screen: avatar, display name, password change, delete account.
 *
 * Wired against {@link RemoteUsers}: all writes happen there. The
 * avatar is encoded client-side as a small base64 JPEG (max 192px,
 * quality 60) and stored at {@code userAvatars/<login>} via
 * {@link RemoteUsers#setAvatar}. The flag {@code users/<login>/hasAvatar}
 * lets other clients know the user has a picture without pulling the
 * full payload until they actually need it.
 */
public class ProfileActivity extends AppCompatActivity {

    /** Maximum side of the encoded avatar, in px. Same order of
     *  magnitude as the home-screen status slots. */
    private static final int AVATAR_MAX_PX = 192;
    private static final int AVATAR_JPEG_QUALITY = 60;

    private SessionManager session;
    private String me;

    private ImageView avatarImage;
    private ImageView avatarPlaceholder;
    private EditText etDisplayName;
    private EditText etOldPassword;
    private EditText etNewPassword;
    private EditText etNewPasswordConfirm;

    private final ActivityResultLauncher<String[]> pickAvatarLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onAvatarPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        session = new SessionManager(this);
        if (!session.isLoggedIn()) {
            finish();
            return;
        }
        me = session.getLogin();

        setContentView(R.layout.activity_profile);

        avatarImage = findViewById(R.id.avatarImage);
        avatarPlaceholder = findViewById(R.id.avatarPlaceholder);
        etDisplayName = findViewById(R.id.etDisplayName);
        etOldPassword = findViewById(R.id.etOldPassword);
        etNewPassword = findViewById(R.id.etNewPassword);
        etNewPasswordConfirm = findViewById(R.id.etNewPasswordConfirm);

        MaterialButton btnSaveName = findViewById(R.id.btnSaveName);
        MaterialButton btnSavePassword = findViewById(R.id.btnSavePassword);
        MaterialButton btnDeleteAccount = findViewById(R.id.btnDeleteAccount);

        View avatarContainer = findViewById(R.id.avatarContainer);
        avatarContainer.setOnClickListener(v -> openAvatarPicker());
        avatarContainer.setOnLongClickListener(v -> {
            confirmRemoveAvatar();
            return true;
        });

        btnSaveName.setOnClickListener(v -> saveDisplayName());
        btnSavePassword.setOnClickListener(v -> changePassword());
        btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());

        loadProfile();
    }

    // ---- Load --------------------------------------------------------

    private void loadProfile() {
        RemoteUsers.usersRef().child(me).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String dn = snapshot.child("displayName").getValue(String.class);
                        if (dn != null) etDisplayName.setText(dn);

                        Boolean has = snapshot.child("hasAvatar").getValue(Boolean.class);
                        Long ts = snapshot.child("avatarTs").getValue(Long.class);
                        if (Boolean.TRUE.equals(has) && ts != null) {
                            AvatarCache.request(me, ts, bm -> showAvatar(bm));
                        } else {
                            showAvatar(null);
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(ProfileActivity.this,
                                "Load error: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showAvatar(Bitmap bm) {
        if (bm != null) {
            avatarImage.setImageBitmap(bm);
            avatarImage.setVisibility(View.VISIBLE);
            avatarPlaceholder.setVisibility(View.GONE);
        } else {
            avatarImage.setImageDrawable(null);
            avatarImage.setVisibility(View.GONE);
            avatarPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    // ---- Display name -----------------------------------------------

    private void saveDisplayName() {
        String name = etDisplayName.getText().toString().trim();
        BusyOverlay.show(this, "Saving...");
        RemoteUsers.setDisplayName(me, name, new RemoteUsers.SimpleCallback() {
            @Override public void onSuccess() {
                BusyOverlay.hide(ProfileActivity.this);
                Toast.makeText(ProfileActivity.this,
                        R.string.profile_name_saved,
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                BusyOverlay.hide(ProfileActivity.this);
                Toast.makeText(ProfileActivity.this,
                        "Error: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Change password --------------------------------------------

    private void changePassword() {
        String oldP = etOldPassword.getText().toString();
        String newP = etNewPassword.getText().toString();
        String newP2 = etNewPasswordConfirm.getText().toString();

        if (TextUtils.isEmpty(oldP) || TextUtils.isEmpty(newP)) {
            Toast.makeText(this, "Fill all password fields",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (newP.length() < 6) {
            Toast.makeText(this, R.string.profile_password_short,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!newP.equals(newP2)) {
            Toast.makeText(this, R.string.profile_password_mismatch,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        BusyOverlay.show(this, "Updating...");
        RemoteUsers.changePassword(me, oldP, newP, new RemoteUsers.SimpleCallback() {
            @Override public void onSuccess() {
                BusyOverlay.hide(ProfileActivity.this);
                etOldPassword.setText("");
                etNewPassword.setText("");
                etNewPasswordConfirm.setText("");
                Toast.makeText(ProfileActivity.this,
                        R.string.profile_password_changed,
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                BusyOverlay.hide(ProfileActivity.this);
                if ("Wrong password".equalsIgnoreCase(message)) {
                    Toast.makeText(ProfileActivity.this,
                            R.string.profile_password_wrong,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ProfileActivity.this,
                            "Error: " + message,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ---- Avatar pick / remove ---------------------------------------

    private void openAvatarPicker() {
        try {
            pickAvatarLauncher.launch(new String[]{"image/*"});
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.profile_avatar_load_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRemoveAvatar() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.profile_avatar_remove_confirm)
                .setPositiveButton(R.string.profile_avatar_remove_confirm, (d, w) ->
                        doRemoveAvatar())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doRemoveAvatar() {
        BusyOverlay.show(this, "Removing...");
        RemoteUsers.clearAvatar(me, new RemoteUsers.SimpleCallback() {
            @Override public void onSuccess() {
                BusyOverlay.hide(ProfileActivity.this);
                AvatarCache.invalidate(me);
                showAvatar(null);
                Toast.makeText(ProfileActivity.this,
                        R.string.profile_avatar_removed,
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                BusyOverlay.hide(ProfileActivity.this);
                Toast.makeText(ProfileActivity.this,
                        "Error: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onAvatarPicked(Uri uri) {
        if (uri == null) return;
        // Try to "persist" read permission so subsequent reads work
        // even after activity death (not strictly required since we
        // copy immediately, but harmless).
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}

        BusyOverlay.show(this, "Saving avatar...");
        new Thread(() -> {
            String encoded = null;
            String errorMsg = null;
            try {
                encoded = encodeAvatar(uri);
            } catch (Throwable t) {
                errorMsg = t.getMessage();
            }
            final String dataUri = encoded;
            final String err = errorMsg;
            runOnUiThread(() -> {
                if (dataUri == null) {
                    BusyOverlay.hide(this);
                    Toast.makeText(this,
                            getString(R.string.profile_avatar_load_error,
                                    err == null ? "?" : err),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                RemoteUsers.setAvatar(me, dataUri, new RemoteUsers.SimpleCallback() {
                    @Override public void onSuccess() {
                        BusyOverlay.hide(ProfileActivity.this);
                        AvatarCache.invalidate(me);
                        Toast.makeText(ProfileActivity.this,
                                R.string.profile_avatar_saved,
                                Toast.LENGTH_SHORT).show();
                        loadProfile(); // reloads bitmap from RTDB through cache
                    }
                    @Override public void onError(String message) {
                        BusyOverlay.hide(ProfileActivity.this);
                        Toast.makeText(ProfileActivity.this,
                                "Error: " + message,
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        }).start();
    }

    /**
     * Read the picked image, downscale to a square no larger than
     * AVATAR_MAX_PX on the longer side, compress to JPEG at quality 60,
     * and return as a data: URL ready for RTDB.
     */
    private String encodeAvatar(Uri uri) throws Exception {
        // Pass 1: read dimensions only.
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot open image");
            BitmapFactory.decodeStream(in, null, opts);
        }
        int w = opts.outWidth, h = opts.outHeight;
        if (w <= 0 || h <= 0) throw new Exception("Bad image");
        int sample = 1;
        while ((w / sample) > AVATAR_MAX_PX * 2 || (h / sample) > AVATAR_MAX_PX * 2) {
            sample *= 2;
        }

        // Pass 2: actually decode.
        BitmapFactory.Options decOpts = new BitmapFactory.Options();
        decOpts.inSampleSize = sample;
        Bitmap src;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Cannot open image");
            src = BitmapFactory.decodeStream(in, null, decOpts);
        }
        if (src == null) throw new Exception("Cannot decode image");

        // Center-square crop, then scale to AVATAR_MAX_PX.
        int sw = src.getWidth(), sh = src.getHeight();
        int side = Math.min(sw, sh);
        int x = (sw - side) / 2, y = (sh - side) / 2;
        Bitmap square = Bitmap.createBitmap(src, x, y, side, side);
        if (square != src) src.recycle();
        Bitmap scaled = Bitmap.createScaledBitmap(square,
                AVATAR_MAX_PX, AVATAR_MAX_PX, true);
        if (scaled != square) square.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_JPEG_QUALITY, baos);
        scaled.recycle();
        String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + b64;
    }

    // ---- Delete account ---------------------------------------------

    private void confirmDeleteAccount() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_delete_account)
                .setMessage(R.string.profile_delete_confirm)
                .setPositiveButton(R.string.profile_delete_account,
                        (d, w) -> doDeleteAccount())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doDeleteAccount() {
        BusyOverlay.show(this, "Deleting...");
        try { RemoteUsers.detachPresence(); } catch (Throwable ignored) {}
        InboxService.stop(getApplicationContext());

        RemoteUsers.deleteAccount(me, new RemoteUsers.SimpleCallback() {
            @Override public void onSuccess() {
                BusyOverlay.hide(ProfileActivity.this);
                session.logout();
                AvatarCache.invalidate(me);
                Intent i = new Intent(ProfileActivity.this, LoginActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }
            @Override public void onError(String message) {
                BusyOverlay.hide(ProfileActivity.this);
                Toast.makeText(ProfileActivity.this,
                        "Error: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}