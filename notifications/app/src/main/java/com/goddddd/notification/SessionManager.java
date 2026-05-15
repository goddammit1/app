package com.goddddd.notification;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User session storage backed by SharedPreferences.
 */
public class SessionManager {

    private static final String PREFS_NAME = "session_prefs";
    private static final String KEY_LOGGED_IN = "is_logged_in";
    private static final String KEY_LOGIN = "login";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void login(String login) {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_LOGIN, login)
                .apply();
    }

    public void logout() {
        prefs.edit()
                .remove(KEY_LOGGED_IN)
                .remove(KEY_LOGIN)
                .apply();
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_LOGGED_IN, false);
    }

    public String getLogin() {
        return prefs.getString(KEY_LOGIN, null);
    }
}