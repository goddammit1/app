package com.goddddd.notification;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Lightweight in-process "loading" overlay.
 *
 * Adds a translucent full-screen view with a centered spinner (and an
 * optional message) on top of the current Activity's decor view. It also
 * disables user input on the rest of the UI while it is visible by
 * setting FLAG_NOT_TOUCHABLE off / claiming clicks on itself, and by
 * applying FLAG_NOT_TOUCH_MODAL = false to the window so back press still
 * works.
 *
 * It exists so we don't have to edit every activity layout: any screen
 * can show progress with a single call.
 *
 *   BusyOverlay.show(this, "Signing in...");
 *   BusyOverlay.hide(this);
 *
 * Both calls are safe to chain repeatedly and from any thread - they
 * post to the main looper internally.
 */
public final class BusyOverlay {

    private static final int TAG_KEY = R.id.tag_busy_overlay;

    private BusyOverlay() {}

    /** Show (or update text of) the overlay attached to this activity. */
    public static void show(final Activity activity, final CharSequence message) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View existing = decor.findViewById(R.id.busy_overlay_root);
            if (existing != null) {
                TextView tv = existing.findViewById(R.id.busy_overlay_text);
                if (tv != null) {
                    if (message == null || message.length() == 0) {
                        tv.setVisibility(View.GONE);
                    } else {
                        tv.setVisibility(View.VISIBLE);
                        tv.setText(message);
                    }
                }
                return;
            }
            decor.addView(build(activity, message),
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        });
    }

    /** Convenience: no message, just a spinner. */
    public static void show(Activity activity) {
        show(activity, null);
    }

    /** Hide the overlay if it is showing. */
    public static void hide(final Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View existing = decor.findViewById(R.id.busy_overlay_root);
            if (existing != null) decor.removeView(existing);
        });
    }

    private static View build(Activity activity, CharSequence message) {
        FrameLayout root = new FrameLayout(activity);
        root.setId(R.id.busy_overlay_root);
        // Semi-transparent black scrim. Consumes touches so the user
        // cannot tap the (now busy) UI behind it.
        root.setBackgroundColor(0xB3000000);
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);

        ProgressBar pb = new ProgressBar(activity);
        pb.setIndeterminate(true);
        column.addView(pb);

        TextView tv = new TextView(activity);
        tv.setId(R.id.busy_overlay_text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14f);
        tv.setGravity(Gravity.CENTER);
        int padPx = (int) (12 * activity.getResources().getDisplayMetrics().density);
        tv.setPadding(padPx, padPx, padPx, 0);
        if (message == null || message.length() == 0) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(message);
        }
        column.addView(tv);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(column, lp);
        return root;
    }
}