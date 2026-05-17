package com.goddddd.notification;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update via GitHub Releases.
 *
 * checkAsync():
 *   1) GET https://api.github.com/repos/OWNER/REPO/releases/latest
 *   2) Compare tag_name vs current versionName.
 *   3) If newer - show dialog. Tap Update -> download .apk asset to cache,
 *      open the system installer via FileProvider + ACTION_VIEW.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // Change these if you fork the repo.
    private static final String REPO_OWNER = "goddammit1";
    private static final String REPO_NAME = "app";

    private static final String PREFS = "update_prefs";
    private static final String KEY_SKIPPED = "skipped_version";
    private static final String KEY_LAST_CHECK = "last_check_ts";
    private static final long MIN_CHECK_INTERVAL_MS = 60_000L;

    private UpdateChecker() {}

    public static void checkAsync(final Activity activity) {
        checkInternal(activity, /*force=*/false);
    }

    /**
     * Manually triggered update check (e.g. tapped "Updates" tile in the
     * Settings sheet). Unlike {@link #checkAsync(Activity)} this:
     *   * ignores the 1-minute rate limit,
     *   * ignores the user's "skip this version" choice (since the
     *     user explicitly asked),
     *   * surfaces feedback even when there is nothing to do, via the
     *     Toast {@code R.string.settings_updates_up_to_date}.
     *
     * Network and JSON parsing still happen on a background thread; UI
     * is touched only via the main looper.
     */
    public static void forceCheckAsync(final Activity activity) {
        checkInternal(activity, /*force=*/true);
    }

    private static void checkInternal(final Activity activity, final boolean force) {
        if (activity == null) return;
        final Context appCtx = activity.getApplicationContext();
        final SharedPreferences prefs =
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0);
        if (!force && now - last < MIN_CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

        if (force) {
            Toast.makeText(activity, "Checking for updates...",
                    Toast.LENGTH_SHORT).show();
        }

        new Thread(() -> {
            try {
                ReleaseInfo info = fetchLatest();
                String currentVer = currentVersionName(appCtx);

                boolean hasUpdate = info != null
                        && info.tagName != null
                        && info.apkUrl != null
                        && isNewer(info.tagName, currentVer);

                // "Skip this version" only suppresses the silent (auto)
                // check. If the user explicitly tapped "Updates", we
                // honour their request and show the dialog anyway.
                if (hasUpdate && !force) {
                    String skipped = prefs.getString(KEY_SKIPPED, null);
                    if (info.tagName.equalsIgnoreCase(skipped)) hasUpdate = false;
                }

                final boolean willPrompt = hasUpdate;
                final ReleaseInfo finalInfo = info;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (willPrompt) {
                        showDialog(activity, finalInfo);
                    } else if (force) {
                        // Explicit check, no newer version available:
                        // tell the user so they know the check actually
                        // ran (silent check would just go quiet here).
                        Toast.makeText(activity,
                                R.string.settings_updates_up_to_date,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "check failed", t);
                if (force) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(activity,
                                    "Update check failed: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private static void showDialog(Activity activity, ReleaseInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String msg = "A new version " + info.tagName + " is available.\n\n"
                + (info.body != null && !info.body.isEmpty()
                        ? info.body + "\n\n"
                        : "")
                + "Update now?";
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(msg)
                .setPositiveButton("Update", (d, w) -> startDownload(activity, info))
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version", (d, w) ->
                        activity.getApplicationContext()
                                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putString(KEY_SKIPPED, info.tagName).apply())
                .show();
    }

    private static void startDownload(final Activity activity, final ReleaseInfo info) {
        Toast.makeText(activity, "Downloading update...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File outDir = new File(activity.getCacheDir(), "updates");
                if (!outDir.exists()) outDir.mkdirs();
                final File outFile = new File(outDir, "app-update.apk");
                if (outFile.exists()) outFile.delete();

                HttpURLConnection c = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(60_000);
                c.setRequestProperty("User-Agent", "NotificationsApp-Updater");
                try (InputStream in = c.getInputStream();
                     OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                new Handler(Looper.getMainLooper()).post(() -> installApk(activity, outFile));
            } catch (Throwable t) {
                Log.e(TAG, "download failed", t);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(activity,
                                "Update download failed: " + t.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void installApk(Activity activity, File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean allowed = activity.getPackageManager().canRequestPackageInstalls();
                if (!allowed) {
                    Toast.makeText(activity,
                            "Please allow installing apps for this app, then try again.",
                            Toast.LENGTH_LONG).show();
                    Intent settings = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(settings);
                    return;
                }
            }
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            Toast.makeText(activity,
                    "Install failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static ReleaseInfo fetchLatest() {
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.github.com/repos/"
                    + REPO_OWNER + "/" + REPO_NAME + "/releases/latest");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "NotificationsApp-Updater");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "GitHub status " + c.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            ReleaseInfo info = new ReleaseInfo();
            info.tagName = root.optString("tag_name", null);
            info.body = root.optString("body", null);
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    String name = a != null ? a.optString("name", "") : "";
                    if (name != null && name.toLowerCase().endsWith(".apk")) {
                        info.apkUrl = a.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "fetchLatest failed", t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static boolean isNewer(String remoteTag, String currentVer) {
        if (remoteTag == null) return false;
        String r = (remoteTag.startsWith("v") || remoteTag.startsWith("V"))
                ? remoteTag.substring(1) : remoteTag;
        int[] a = parseVersion(r);
        int[] b = parseVersion(currentVer);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parseVersion(String s) {
        if (s == null || s.isEmpty()) return new int[]{0};
        String[] parts = s.split("[.\\-+]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("\\D", "")); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static String currentVersionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static class ReleaseInfo {
        String tagName;
        String apkUrl;
        String body;
    }
}