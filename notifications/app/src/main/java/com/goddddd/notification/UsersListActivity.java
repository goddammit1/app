package com.goddddd.notification;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick-recipient screen. Shows each user with an online / offline indicator.
 *
 * In view-only mode the user is just browsing the list:
 *  - taps do nothing,
 *  - the user themselves IS shown in the list,
 *  - a long-press on the user's own row offers to delete the account.
 */
public class UsersListActivity extends AppCompatActivity {

    /** When true, tapping a user does NOT open the send-alert dialog. */
    public static final String EXTRA_VIEW_ONLY = "viewOnly";

    private ListView listView;
    private ProgressBar progress;
    private TextView emptyText;
    private TextView hintText;

    private SessionManager session;
    private boolean viewOnly = false;
    private final List<RemoteUsers.UserInfo> users = new ArrayList<>();
    private UserAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_users);

        session = new SessionManager(this);
        if (!session.isLoggedIn()) {
            finish();
            return;
        }

        listView = findViewById(R.id.listView);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.emptyText);
        hintText = findViewById(R.id.hintText);

        viewOnly = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_VIEW_ONLY, false);
        if (viewOnly) {
            setTitle("Online users");
            if (hintText != null) {
                hintText.setText("Green = in app, grey = offline. Long-press yourself to delete account.");
            }
        }

        adapter = new UserAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            RemoteUsers.UserInfo u = users.get(position);
            if (viewOnly) return; // no action in view-only mode
            // In "pick recipient" mode we never include the current user, so just send.
            askMessageAndSend(u.login);
        });

        // Long-press to delete account (only on your own row, only in view-only mode).
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!viewOnly) return false;
            RemoteUsers.UserInfo u = users.get(position);
            String me = session.getLogin();
            if (u.login != null && u.login.equalsIgnoreCase(me)) {
                confirmDeleteAccount();
                return true;
            }
            return false;
        });

        loadUsers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUsers();
    }

    private void loadUsers() {
        progress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        RemoteUsers.loadUserListWithStatus(new RemoteUsers.UserListWithStatusCallback() {
            @Override
            public void onResult(List<RemoteUsers.UserInfo> result) {
                progress.setVisibility(View.GONE);
                users.clear();
                String me = session.getLogin();
                for (RemoteUsers.UserInfo u : result) {
                    if (u.login == null) continue;
                    // In pick-recipient mode skip self; in view-only mode include self.
                    if (!viewOnly && u.login.equalsIgnoreCase(me)) continue;
                    users.add(u);
                }
                java.util.Collections.sort(users, (a, b) -> {
                    if (a.online != b.online) return a.online ? -1 : 1;
                    String an = a.login != null ? a.login : "";
                    String bn = b.login != null ? b.login : "";
                    return an.compareToIgnoreCase(bn);
                });
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(String message) {
                progress.setVisibility(View.GONE);
                Toast.makeText(UsersListActivity.this,
                        "Load error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void askMessageAndSend(String recipient) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Alert text (optional)");

        new AlertDialog.Builder(this)
                .setTitle("Send to " + recipient)
                .setView(input)
                .setPositiveButton("Send", (d, w) -> {
                    String text = input.getText().toString().trim();
                    send(recipient, text);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void send(String recipient, String text) {
        String me = session.getLogin();
        RemoteUsers.sendMessage(recipient, me, text, new RemoteUsers.SendCallback() {
            @Override
            public void onSuccess(String alertId) {
                Toast.makeText(UsersListActivity.this,
                        "Sent to " + recipient, Toast.LENGTH_SHORT).show();
                Intent i = new Intent(UsersListActivity.this, AlertDetailActivity.class);
                i.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alertId);
                startActivity(i);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(UsersListActivity.this,
                        "Send error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void confirmDeleteAccount() {
        new AlertDialog.Builder(this)
                .setTitle("Delete account")
                .setMessage("This will delete your account and all alerts you have sent. "
                        + "You will be signed out. Continue?")
                .setPositiveButton("Delete", (d, w) -> doDeleteAccount())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDeleteAccount() {
        final String me = session.getLogin();
        if (me == null) return;
        RemoteUsers.deleteAccount(me, new RemoteUsers.SimpleCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(UsersListActivity.this,
                        "Account deleted", Toast.LENGTH_SHORT).show();
                InboxService.stop(getApplicationContext());
                session.logout();
                Intent i = new Intent(UsersListActivity.this, LoginActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(UsersListActivity.this,
                        "Delete error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------- Adapter with online/offline indicator ----------

    private class UserAdapter extends BaseAdapter {
        @Override public int getCount() { return users.size(); }
        @Override public Object getItem(int position) { return users.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RemoteUsers.UserInfo u = users.get(position);

            LinearLayout row;
            TextView dot;
            TextView name;
            TextView status;

            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                dot = (TextView) row.getChildAt(0);
                name = (TextView) row.getChildAt(1);
                status = (TextView) row.getChildAt(2);
            } else {
                row = new LinearLayout(UsersListActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padPx = (int) (16 * getResources().getDisplayMetrics().density);
                row.setPadding(padPx, padPx, padPx, padPx);

                dot = new TextView(UsersListActivity.this);
                int dotSize = (int) (10 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
                dotLp.rightMargin = (int) (12 * getResources().getDisplayMetrics().density);
                dot.setLayoutParams(dotLp);
                row.addView(dot);

                name = new TextView(UsersListActivity.this);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                name.setLayoutParams(nameLp);
                name.setTextSize(16);
                name.setTextColor(getResources().getColor(R.color.dark_text_primary));
                row.addView(name);

                status = new TextView(UsersListActivity.this);
                status.setTextSize(12);
                row.addView(status);
            }

            String me = session.getLogin();
            boolean isMe = u.login != null && u.login.equalsIgnoreCase(me);
            name.setText(isMe ? u.login + "  (you)" : u.login);

            if (u.online) {
                paintDot(dot, getResources().getColor(R.color.status_ready_fg));
                status.setText("Online");
                status.setTextColor(getResources().getColor(R.color.status_ready_fg));
            } else {
                paintDot(dot, getResources().getColor(R.color.status_pending_fg));
                status.setText("Offline");
                status.setTextColor(getResources().getColor(R.color.status_pending_fg));
            }
            return row;
        }

        private void paintDot(TextView dot, int colorInt) {
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(colorInt);
            dot.setBackground(bg);
        }
    }
}