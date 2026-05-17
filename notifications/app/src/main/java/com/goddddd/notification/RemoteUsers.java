package com.goddddd.notification;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for Firebase Realtime Database.
 *
 * Structure:
 *   users/&lt;login&gt; = {
 *       createdAt, online, lastSelfTestAlertId,
 *       passHash, salt           // password storage (sha256(salt + password))
 *   }
 *   inbox/&lt;login&gt;/&lt;pushId&gt; = { from, text, ts, alertId }
 *   alerts/&lt;alertId&gt; = {
 *       from, text, ts, selfTest?,
 *       recipients: { login1: true, login2: true, ... },
 *       responses:  { login1: { ready: true, ts }, ... }
 *   }
 *
 * Retention rules:
 *  - Regular alerts older than 24h are removed when a new alert is sent.
 *  - Only one self-test alert per user is kept (previous one is removed).
 */
public class RemoteUsers {

    public static final String DATABASE_URL =
            "https://notifications-a3b71-default-rtdb.firebaseio.com";

    public static final String NODE_USERS = "users";
    public static final String NODE_INBOX = "inbox";
    public static final String NODE_ALERTS = "alerts";
    public static final String NODE_STATUSES = "statuses";
    public static final String NODE_BROADCAST_LOCK = "broadcastLock";
    /** Avatar payloads: base64 jpeg, ~5-15 KB each. Kept in a separate
     *  node from "users" so listing the user table stays cheap. */
    public static final String NODE_USER_AVATARS = "userAvatars";

    /** Alerts older than this are automatically removed. */
    public static final long ALERT_TTL_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * Status TTLs:
     *   READY     stays "ready"     for 1 hour after the user pressed it.
     *   NOT_READY stays "not_ready" for 5 minutes (Send skips them during
     *             that window, but their UI tile stays red within the
     *             READY_TTL window so the panel reads consistently).
     */
    public static final long READY_TTL_MS = 60L * 60 * 1000;
    public static final long NOT_READY_TTL_MS = 5L * 60 * 1000;
    /** One Send floods all clients with the same 60s response window. */
    public static final long BROADCAST_WINDOW_MS = 60L * 1000;

    private static FirebaseDatabase db() {
        return FirebaseDatabase.getInstance(DATABASE_URL);
    }

    public static DatabaseReference usersRef() {
        return db().getReference(NODE_USERS);
    }

    public static DatabaseReference inboxRef(String login) {
        return db().getReference(NODE_INBOX).child(login);
    }

    public static DatabaseReference alertsRef() {
        return db().getReference(NODE_ALERTS);
    }

    public static DatabaseReference statusesRef() {
        return db().getReference(NODE_STATUSES);
    }

    public static DatabaseReference broadcastLockRef() {
        return db().getReference(NODE_BROADCAST_LOCK);
    }

    public static DatabaseReference avatarsRef() {
        return db().getReference(NODE_USER_AVATARS);
    }

    // ---------- Profile (display name, password, avatar) ----------

    /** Pick a human-friendly label for a user snapshot:
     *  display name if set, otherwise the login itself. */
    public static String displayNameOrLogin(DataSnapshot userSnap) {
        if (userSnap == null) return null;
        String dn = userSnap.child("displayName").getValue(String.class);
        if (dn != null && !dn.trim().isEmpty()) return dn;
        return userSnap.getKey();
    }

    /** Update (or clear with null/empty) the display name. */
    public static void setDisplayName(String login, String displayName,
                                      SimpleCallback cb) {
        if (login == null || login.isEmpty()) {
            if (cb != null) cb.onError("No login");
            return;
        }
        Object value = (displayName == null || displayName.trim().isEmpty())
                ? null
                : displayName.trim();
        usersRef().child(login).child("displayName").setValue(value,
                (error, ref) -> {
                    if (cb == null) return;
                    if (error == null) cb.onSuccess();
                    else cb.onError(error.getMessage());
                });
    }

