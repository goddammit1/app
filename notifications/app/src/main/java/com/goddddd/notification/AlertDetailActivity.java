package com.goddddd.notification;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pretty per-alert report:
 *  - green block for users who answered "ready"
 *  - red block for users who answered "not ready"
 *  - grey block for users who have not answered yet
 */
public class AlertDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ALERT_ID = "alertId";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert_detail);
        setTitle(R.string.detail_title);

        String alertId = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_ALERT_ID) : null;
        if (alertId == null) {
            finish();
            return;
        }

        load(alertId);
    }

    private void load(String alertId) {
        RemoteUsers.loadAlertDetail(alertId, new RemoteUsers.AlertDetailCallback() {
            @Override
            public void onResult(RemoteUsers.AlertDetail d) {
                render(d);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(AlertDetailActivity.this,
                        "Load error: " + message,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void render(RemoteUsers.AlertDetail d) {
        TextView headerDate = findViewById(R.id.headerDate);
        TextView headerText = findViewById(R.id.headerText);
        TextView headerSummary = findViewById(R.id.headerSummary);

        TextView sectionReady = findViewById(R.id.sectionReady);
        TextView sectionNotReady = findViewById(R.id.sectionNotReady);
        TextView sectionPending = findViewById(R.id.sectionPending);

        LinearLayout listReady = findViewById(R.id.listReady);
        LinearLayout listNotReady = findViewById(R.id.listNotReady);
        LinearLayout listPending = findViewById(R.id.listPending);

        headerDate.setText(DateFormat.format("dd.MM HH:mm", d.ts));
        headerText.setText((d.text != null && !d.text.isEmpty())
                ? d.text : getString(R.string.detail_no_text));

        // Split recipients into three groups
        List<String> ready = new ArrayList<>();
        List<String> notReady = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        if (d.recipients != null) {
            for (String login : d.recipients) {
                Boolean r = d.responses != null ? d.responses.get(login) : null;
                if (r == null) pending.add(login);
                else if (r) ready.add(login);
                else notReady.add(login);
            }
        }

        int totalResp = ready.size() + notReady.size();
        int totalRec = (d.recipients != null) ? d.recipients.size() : 0;
        headerSummary.setText(totalResp + " / " + totalRec + " responded");

        sectionReady.setText(getString(R.string.detail_section_ready, ready.size()));
        sectionNotReady.setText(getString(R.string.detail_section_not_ready, notReady.size()));
        sectionPending.setText(getString(R.string.detail_section_pending, pending.size()));

        fill(listReady, ready, R.drawable.bg_status_ready, R.color.status_ready_fg);
        fill(listNotReady, notReady, R.drawable.bg_status_not_ready, R.color.status_not_ready_fg);
        fill(listPending, pending, R.drawable.bg_status_pending, R.color.status_pending_fg);

        sectionReady.setVisibility(ready.isEmpty() ? View.GONE : View.VISIBLE);
        listReady.setVisibility(ready.isEmpty() ? View.GONE : View.VISIBLE);

        sectionNotReady.setVisibility(notReady.isEmpty() ? View.GONE : View.VISIBLE);
        listNotReady.setVisibility(notReady.isEmpty() ? View.GONE : View.VISIBLE);

        sectionPending.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        listPending.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void fill(LinearLayout parent, List<String> logins,
                      @DrawableRes int bg, int textColorRes) {
        parent.removeAllViews();
        int marginPx = (int) (6 * getResources().getDisplayMetrics().density);
        int paddingPx = (int) (12 * getResources().getDisplayMetrics().density);
        for (String login : logins) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = marginPx;
            tv.setLayoutParams(lp);
            tv.setBackgroundResource(bg);
            tv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            tv.setText(login);
            tv.setTextColor(getResources().getColor(textColorRes));
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            parent.addView(tv);
        }
    }
}