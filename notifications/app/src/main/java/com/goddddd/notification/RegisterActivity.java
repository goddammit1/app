package com.goddddd.notification;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class RegisterActivity extends AppCompatActivity {

    private static final int MIN_LOGIN_LEN = 3;
    private static final int MIN_PASS_LEN = 6;

    private EditText etLogin, etPassword, etPasswordConfirm;
    private MaterialButton btnRegister;
    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        session = new SessionManager(this);

        etLogin = findViewById(R.id.etLogin);
        etPassword = findViewById(R.id.etPassword);
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm);
        btnRegister = findViewById(R.id.btnRegister);
        TextView tvGoLogin = findViewById(R.id.tvGoLogin);

        btnRegister.setOnClickListener(v -> tryRegister());

        tvGoLogin.setOnClickListener(v -> finish());
    }

    private void tryRegister() {
        String login = etLogin.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etPasswordConfirm.getText().toString();

        if (TextUtils.isEmpty(login) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }
        if (login.length() < MIN_LOGIN_LEN) {
            Toast.makeText(this, "Login must be at least " + MIN_LOGIN_LEN + " characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < MIN_PASS_LEN) {
            Toast.makeText(this, "Password must be at least " + MIN_PASS_LEN + " characters", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!password.equals(confirm)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        btnRegister.setEnabled(false);
        BusyOverlay.show(this, "Creating account...");
        RemoteUsers.registerRemote(login, password, new RemoteUsers.AuthCallback() {
            @Override
            public void onSuccess(String login) {
                BusyOverlay.hide(RegisterActivity.this);
                session.login(login);
                InboxService.start(getApplicationContext());
                Toast.makeText(RegisterActivity.this, "Account created!",
                        Toast.LENGTH_SHORT).show();
                Intent i = new Intent(RegisterActivity.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void onError(String message) {
                BusyOverlay.hide(RegisterActivity.this);
                btnRegister.setEnabled(true);
                Toast.makeText(RegisterActivity.this,
                        message != null ? message : "Registration failed",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}