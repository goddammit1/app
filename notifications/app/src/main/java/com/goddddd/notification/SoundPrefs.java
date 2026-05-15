package com.goddddd.notification;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;

/**
 * Stores the path to a user-picked custom alarm sound.
 * The file is copied into the app's internal storage by SettingsActivity.
 */
public class SoundPrefs {

    private static final String PREFS_NAME = "sound_prefs";
    private static final String KEY_CUSTOM_PATH = "custom_alarm_path";
    private static final String KEY_CUSTOM_NAME = "custom_alarm_name";

    public static final String CUSTOM_FILE_NAME = "alarm_custom";

    private SoundPrefs() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setCustomAlarm(Context ctx, String filePath, String displayName) {
        prefs(ctx).edit()
                .putString(KEY_CUSTOM_PATH, filePath)
                .putString(KEY_CUSTOM_NAME, displayName)
                .apply();
    }

    public static void clearCustomAlarm(Context ctx) {
        String old = getCustomAlarmPath(ctx);
        if (old != null) {
            try { new File(old).delete(); } catch (Exception ignored) {}
        }
        prefs(ctx).edit()
                .remove(KEY_CUSTOM_PATH)
                .remove(KEY_CUSTOM_NAME)
                .apply();
    }

    public static String getCustomAlarmPath(Context ctx) {
        return prefs(ctx).getString(KEY_CUSTOM_PATH, null);
    }

    public static String getCustomAlarmName(Context ctx) {
        return prefs(ctx).getString(KEY_CUSTOM_NAME, null);
    }

    /** @return Uri for AlarmEngine to play: custom file if set & exists, otherwise null. */
    public static Uri getCustomAlarmUri(Context ctx) {
        String path = getCustomAlarmPath(ctx);
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists() || f.length() == 0) return null;
        return Uri.fromFile(f);
    }
}