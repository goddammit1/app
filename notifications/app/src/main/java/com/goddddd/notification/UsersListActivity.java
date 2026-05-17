package com.goddddd.notification;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick-recipient / Online-users screen.
 *
 * Each row is: [avatar (with online dot)] [name + optional "Playing X"] [Online/Offline].
 * When the row's user has no avatar, a generic silhouette is rendered;
 * the online dot is anchored to the avatar's bottom-right corner.
 * The "Playing X" sub-line under the display name shows up only while
 * the user is currently in the tracked game (see {@link GameWatcher}).
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
                hintText.setText("Green dot = online. Grey = offline.");
            }
        }

        adapter = new UserAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            RemoteUsers.UserInfo u = users.get(position);
            if (viewOnly) return;
            askMessageAndSend(u.login);
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
                    if (!viewOnly && u.login.equalsIgnoreCase(me)) continue;
                    users.add(u);
                }
                java.util.Collections.sort(users, (a, b) -> {
                    if (a.online != b.online) return a.online ? -1 : 1;
                    String an = displayLabel(a);
                    String bn = displayLabel(b);
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

    private static String displayLabel(RemoteUsers.UserInfo u) {
        if (u == null) return "";
        if (u.displayName != null && !u.displayName.trim().isEmpty()) {
            return u.displayName.trim();
        }
        return u.login != null ? u.login : "";
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

    // ---------- Adapter ----------

    private class UserAdapter extends BaseAdapter {
        @Override public int getCount() { return users.size(); }
        @Override public Object getItem(int position) { return users.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RemoteUsers.UserInfo u = users.get(position);

            ViewHolder h;
            if (convertView != null && convertView.getTag() instanceof ViewHolder) {
                h = (ViewHolder) convertView.getTag();
            } else {
                convertView = buildRow();
                h = (ViewHolder) convertView.getTag();
            }

            String label = displayLabel(u);
            String me = session.getLogin();
            boolean isMe = u.login != null && u.login.equalsIgnoreCase(me);
            h.name.setText(isMe ? label + "  (you)" : label);

            if (u.online) {
                h.onlineDot.setVisibility(View.VISIBLE);
                h.status.setText("Online");
                h.status.setTextColor(getResources().getColor(R.color.status_ready_fg));
            } else {
                h.onlineDot.setVisibility(View.GONE);
                h.status.setText("Offline");
                h.status.setTextColor(getResources().getColor(R.color.status_pending_fg));
            }

            // Discord-style sub-line under the user's name.
            //   "Playing Mobile Legends"           - game is on screen now
            //   "Mobile Legends in background"     - process alive, user
            //                                        is doing something else
            // We trust the flag from Firebase only while its server
            // timestamp is fresh. TTL is per-state (playing has a much
            // shorter window than minimized) and lives in GameWatcher
            // so writer and reader can never drift out of sync.
            long now = System.currentTimeMillis();
            String gs = u.gameState;
            long age = (u.gameTs > 0) ? (now - u.gameTs) : Long.MAX_VALUE;
            String gameName = getString(R.string.tracked_game_name);
            if (GameWatcher.STATE_PLAYING.equals(gs)
                    && age < GameWatcher.PLAYING_TTL_MS) {
                h.playing.setText(getString(R.string.users_list_playing, gameName));
                // Normal secondary text colour - "active in game right now".
                h.playing.setTextColor(getResources().getColor(R.color.text_secondary));
                h.playing.setAlpha(1f);
                h.playing.setVisibility(View.VISIBLE);
            } else if (GameWatcher.STATE_MINIMIZED.equals(gs)
                    && age < GameWatcher.MINIMIZED_TTL_MS) {
                h.playing.setText(getString(R.string.users_list_minimized, gameName));
                // Dimmer - "process alive but not actually playing".
                // Same colour, lower alpha keeps the design consistent
                // and clearly less prominent than the live "playing" state.
                h.playing.setTextColor(getResources().getColor(R.color.text_secondary));
                h.playing.setAlpha(0.55f);
                h.playing.setVisibility(View.VISIBLE);
            } else {
                h.playing.setVisibility(View.GONE);
            }

            // Avatar: placeholder first, then async load if the user has one.
            final String login = u.login;
            h.avatarPlaceholder.setVisibility(View.VISIBLE);
            h.avatarImage.setImageDrawable(null);
            h.avatarImage.setVisibility(View.GONE);
            h.avatarImage.setTag(login);

            if (u.hasAvatar && u.avatarTs > 0 && login != null) {
                AvatarCache.request(login, u.avatarTs, bm -> {
                    if (!login.equals(h.avatarImage.getTag())) return;
                    if (bm != null) {
                        h.avatarImage.setImageBitmap(bm);
                        h.avatarImage.setVisibility(View.VISIBLE);
                        h.avatarPlaceholder.setVisibility(View.GONE);
                    }
                });
            }

            return convertView;
        }

        /** Builds the row view tree once and stashes a ViewHolder on it. */
        private View buildRow() {
            float d = getResources().getDisplayMetrics().density;
            int pad = (int) (16 * d);
            int gap = (int) (12 * d);
            int avatarSize = (int) (40 * d);
            int dotSize = (int) (12 * d);

            LinearLayout row = new LinearLayout(UsersListActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            // The online dot deliberately overflows the avatar circle
            // (Discord-style). Both the row and the avatar container must
            // allow children to draw outside their own bounds, otherwise
            // the dot gets clipped at the avatar's right/bottom edge.
            row.setClipChildren(false);
            row.setClipToPadding(false);

            // Avatar container (circle background + image + placeholder + online dot).
            //
            // Important: the container itself is NOT clipped. If we clipped
            // the FrameLayout (clipToOutline=true), every child - including
            // the online status dot - would be cut off at the avatar's
            // rounded edge, and the dot couldn't "stick out" of the circle
            // Discord-style. Instead we clip just the inner ImageView to a
            // circle, so the bitmap stays round while the dot is free to
            // overflow.
            FrameLayout avatarBox = new FrameLayout(UsersListActivity.this);
            LinearLayout.LayoutParams boxLp =
                    new LinearLayout.LayoutParams(avatarSize, avatarSize);
            boxLp.rightMargin = gap;
            avatarBox.setLayoutParams(boxLp);
            avatarBox.setBackground(ContextCompat.getDrawable(
                    UsersListActivity.this, R.drawable.bg_avatar_small));
            // Make sure children that draw outside the box (the online dot)
            // are not clipped by the FrameLayout's own bounds either.
            avatarBox.setClipChildren(false);
            avatarBox.setClipToPadding(false);

            ImageView avatarImage = new ImageView(UsersListActivity.this);
            avatarImage.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            avatarImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarImage.setVisibility(View.GONE);
            // Circular clip on the ImageView only - the bitmap stays round
            // without affecting siblings.
            avatarImage.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            avatarImage.setClipToOutline(true);
            avatarBox.addView(avatarImage);

            ImageView avatarPlaceholder = new ImageView(UsersListActivity.this);
            int phSize = (int) (24 * d);
            FrameLayout.LayoutParams phLp =
                    new FrameLayout.LayoutParams(phSize, phSize);
            phLp.gravity = Gravity.CENTER;
            avatarPlaceholder.setLayoutParams(phLp);
            avatarPlaceholder.setImageResource(R.drawable.ic_person_placeholder);
            avatarBox.addView(avatarPlaceholder);

            // Online dot anchored to the bottom-right of the avatar.
            // It is allowed to overflow the avatar circle (Discord-style),
            // because:
            //   * avatarBox no longer clips its children;
            //   * only the inner ImageView is clipped to a circle.
            // No negative or positive margin is applied - the dot sits
            // exactly at the bottom-right corner of the box, with roughly
            // half of it spilling outside the circle.
            View onlineDot = new View(UsersListActivity.this);
            FrameLayout.LayoutParams dotLp =
                    new FrameLayout.LayoutParams(dotSize, dotSize);
            dotLp.gravity = Gravity.BOTTOM | Gravity.END;
            onlineDot.setLayoutParams(dotLp);
            onlineDot.setBackground(ContextCompat.getDrawable(
                    UsersListActivity.this, R.drawable.bg_online_dot));
            avatarBox.addView(onlineDot);

            row.addView(avatarBox);

            // Name + "Playing X" stacked vertically, taking the
            // remaining horizontal space. The "Playing X" line is
            // Discord-inspired: small secondary text under the
            // primary name, only visible when the user is currently
            // in the tracked game.
            LinearLayout nameBlock = new LinearLayout(UsersListActivity.this);
            nameBlock.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams nameBlockLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameBlock.setLayoutParams(nameBlockLp);

            TextView name = new TextView(UsersListActivity.this);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            name.setTextSize(16);
            name.setTextColor(getResources().getColor(R.color.dark_text_primary));
            nameBlock.addView(name);

            TextView playing = new TextView(UsersListActivity.this);
            playing.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            playing.setTextSize(11);
            playing.setTextColor(getResources().getColor(R.color.text_secondary));
            playing.setVisibility(View.GONE);
            nameBlock.addView(playing);

            row.addView(nameBlock);

            // Online / Offline label on the right.
            TextView status = new TextView(UsersListActivity.this);
            status.setTextSize(12);
            row.addView(status);

            ViewHolder h = new ViewHolder();
            h.avatarImage = avatarImage;
            h.avatarPlaceholder = avatarPlaceholder;
            h.onlineDot = onlineDot;
            h.name = name;
            h.playing = playing;
            h.status = status;
            row.setTag(h);
            return row;
        }
    }

    private static class ViewHolder {
        ImageView avatarImage;
        ImageView avatarPlaceholder;
        View onlineDot;
        TextView name;
        /** Optional sub-line under the name: "Playing Mobile Legends".
         *  GONE when the user is not in the tracked game. */
        TextView playing;
        TextView status;
    }
}