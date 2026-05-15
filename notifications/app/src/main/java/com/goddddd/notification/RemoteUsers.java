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

    /** Alerts older than this are automatically removed. */
    public static final long ALERT_TTL_MILLIS = 24L * 60 * 60 * 1000;

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
                // 3) The user record itself
                usersRef().child(login).removeValue((error, ref) -> {
                    if (cb == null) return;
                    if (error == null) cb.onSuccess();
                    else cb.onError(error.getMessage());
                });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Even if we cannot enumerate alerts, still try to remove user/inbox.
                inboxRef(login).removeValue();
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

    /** Load users with their online status. */
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

    // ---------- Alerts ----------

    /** Send to a single recipient. */
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

    /** Broadcast to all registered users except the sender. */
    public static void broadcastMessage(final String fromLogin, final String text,
                                        final SendCallback cb) {
        loadUserList(new UserListCallback() {
            @Override
            public void onResult(List<String> logins) {
                List<String> recipients = new ArrayList<>();
                for (String l : logins) {
                    if (l != null && !l.equalsIgnoreCase(fromLogin)) recipients.add(l);
                }
                if (recipients.isEmpty()) {
                    if (cb != null) cb.onError("No recipients");
                    return;
                }
                createAlertAndDispatch(fromLogin, text, recipients, cb);
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

    /** Load alerts created by a given user during the last 24 hours, newest first. */
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
                    if (from != null && from.equalsIgnoreCase(myLogin)
                            && (isSelf || tsVal >= cutoff)) {
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