package com.nick.flowlauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class CrashReportActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String crash = FlowApplication.getCrash(this);
        if (crash == null || crash.isEmpty()) crash = "No crash report was recorded.";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        root.setBackgroundColor(0xFF101014);

        TextView title = new TextView(this);
        title.setText("Flow Launcher hit a startup error");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("Copy the report and paste it into our chat. You can also retry the launcher after clearing the report.");
        note.setTextColor(0xFFCCCCCC);
        note.setTextSize(15f);
        note.setPadding(0, 0, 0, dp(12));
        root.addView(note);

        final String report = crash;
        TextView body = new TextView(this);
        body.setText(crash);
        body.setTextColor(0xFFE6E6E6);
        body.setTextSize(12f);
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(10), 0, 0);

        Button copy = new Button(this);
        copy.setText("Copy report");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Flow Launcher crash", report));
            Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show();
        });
        buttons.addView(copy);

        Button retry = new Button(this);
        retry.setText("Retry launcher");
        retry.setOnClickListener(v -> {
            FlowApplication.clearCrash(this);
            Intent i = new Intent(this, LauncherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
        buttons.addView(retry);

        root.addView(buttons);
        setContentView(root);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
