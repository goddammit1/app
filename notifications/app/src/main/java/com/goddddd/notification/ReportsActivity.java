package com.goddddd.notification;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class ReportsActivity extends AppCompatActivity {

    private ListView listView;
    private ProgressBar progress;
    private TextView emptyText;

    private SessionManager session;
    private final List<RemoteUsers.AlertSummary> alerts = new ArrayList<>();
    private ArrayAdapter<RemoteUsers.AlertSummary> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reports);

        session = new SessionManager(this);
        if (!session.isLoggedIn()) {
            finish();
            return;
        }

        listView = findViewById(R.id.listView);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.emptyText);

        adapter = new ArrayAdapter<RemoteUsers.AlertSummary>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, alerts) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                RemoteUsers.AlertSummary a = alerts.get(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setTextColor(getResources().getColor(R.color.dark_text_primary));
                t2.setTextColor(getResources().getColor(R.color.dark_text_secondary));

                String dateStr = DateFormat.format("dd.MM HH:mm", a.ts).toString();
                String text = (a.text != null && !a.text.isEmpty()) ? a.text : "(no text)";
                t1.setText(dateStr + " - " + text);
                t2.setText("Responses: " + a.responseCount + " / " + a.recipientCount);
                return v;
            }
        };
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((p, view, position, id) -> {
            Intent i = new Intent(this, AlertDetailActivity.class);
            i.putExtra(AlertDetailActivity.EXTRA_ALERT_ID, alerts.get(position).id);
            startActivity(i);
        });

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh so newly arrived responses are reflected when user returns.
        if (session != null && session.isLoggedIn()) load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        RemoteUsers.loadMyAlerts(session.getLogin(),
                new RemoteUsers.AlertsListCallback() {
                    @Override
                    public void onResult(List<RemoteUsers.AlertSummary> result) {
                        progress.setVisibility(View.GONE);
                        alerts.clear();
                        alerts.addAll(result);
                        adapter.notifyDataSetChanged();
                        emptyText.setVisibility(alerts.isEmpty() ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onError(String message) {
                        progress.setVisibility(View.GONE);
                        Toast.makeText(ReportsActivity.this,
                                "Load error: " + message, Toast.LENGTH_LONG).show();
                    }
                });
    }

}
