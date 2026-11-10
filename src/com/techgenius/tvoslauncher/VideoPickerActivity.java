package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * "Browse clips…": a ranked list of the wallper.app catalogue (most liked first, already filtered
 * to ≥ 1440p and the owner's categories). Picking a row downloads the clip and its poster into
 * {@link VideoCache} and applies it — the video_wall scene then shows the poster, and the player
 * (plan Task 5) plays the file behind the interface.
 */
public class VideoPickerActivity extends Activity {

    private static final String TAG = "VideoPicker";
    private static final String CDN = "https://cdn.wallper.app";
    private static final int SHOW_ROWS = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private List<WallperCatalogParse.Clip> clips = new ArrayList<>();
    private String downloadingKey;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(build());
        WallperCatalog.loadAsync(this, (kept, fetchedAt) -> runOnUiThread(() -> {
            clips = kept;
            populate();
            if (kept.isEmpty()) status.setText("Catalogue is empty — check the connection.");
        }));
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0B0C"));
        root.setPadding(dp(28), dp(16), dp(28), dp(16));

        TextView head = new TextView(this);
        head.setText("Video Wallpapers — most liked");
        head.setTextColor(Color.WHITE);
        head.setTextSize(20);
        root.addView(head);

        status = new TextView(this);
        status.setText("Loading the catalogue…");
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(12);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void populate() {
        list.removeAllViews();
        int shown = 0;
        for (WallperCatalogParse.Clip c : clips) {
            if (shown >= SHOW_ROWS) break;
            list.addView(row(c, shown++));
        }
        status.setText(clips.size() > SHOW_ROWS
                ? "Top " + SHOW_ROWS + " of " + clips.size() + " — pick one to download"
                : clips.size() + " clips");
    }

    private View row(WallperCatalogParse.Clip c, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // tile background drawn inline: this project has no shared row drawable
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = index == 0 ? 0 : dp(6);
        row.setLayoutParams(rlp);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnFocusChangeListener((v, f) -> v.animate()
                .scaleX(f ? 1.02f : 1f).scaleY(f ? 1.02f : 1f).setDuration(120).start());

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(96), dp(54));
        thumb.setLayoutParams(tlp);
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(dp(4));
        ph.setColor(0x2EFFFFFF);
        thumb.setBackground(ph);
        loadThumb(thumb, c.previewUrl);
        row.addView(thumb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(10);
        col.setLayoutParams(clp);

        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);

        TextView meta = new TextView(this);
        meta.setText(c.width + "×" + c.height + "  ·  " + c.category + "  ·  ♥ " + c.likes
                + "  ·  " + c.durationSec + "s");
        meta.setTextColor(0x99FFFFFF);
        meta.setTextSize(11);
        meta.setPadding(0, dp(2), 0, 0);
        col.addView(meta);

        TextView state = new TextView(this);
        state.setTextSize(11);
        state.setGravity(Gravity.END);
        state.setTextColor(0xBFFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(10);
        if (VideoCache.isCurrent(this, c.videoKey)) state.setText("✓ current");
        state.setLayoutParams(slp);
        row.addView(state);
        row.setTag(state);

        row.setOnClickListener(v -> pick(c, state));
        row.setOnLongClickListener(v -> {
            if (VideoCache.isCurrent(this, c.videoKey)) {
                VideoCache.deleteCurrent(this);
                state.setText("");
                Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show();
                populate();
            }
            return true;
        });
        return row;
    }

    private void pick(WallperCatalogParse.Clip c, TextView state) {
        if (downloadingKey != null) {
            Toast.makeText(this, "Already downloading a clip", Toast.LENGTH_SHORT).show();
            return;
        }
        downloadingKey = c.videoKey;
        state.setText("Downloading… 0%");
        String videoUrl = CDN + "/wallper-user-generated/" + c.videoKey + ".mp4";
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "clip.part");
                HttpURLConnection conn = (HttpURLConnection) new URL(videoUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                long total = conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            runOnUiThread(() -> state.setText("Downloading… " + pct + "%"));
                        }
                    }
                }
                conn.disconnect();
                File dst = VideoCache.videoFile(this, c.videoKey);
                if (!tmp.renameTo(dst)) {
                    try (java.io.FileInputStream in2 = new java.io.FileInputStream(tmp);
                         FileOutputStream out2 = new FileOutputStream(dst)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in2.read(buf)) > 0) out2.write(buf, 0, n);
                    }
                    tmp.delete();
                }
                copyPoster(c);
                VideoCache.setCurrent(this, c.videoKey, c.slug, c.name, c.category);
                VideoCache.evict(this);
                runOnUiThread(() -> {
                    state.setText("✓ current");
                    downloadingKey = null;
                    Toast.makeText(this, "Applied — back to Home to see it", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                new File(getCacheDir(), "clip.part").delete();
                runOnUiThread(() -> {
                    state.setText("Failed — try again");
                    downloadingKey = null;
                });
            }
        }, "clip-download").start();
    }

    private void copyPoster(WallperCatalogParse.Clip c) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(c.previewUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(VideoCache.posterFile(this, c.videoKey))) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "poster download failed", e);
        }
    }

    private void loadThumb(ImageView target, String url) {
        target.setTag(url);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(10000);
                bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
            } catch (Exception ignored) {
            }
            if (bmp == null) return;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, dp(96) * 2, dp(54) * 2, true);
            if (scaled != bmp) bmp.recycle();
            runOnUiThread(() -> {
                if (url.equals(target.getTag())) target.setImageBitmap(scaled);
            });
        }, "clip-thumb").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