    /**
     * Change the user's password. Verifies the old one first; if it does
     * not match, calls onError("Wrong password"). On success rewrites
     * {@code salt} + {@code passHash}.
     */
    public static void changePassword(final String login,
                                      final String oldPassword,
                                      final String newPassword,
                                      final SimpleCallback cb) {
        if (login == null || login.isEmpty()
                || oldPassword == null || newPassword == null
                || newPassword.length() < 6) {
            if (cb != null) cb.onError("Bad arguments");
            return;
        }
        usersRef().child(login).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String salt = snap.child("salt").getValue(String.class);
                String hash = snap.child("passHash").getValue(String.class);
                if (salt == null || hash == null
                        || !PasswordHash.verify(salt, hash, oldPassword)) {
                    if (cb != null) cb.onError("Wrong password");
                    return;
                }
                String newSalt = PasswordHash.generateSalt();
                String newHash = PasswordHash.hash(newSalt, newPassword);
                Map<String, Object> upd = new HashMap<>();
                upd.put("salt", newSalt);
                upd.put("passHash", newHash);
                usersRef().child(login).updateChildren(upd, (error, ref) -> {
                    if (cb == null) return;
                    if (error == null) cb.onSuccess();
                    else cb.onError(error.getMessage());
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (cb != null) cb.onError(error.getMessage());
            }
        });
    }

    /**
     * Store a (small) avatar as a base64 data URI in
     * {@code userAvatars/<login>} and bump
     * {@code users/<login>/hasAvatar / avatarTs} so other clients know
     * to invalidate their cache.
     *
     * The caller is responsible for resizing/compressing the image so
     * the resulting base64 stays small (? ~30 KB is comfortable).
     */
    public static void setAvatar(final String login, final String dataUri,
                                 final SimpleCallback cb) {
        if (login == null || login.isEmpty() || dataUri == null) {
            if (cb != null) cb.onError("No login");
            return;
        }
        final long ts = System.currentTimeMillis();
        avatarsRef().child(login).setValue(dataUri, (error, ref) -> {
            if (error != null) {
                if (cb != null) cb.onError(error.getMessage());
                return;
            }
            Map<String, Object> upd = new HashMap<>();
            upd.put("hasAvatar", true);
            upd.put("avatarTs", ts);
            usersRef().child(login).updateChildren(upd, (error2, ref2) -> {
                if (cb == null) return;
                if (error2 == null) cb.onSuccess();
                else cb.onError(error2.getMessage());
            });
        });
    }

    public static void clearAvatar(final String login, final SimpleCallback cb) {
        if (login == null || login.isEmpty()) {
            if (cb != null) cb.onError("No login");
            return;
        }
        avatarsRef().child(login).removeValue((error, ref) -> {
            if (error != null) {
                if (cb != null) cb.onError(error.getMessage());
                return;
            }
            Map<String, Object> upd = new HashMap<>();
            upd.put("hasAvatar", null);
            upd.put("avatarTs", null);
            usersRef().child(login).updateChildren(upd, (error2, ref2) -> {
                if (cb == null) return;
                if (error2 == null) cb.onSuccess();
                else cb.onError(error2.getMessage());
            });
        });
    }

    // ---------- Authentication ----------

    /**
     * Atomically register a new user in Firebase: fails if the login is already taken.
     * Stores salt + passHash (no plaintext).
     */
    public static void registerRemote(final String rawLogin, final String password,
                                      final AuthCallback cb) {
        if (rawLogin == null || rawLogin.trim().isEmpty()
                || password == null || password.isEmpty()) {
            if (cb != null) cb.onError("Login and password are required");
            return;
        }
        final String login = rawLogin.trim();
        final String salt = PasswordHash.generateSalt();
        final String hash = PasswordHash.hash(salt, password);

        usersRef().child(login).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                if (data.getValue() != null) {
                    // Login already exists - abort.
                    return Transaction.abort();
                }
                Map<String, Object> u = new HashMap<>();
                u.put("createdAt", System.currentTimeMillis());
                u.put("salt", salt);
                u.put("passHash", hash);
                data.setValue(u);
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                                   DataSnapshot currentData) {
                if (error != null) {
                    if (cb != null) cb.onError(error.getMessage());
                } else if (!committed) {
                    if (cb != null) cb.onError("This login is already taken");
                } else {
                    if (cb != null) cb.onSuccess(login);
                }
            }
        });
    }

    /** Verify password against stored salt + hash in Firebase. */
    public static void loginRemote(final String rawLogin, final String password,
                                   final AuthCallback cb) {
        if (rawLogin == null || rawLogin.trim().isEmpty()
                || password == null || password.isEmpty()) {
            if (cb != null) cb.onError("Login and password are required");
            return;
        }
        final String login = rawLogin.trim();
        usersRef().child(login).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    if (cb != null) cb.onError("Wrong login or password");
                    return;
                }
                String salt = snapshot.child("salt").getValue(String.class);
                String passHash = snapshot.child("passHash").getValue(String.class);
                if (salt == null || passHash == null) {
                    // Legacy user record without credentials - cannot authenticate.
                    if (cb != null) cb.onError("Wrong login or password");
                    return;
                }
                if (PasswordHash.verify(salt, passHash, password)) {
                    if (cb != null) cb.onSuccess(login);
                } else {
                    if (cb != null) cb.onError("Wrong login or password");
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (cb != null) cb.onError(error.getMessage());
            }
        });
    }

    /**
     * Delete a user's data:
     *  - users/&lt;login&gt;
     *  - inbox/&lt;login&gt;
     *  - any alerts authored by this user
     */
    public static void deleteAccount(final String login, final SimpleCallback cb) {
        if (login == null || login.isEmpty()) {
            if (cb != null) cb.onError("No login");
            return;
        }
        // 0) Tear presence down BEFORE deleting anything. Otherwise the
        //    presence listener (attachPresence) and/or the server-side
        //    onDisconnect handler will happily re-create users/<login>
        //    with just an "online" field right after we delete it. That
        //    is exactly the leftover "online: false" record users were
        //    seeing in the database.
        try {
            detachPresence();
        } catch (Throwable ignored) {}
        try {
            // Explicit cancel - detachPresence already calls cancel() for
            // the path, but if presence was never attached on this login
            // (e.g. service crashed earlier) the handler may still exist
            // on the server, so cancel it here for good measure.
            usersRef().child(login).child("online").onDisconnect().cancel();
        } catch (Throwable ignored) {}

        // 1) Delete alerts where from == login
        alertsRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot s : snapshot.getChildren()) {
                    String from = s.child("from").getValue(String.class);
                    if (from != null && from.equalsIgnoreCase(login)) {
                        s.getRef().removeValue();
                    }
                }
                // 2) Inbox
                inboxRef(login).removeValue();
                // 2b) Persistent status + avatar payload.
                statusesRef().child(login).removeValue();
                avatarsRef().child(login).removeValue();
                // 3) The user record itself
                usersRef().child(login).removeValue((error, ref) -> {
                    // 4) Safety net: a stray write coming from a presence
                    //    listener or a delayed onDisconnect could resurrect
                    //    users/<login>/online. Re-delete the whole node
                    //    once more after a short delay to clean that up.
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> {
                                try {
                                    usersRef().child(login)
                                            .child("online")
                                            .onDisconnect().cancel();
                                } catch (Throwable ignored) {}
                                usersRef().child(login).removeValue();
                            }, 1500);

                    if (cb == null) return;
                    if (error == null) cb.onSuccess();
                    else cb.onError(error.getMessage());
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Even if we cannot enumerate alerts, still try to remove
                // everything user-owned.
                inboxRef(login).removeValue();
                statusesRef().child(login).removeValue();
                avatarsRef().child(login).removeValue();
                usersRef().child(login).removeValue();
                if (cb != null) cb.onError(error.getMessage());
            }
        });
    }

    // ---------- User registry / presence ----------

    /** Mark user as online/offline. Call from MainActivity lifecycle. */
    public static void setOnline(String login, boolean online) {
        if (login == null || login.isEmpty()) return;
        DatabaseReference ref = usersRef().child(login).child("online");
        if (online) {
            ref.onDisconnect().setValue(false);
            ref.setValue(true);
        } else {
            ref.onDisconnect().cancel();
            ref.setValue(false);
        }
    }

    // ---------- Presence (reconnect-aware) ----------
    //
    // Why this exists:
    //
    // setOnline(login, true) is fire-and-forget: it writes "online=true"
    // and registers an onDisconnect() that flips the value to "false"
    // when the socket dies on the Firebase side. That handles the
    // "user goes offline" case (network loss, app killed, VPN toggle,
    // etc), but NOTHING flips the value back to true once the socket
    // reconnects. Result: after any network change the user stays
    // "offline" until they manually re-open the app.
    //
    // Firebase provides a special read-only path ".info/connected"
    // that emits true/false every time the client's realtime socket
    // connects or disconnects. By listening to it we can:
    //   1) re-arm the onDisconnect() handler on every reconnect, and
    //   2) immediately re-write online=true.
    //
    // Always pair attachPresence() with detachPresence() on the same
    // login when the owning component is torn down (e.g. service
    // onDestroy, or logout).

    private static DatabaseReference sPresenceConnRef;
    private static ValueEventListener sPresenceListener;
    private static DatabaseReference sPresenceOnlineRef;
    private static String sPresenceLogin;

    /**
     * Start tracking presence for the given login. Safe to call multiple
     * times - if presence is already attached for the same login this is
     * a no-op, otherwise the previous binding is detached first.
     */
    public static synchronized void attachPresence(final String login) {
        if (login == null || login.isEmpty()) return;
        if (login.equals(sPresenceLogin) && sPresenceListener != null) {
            return; // already attached for this user
        }
        detachPresence();

        sPresenceLogin = login;
        sPresenceOnlineRef = usersRef().child(login).child("online");
        sPresenceConnRef = db().getReference(".info/connected");

        sPresenceListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (connected == null || !connected) {
                    // We're offline from the SDK's point of view; the
                    // onDisconnect() registered on the previous "connected"
                    // tick (if any) has already been or will be executed
                    // by the server. Nothing to do here.
                    return;
                }
                final DatabaseReference ref = sPresenceOnlineRef;
                if (ref == null) return;

                // Step 1: arm the server-side disconnect handler FIRST.
                // If we wrote "true" first and the connection dropped right
                // after, no onDisconnect would be in place and the value
                // would be stuck at "true".
                ref.onDisconnect().setValue(false)
                        .addOnCompleteListener(t -> ref.setValue(true));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Permissions / shutdown - just give up; will be re-armed
                // on the next attachPresence().
            }
        };
        sPresenceConnRef.addValueEventListener(sPresenceListener);
    }

    /**
     * Stop tracking presence. Best-effort marks the user offline immediately
     * and cancels any pending onDisconnect.
     */
    public static synchronized void detachPresence() {
        if (sPresenceConnRef != null && sPresenceListener != null) {
            try {
                sPresenceConnRef.removeEventListener(sPresenceListener);
            } catch (Throwable ignored) {}
        }
        if (sPresenceOnlineRef != null) {
            try {
                sPresenceOnlineRef.onDisconnect().cancel();
                sPresenceOnlineRef.setValue(false);
            } catch (Throwable ignored) {}
        }
        sPresenceConnRef = null;
        sPresenceListener = null;
        sPresenceOnlineRef = null;
        sPresenceLogin = null;
    }

    /** Load all registered user logins. */
    public static void loadUserList(final UserListCallback cb) {
        usersRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<String> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    list.add(child.getKey());
                }
                cb.onResult(list);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    /** Load users with their online status, display name and avatar meta. */
    public static void loadUserListWithStatus(final UserListWithStatusCallback cb) {
        usersRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<UserInfo> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    UserInfo u = new UserInfo();
                    u.login = child.getKey();
                    Boolean online = child.child("online").getValue(Boolean.class);
                    u.online = online != null && online;
                    u.displayName = child.child("displayName").getValue(String.class);
                    Boolean has = child.child("hasAvatar").getValue(Boolean.class);
                    u.hasAvatar = Boolean.TRUE.equals(has);
                    Long ts = child.child("avatarTs").getValue(Long.class);
                    u.avatarTs = ts != null ? ts : 0L;
                    list.add(u);
                }
                cb.onResult(list);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    // ---------- Statuses (persistent ready/not_ready model) ----------
    //
    // Each user has at most one record in statuses/<login>:
    //     { state: "ready"|"not_ready", ts: <ms> }
    // Absence of a record (or expired ts) = pending = will receive the
    // next broadcast.
    //
    // TTLs are evaluated client-side at the moment we read the table.
    // We don't run a server cleanup - whoever reads the table just
    // ignores expired records and the next "set" overwrites them anyway.

    /**
     * Mark the given user as ready / not_ready, with the timestamp set
     * to "now". Pass {@code null} to clear (i.e. go back to pending).
     */
    public static void setStatus(String login, Boolean ready) {
        if (login == null || login.isEmpty()) return;
        DatabaseReference ref = statusesRef().child(login);
        if (ready == null) {
            ref.removeValue();
            return;
        }
        Map<String, Object> v = new HashMap<>();
        v.put("state", ready ? "ready" : "not_ready");
        v.put("ts", System.currentTimeMillis());
        ref.setValue(v);
    }

    public static void clearStatus(String login) {
        setStatus(login, null);
    }

    /** Return true if the status record at the given ts is still active. */
    public static boolean isStatusActive(String state, long ts, long now) {
        if (state == null) return false;
        if ("ready".equalsIgnoreCase(state)) return (now - ts) < READY_TTL_MS;
        if ("not_ready".equalsIgnoreCase(state)) return (now - ts) < NOT_READY_TTL_MS;
        return false;
    }

    // ---------- Broadcast lock (global 60s response window) ----------

    /**
     * Try to acquire the global broadcast lock. Atomic - if someone else
     * is currently inside their 60s window, this aborts.
     *
     * onSuccess fires once the lock is taken; onError fires if either the
     * lock is busy or the transaction failed.
     */
    public static void tryAcquireBroadcastLock(final String fromLogin,
                                               final SimpleCallback cb) {
        broadcastLockRef().runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                long now = System.currentTimeMillis();
                Object cur = data.getValue();
                if (cur instanceof Map) {
                    Object active = ((Map<?, ?>) cur).get("active");
                    Object tsObj = ((Map<?, ?>) cur).get("ts");
                    long ts = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0;
                    boolean isActive = Boolean.TRUE.equals(active)
                            && (now - ts) < BROADCAST_WINDOW_MS;
                    if (isActive) return Transaction.abort();
                }
                Map<String, Object> v = new HashMap<>();
                v.put("active", true);
                v.put("from", fromLogin);
                v.put("ts", now);
                data.setValue(v);
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                                   DataSnapshot currentData) {
                if (cb == null) return;
                if (error != null) cb.onError(error.getMessage());
                else if (!committed) cb.onError("Broadcast is already in progress");
                else cb.onSuccess();
            }
        });
    }

    /**
     * Attach the freshly-created alert id to the existing broadcast lock so
     * other clients know which alert this window corresponds to. Used by
     * {@link #broadcastMessage} right after the alert is written.
     */
    public static void setBroadcastLockAlertId(String alertId) {
        if (alertId == null) return;
        broadcastLockRef().child("alertId").setValue(alertId);
    }

    /** Best-effort release of the lock (e.g. cleanup right after a send). */
    public static void releaseBroadcastLock() {
        broadcastLockRef().removeValue();
    }

    // ---------- Alerts ----------

    /**
     * One-to-one send. Self-test still uses this (fromLogin == toLogin)
     * and is the only legitimate caller now that "send to a specific
     * user" was removed from the UI.
     */
    public static void sendMessage(final String toLogin, final String fromLogin,
                                   final String text, final SendCallback cb) {
        if (toLogin == null || toLogin.isEmpty()) {
            if (cb != null) cb.onError("No recipient");
            return;
        }
        List<String> recipients = new ArrayList<>();
        recipients.add(toLogin);
        createAlertAndDispatch(fromLogin, text, recipients, cb);
    }

    /**
     * Broadcast a "are you ready?" alert to every user who is currently
     * NOT ready and NOT (recently) not_ready. The sender is included if
     * they themselves don't have a fresh status. This is the only
     * user-initiated send-path in the redesigned UI.
     *
     * The global broadcast lock is acquired first; if it can't be taken
     * because another user is in their 60s window, we abort with an error
     * and the caller surfaces it to the user.
     */
    public static void broadcastMessage(final String fromLogin, final String text,
                                        final SendCallback cb) {
        tryAcquireBroadcastLock(fromLogin, new SimpleCallback() {
            @Override
            public void onSuccess() {
                // Got the lock - collect users + filter by status snapshot.
                loadUserList(new UserListCallback() {
                    @Override
                    public void onResult(List<String> allLogins) {
                        statusesRef().addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot snap) {
                                long now = System.currentTimeMillis();
                                List<String> recipients = new ArrayList<>();
                                for (String l : allLogins) {
                                    if (l == null) continue;
                                    String state = snap.child(l).child("state").getValue(String.class);
                                    Long ts = snap.child(l).child("ts").getValue(Long.class);
                                    if (ts != null && isStatusActive(state, ts, now)) continue;
                                    recipients.add(l);
                                }
                                if (recipients.isEmpty()) {
                                    releaseBroadcastLock();
                                    if (cb != null) cb.onError("No recipients");
                                    return;
                                }
                                createAlertAndDispatch(fromLogin, text, recipients,
                                        new SendCallback() {
                                            @Override
                                            public void onSuccess(String alertId) {
                                                // Bind alertId to the lock so listeners can
                                                // tell which alert is currently in flight.
                                                setBroadcastLockAlertId(alertId);
                                                // Self-healing: even if the early-release
                                                // logic never fires (e.g. nobody listens),
                                                // the lock will time out by itself.
                                                new android.os.Handler(android.os.Looper.getMainLooper())
                                                        .postDelayed(
                                                                RemoteUsers::releaseBroadcastLock,
                                                                BROADCAST_WINDOW_MS + 500);
                                                if (cb != null) cb.onSuccess(alertId);
                                            }
                                            @Override
                                            public void onError(String message) {
                                                releaseBroadcastLock();
                                                if (cb != null) cb.onError(message);
                                            }
                                        });
                            }
                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {
                                releaseBroadcastLock();
                                if (cb != null) cb.onError(error.getMessage());
                            }
                        });
                    }
                    @Override
                    public void onError(String message) {
                        releaseBroadcastLock();
                        if (cb != null) cb.onError(message);
                    }
                });
            }
            @Override
            public void onError(String message) {
                if (cb != null) cb.onError(message);
            }
        });
    }

    /**
     * Create an alerts/&lt;id&gt; record and push into each recipient's inbox.
     */
    private static void createAlertAndDispatch(final String fromLogin, final String text,
                                               final List<String> recipients,
                                               final SendCallback cb) {
        final boolean isSelfTest = recipients.size() == 1
                && fromLogin != null
                && fromLogin.equalsIgnoreCase(recipients.get(0));

        final Runnable thenCreate = () -> {
            DatabaseReference newAlertRef = alertsRef().push();
            final String alertId = newAlertRef.getKey();
            long ts = System.currentTimeMillis();

            Map<String, Object> recMap = new HashMap<>();
            for (String r : recipients) recMap.put(r, true);

            Map<String, Object> alert = new HashMap<>();
            alert.put("from", fromLogin);
            alert.put("text", text);
            alert.put("ts", ts);
            alert.put("recipients", recMap);
            if (isSelfTest) alert.put("selfTest", true);

            newAlertRef.setValue(alert, (error, ref) -> {
                if (error != null) {
                    if (cb != null) cb.onError(error.getMessage());
                    return;
                }
                for (String r : recipients) {
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("from", fromLogin);
                    msg.put("text", text);
                    msg.put("ts", System.currentTimeMillis());
                    msg.put("alertId", alertId);
                    inboxRef(r).push().setValue(msg);
                }
                if (isSelfTest) {
                    usersRef().child(fromLogin)
                            .child("lastSelfTestAlertId")
                            .setValue(alertId);
                }
                if (cb != null) cb.onSuccess(alertId);
            });
        };

        if (isSelfTest) {
            removePreviousSelfTest(fromLogin, thenCreate);
        } else {
            cleanupOldAlerts(fromLogin, thenCreate);
        }
    }

    private static void removePreviousSelfTest(final String fromLogin, final Runnable then) {
        usersRef().child(fromLogin).child("lastSelfTestAlertId")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String oldId = snapshot.getValue(String.class);
                        if (oldId != null && !oldId.isEmpty()) {
                            alertsRef().child(oldId).removeValue();
                        }
                        if (then != null) then.run();
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (then != null) then.run();
                    }
                });
    }

    private static void cleanupOldAlerts(final String fromLogin, final Runnable then) {
        if (fromLogin == null) {
            if (then != null) then.run();
            return;
        }
        final long cutoff = System.currentTimeMillis() - ALERT_TTL_MILLIS;
        alertsRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot s : snapshot.getChildren()) {
                    String from = s.child("from").getValue(String.class);
                    Long ts = s.child("ts").getValue(Long.class);
                    Boolean selfTest = s.child("selfTest").getValue(Boolean.class);
                    if (selfTest != null && selfTest) continue;
                    if (from != null && from.equalsIgnoreCase(fromLogin)
                            && ts != null && ts < cutoff) {
                        s.getRef().removeValue();
                    }
                }
                if (then != null) then.run();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (then != null) then.run();
            }
        });
    }

    /** Record recipient's answer to a particular alert. */
    public static void postResponse(String alertId, String myLogin, boolean ready) {
        if (alertId == null || alertId.isEmpty() || myLogin == null) return;
        Map<String, Object> resp = new HashMap<>();
        resp.put("ready", ready);
        resp.put("ts", System.currentTimeMillis());
        alertsRef().child(alertId).child("responses").child(myLogin).setValue(resp);
    }

    /**
     * Load the shared alerts history (last 24 hours), newest first.
     *
     * History is shared across all users so everyone can see who sent
     * what, EXCEPT self-test alerts: those belong to a single user only
     * and remain visible only to their author. Pass the current user's
     * login as {@code myLogin} to apply that filter.
     */
    public static void loadMyAlerts(final String myLogin, final AlertsListCallback cb) {
        final long cutoff = System.currentTimeMillis() - ALERT_TTL_MILLIS;
        alertsRef().addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<AlertSummary> list = new ArrayList<>();
                for (DataSnapshot s : snapshot.getChildren()) {
                    String from = s.child("from").getValue(String.class);
                    Long ts = s.child("ts").getValue(Long.class);
                    long tsVal = ts != null ? ts : 0;
                    Boolean selfTest = s.child("selfTest").getValue(Boolean.class);
                    boolean isSelf = selfTest != null && selfTest;

                    // Self-test alerts: only the author should see them.
                    if (isSelf) {
                        if (myLogin == null || from == null
                                || !from.equalsIgnoreCase(myLogin)) {
                            continue;
                        }
                    } else {
                        // Regular alerts: visible to everyone, but only
                        // within the 24h retention window.
                        if (tsVal < cutoff) continue;
                    }

                    AlertSummary a = new AlertSummary();
                    a.id = s.getKey();
                    a.from = from;
                    a.text = s.child("text").getValue(String.class);
                    a.ts = tsVal;
                    a.selfTest = isSelf;
                    a.recipientCount = (int) s.child("recipients").getChildrenCount();
                    a.responseCount = (int) s.child("responses").getChildrenCount();
                    list.add(a);
                }
                java.util.Collections.sort(list, (x, y) -> Long.compare(y.ts, x.ts));
                cb.onResult(list);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    /** Load full detail of a single alert (recipients + responses). */
    public static void loadAlertDetail(String alertId, final AlertDetailCallback cb) {
        alertsRef().child(alertId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                AlertDetail d = new AlertDetail();
                d.id = snapshot.getKey();
                d.from = snapshot.child("from").getValue(String.class);
                d.text = snapshot.child("text").getValue(String.class);
                Long ts = snapshot.child("ts").getValue(Long.class);
                d.ts = ts != null ? ts : 0;
                Boolean selfTest = snapshot.child("selfTest").getValue(Boolean.class);
                d.selfTest = selfTest != null && selfTest;
                d.recipients = new ArrayList<>();
                for (DataSnapshot r : snapshot.child("recipients").getChildren()) {
                    d.recipients.add(r.getKey());
                }
                d.responses = new HashMap<>();
                for (DataSnapshot r : snapshot.child("responses").getChildren()) {
                    Boolean ready = r.child("ready").getValue(Boolean.class);
                    d.responses.put(r.getKey(), ready != null && ready);
                }
                cb.onResult(d);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    // ---------- POJOs and callbacks ----------

    public static class UserInfo {
        public String login;
        public boolean online;
        /** Optional display name; falls back to login when null/empty. */
        public String displayName;
        /** Whether the user has uploaded an avatar. */
        public boolean hasAvatar;
        /** Last avatar update timestamp - used as a cache key. */
        public long avatarTs;
    }

    public static class AlertSummary {
        public String id;
        public String from;
        public String text;
        public long ts;
        public int recipientCount;
        public int responseCount;
        public boolean selfTest;
    }

    public static class AlertDetail {
        public String id;
        public String from;
        public String text;
        public long ts;
        public boolean selfTest;
        public List<String> recipients;
        public Map<String, Boolean> responses;
    }

    public interface UserListCallback {
        void onResult(List<String> logins);
        void onError(String message);
    }

    public interface UserListWithStatusCallback {
        void onResult(List<UserInfo> users);
        void onError(String message);
    }

    public interface SendCallback {
        void onSuccess(String alertId);
        void onError(String message);
    }

    public interface AlertsListCallback {
        void onResult(List<AlertSummary> alerts);
        void onError(String message);
    }

    public interface AlertDetailCallback {
        void onResult(AlertDetail detail);
        void onError(String message);
    }

    public interface AuthCallback {
        void onSuccess(String login);
        void onError(String message);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(String message);
    }
}