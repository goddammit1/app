package com.goddddd.notification;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class LoginActivity extends AppCompatActivity {

    private EditText etLogin, etPassword;
    private MaterialButton btnLogin;
    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        session = new SessionManager(this);

        if (session.isLoggedIn()) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        etLogin = findViewById(R.id.etLogin);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        TextView tvGoRegister = findViewById(R.id.tvGoRegister);

        btnLogin.setOnClickListener(v -> tryLogin());

        tvGoRegister.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
    }

    private void tryLogin() {
        String login = etLogin.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (TextUtils.isEmpty(login) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "Enter login and password", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);
        BusyOverlay.show(this, "Signing in...");
        RemoteUsers.loginRemote(login, password, new RemoteUsers.AuthCallback() {
            @Override
            public void onSuccess(String login) {
                BusyOverlay.hide(LoginActivity.this);
                session.login(login);
                InboxService.start(getApplicationContext());
                Toast.makeText(LoginActivity.this,
                        "Welcome, " + login + "!", Toast.LENGTH_SHORT).show();
                goToMain();
            }

            @Override
            public void onError(String message) {
                BusyOverlay.hide(LoginActivity.this);
                btnLogin.setEnabled(true);
                Toast.makeText(LoginActivity.this,
                        message != null ? message : "Login failed",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}