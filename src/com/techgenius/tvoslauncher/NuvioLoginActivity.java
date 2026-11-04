package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Signs the TV in to the user's Nuvio account: shows the code Nuvio hands out, polls until it is
 * approved at nuvio.tv/tv-login, then pulls Continue Watching.
 */
public class NuvioLoginActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private NuvioAccount.Login login;
    private TextView status;
    private boolean stopped;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (stopped || login == null) return;
            new Thread(() -> {
                String state;
                try {
                    state = NuvioAccount.poll(getApplicationContext(), login);
                } catch (Exception e) {
                    state = "pending";
                }
                final String s = state;
                runOnUiThread(() -> {
                    if (stopped) return;
                    if ("approved".equalsIgnoreCase(s)) {
                        exchange();
                    } else if ("expired".equalsIgnoreCase(s)) {
                        status.setText("That code expired — asking Nuvio for a new one…");
                        start();
                    } else {
                        status.setText("Waiting for approval on nuvio.tv/tv-login…");
                        handler.postDelayed(poll, Math.max(2, login.pollSeconds) * 1000L);
                    }
                });
            }, "nuvio-poll").start();
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(build());
        start();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0B0B0C"));
        root.setPadding(120, 60, 120, 60);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Nuvio account");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("On your phone open nuvio.tv/tv-login and enter this code");
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(16);
        hint.setPadding(0, 18, 0, 26);
        root.addView(hint);

        TextView code = new TextView(this);
        code.setId(android.R.id.text1);
        code.setText("· · · · · ·");
        code.setTextColor(Color.WHITE);
        code.setTextSize(54);
        code.setLetterSpacing(0.18f);
        code.setPadding(0, 10, 0, 26);
        root.addView(code);

        TextView url = new TextView(this);
        url.setId(android.R.id.text2);
        url.setTextColor(0x66FFFFFF);
        url.setTextSize(13);
        url.setPadding(0, 0, 0, 22);
        root.addView(url);

        status = new TextView(this);
        status.setText("Contacting Nuvio…");
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(16);
        root.addView(status);
        return root;
    }

    /** 32 hex characters are unreadable in one run; four groups are not. */
    private static String grouped(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 8 == 0) out.append(' ');
            out.append(Character.toUpperCase(code.charAt(i)));
        }
        return out.toString();
    }

    private void start() {
        final TextView code = findViewById(android.R.id.text1);
        new Thread(() -> {
            try {
                final NuvioAccount.Login started = NuvioAccount.startLogin(getApplicationContext());
                runOnUiThread(() -> {
                    login = started;
                    code.setText(grouped(started.code));
                    ((TextView) findViewById(android.R.id.text2)).setText(started.webUrl);
                    status.setText("Waiting for approval on nuvio.tv/tv-login…");
                    handler.postDelayed(poll, Math.max(2, started.pollSeconds) * 1000L);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not reach Nuvio: " + e.getMessage()));
            }
        }, "nuvio-login").start();
    }

    private void exchange() {
        status.setText("Signed in. Fetching your Continue Watching…");
        new Thread(() -> {
            try {
                NuvioAccount.finishLogin(getApplicationContext(), login);
                // A connected account is useless if the home row is still switched off.
                Profiles.homePrefs(getApplicationContext()).edit().putBoolean("cw_enabled", true).apply();
                NuvioSync.refresh(getApplicationContext());
                runOnUiThread(() -> {
                    Toast.makeText(this, "Nuvio connected", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Sign-in failed: " + e.getMessage()));
            }
        }, "nuvio-exchange").start();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
