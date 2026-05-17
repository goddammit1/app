package com.goddddd.notification;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory avatar cache.
 *
 * Avatars are stored in {@code userAvatars/<login>} as a small base64
 * JPEG (~15 KB). To avoid pulling the table for every paint of the
 * status panel we keep a per-login {@code avatarTs} on the user record
 * and read the actual bytes lazily - and only once per (login, avatarTs)
 * pair, holding the resulting {@link Bitmap} in an {@link LruCache}.
 *
 * The cache key is {@code login + "@" + avatarTs}. A new upload bumps
 * {@code avatarTs}, so any stale entry is dropped naturally.
 *
 * Public API:
 *   - {@link #request(String, long, Listener)} - async fetch + decode.
 *   - {@link #get(String, long)} - synchronous cache lookup, may return null.
 *   - {@link #invalidate(String)} - drop a specific user from the cache.
 *
 * Thread-safety: callers can call from any thread; listener invocations
 * happen on the main looper.
 */
public final class AvatarCache {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(32);
    private static final Set<String> PENDING = new HashSet<>();
    /** Most recent listeners keyed by cache key (so a second request
     *  while the first is in flight does not refetch). */
    private static final Map<String, java.util.List<Listener>> PENDING_LISTENERS =
            new HashMap<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AvatarCache() {}

    public interface Listener {
        /** Called on main thread once an avatar has been loaded (or has failed). */
        void onAvatar(@Nullable Bitmap bm);
    }

    /** Build the cache key for a (login, ts) tuple. */
    private static String keyFor(String login, long ts) {
        return login + "@" + ts;
    }

    @Nullable
    public static Bitmap get(String login, long ts) {
        if (login == null) return null;
        return CACHE.get(keyFor(login, ts));
    }

    /** Drop everything we have cached for this login (any ts). */
    public static synchronized void invalidate(String login) {
        if (login == null) return;
        String prefix = login + "@";
        // LruCache has no iteration API; build snapshot.
        Map<String, Bitmap> snap = CACHE.snapshot();
        for (String k : snap.keySet()) {
            if (k.startsWith(prefix)) CACHE.remove(k);
        }
    }

    /**
     * Request the avatar bytes for the given (login, ts). If a fresh
     * cached bitmap exists it is delivered synchronously on the main
     * looper, otherwise we hit RTDB once and cache the result.
     *
     * Multiple concurrent calls for the same key are coalesced into a
     * single fetch.
     */
    public static void request(final String login, final long ts,
                               @NonNull final Listener listener) {
        if (login == null) {
            deliver(listener, null);
            return;
        }
        final String k = keyFor(login, ts);
        Bitmap hit = CACHE.get(k);
        if (hit != null) {
            deliver(listener, hit);
            return;
        }

        boolean alreadyFetching;
        synchronized (PENDING) {
            alreadyFetching = PENDING.contains(k);
            PENDING.add(k);
            java.util.List<Listener> list = PENDING_LISTENERS.get(k);
            if (list == null) {
                list = new java.util.ArrayList<>();
                PENDING_LISTENERS.put(k, list);
            }
            list.add(listener);
        }
        if (alreadyFetching) return;

        RemoteUsers.avatarsRef().child(login)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Bitmap decoded = decode(snapshot.getValue(String.class));
                        publish(k, decoded);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        publish(k, null);
                    }
                });
    }

    @Nullable
    private static Bitmap decode(@Nullable String dataUri) {
        if (dataUri == null || dataUri.isEmpty()) return null;
        // Accept both "data:image/jpeg;base64,...." and a plain base64 string.
        String payload = dataUri;
        int comma = dataUri.indexOf(',');
        if (dataUri.startsWith("data:") && comma > 0) {
            payload = dataUri.substring(comma + 1);
        }
        try {
            byte[] bytes = Base64.decode(payload, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void publish(final String key, @Nullable final Bitmap bm) {
        if (bm != null) CACHE.put(key, bm);

        java.util.List<Listener> waiting;
        synchronized (PENDING) {
            PENDING.remove(key);
            waiting = PENDING_LISTENERS.remove(key);
        }
        if (waiting == null) return;
        for (final Listener l : waiting) deliver(l, bm);
    }

    @MainThread
    private static void deliver(final Listener l, @Nullable final Bitmap bm) {
        MAIN.post(() -> {
            try { l.onAvatar(bm); } catch (Throwable ignored) {}
        });
    }
}