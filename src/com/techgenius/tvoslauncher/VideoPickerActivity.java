package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
        VideoCache.purgeExcluded(this);   // drop cached clips from categories the owner excluded
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

    private int rowsShown = SHOW_ROWS;

    private void populate() {
        list.removeAllViews();
        int shown = 0;
        for (WallperCatalogParse.Clip c : clips) {
            if (shown >= rowsShown) break;
            list.addView(row(c, shown++));
        }
        if (clips.size() > rowsShown) {
            // Progressive disclosure instead of a hard 60-row cap: the catalog holds 800+ clips,
            // the owner asked why only one page was reachable.
            TextView more = new TextView(this);
            more.setText(I18n.s(this, "Show more", "Daha fazla göster")
                    + "  (+" + Math.min(SHOW_ROWS, clips.size() - rowsShown) + ")");
            more.setTextSize(15);
            more.setTextColor(0xFFF4F4F6);
            more.setGravity(Gravity.CENTER);
            more.setPadding(dp(8), dp(10), dp(8), dp(10));
            more.setBackground(new MainActivity.LiquidGlass(
                    dp(14), 0x2210161C, null, 0, 0, 1f).adaptive());
            more.setOnClickListener(v -> {
                rowsShown += SHOW_ROWS;
                populate();
            });
            more.setOnFocusChangeListener((v, f) -> more.setTextColor(f ? 0xFF111114 : 0xFFF4F4F6));
            more.setFocusable(true);
            more.setFocusableInTouchMode(true);
            list.addView(more);
        }
        status.setText(clips.size() > rowsShown
                ? "Top " + rowsShown + " of " + clips.size() + " — pick one to download"
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
        String key = bareKey(c);
        if (VideoCache.isCurrent(this, key)) state.setText("✓ current");
        else if (VideoCache.isCached(this, key)) state.setText("cached — hold to delete");
        state.setLayoutParams(slp);
        row.addView(state);
        row.setTag(state);

        row.setOnClickListener(v -> pick(c, state));
        row.setOnLongClickListener(v -> {
            if (VideoCache.isCached(this, key)) {
                VideoCache.deleteClip(this, key);
                Toast.makeText(this, "Deleted from storage", Toast.LENGTH_SHORT).show();
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
        // Defensive: older cached catalogues carried keys that already ended in .mp4.
        String cdnKey = bareKey(c);
        // The clip lands in the owner's Download folder under its OWN NAME — a real MP4 file
        // they can see and keep (owner request), not a UUID in a private cache.
        String key = c.name == null || c.name.trim().isEmpty() ? cdnKey
                : c.name.trim().replaceAll("[^A-Za-z0-9 ()_-]+", "_");
        downloadingKey = key;
        state.setText("Downloading… 0%");
        String videoUrl = CDN + "/wallper-user-generated/" + cdnKey + ".mp4";
        new Thread(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < 3; attempt++) {   // CDN 403s are frequently transient
                if (attempt > 0) {
                    try { Thread.sleep(1500L * attempt); } catch (InterruptedException ignored) { }
                }
                try {
                    // FileOutputStream never creates parents: without this the write fails with
                    // FileNotFoundException (No such file or directory) — the failure the owner hit.
                    new File(getFilesDir(), "videos").mkdirs();
                    File tmp = new File(getCacheDir(), "clip.part");
                    HttpURLConnection conn = (HttpURLConnection) new URL(videoUrl).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    long total = conn.getContentLength();
                    long done = 0;
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
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
                    // A truncated read plays a few frames and freezes — the "never plays" class.
                    if (total > 0 && done < total) {
                        throw new Exception("download incomplete: " + done + "/" + total + " bytes");
                    }
                    android.media.MediaMetadataRetriever mm = new android.media.MediaMetadataRetriever();
                    try {
                        mm.setDataSource(tmp.getPath());
                        String dur = mm.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (dur == null || Long.parseLong(dur) <= 0) {
                            throw new Exception("file has no video track");
                        }
                    } finally {
                        mm.release();
                    }
                    // Motion check: a locked-shot clip loops a near-still frame and reads as a
                    // FROZEN wallpaper. Sample three frames and require visible motion.
                    android.media.MediaMetadataRetriever mm2 = new android.media.MediaMetadataRetriever();
                    try {
                        mm2.setDataSource(tmp.getPath());
                        android.graphics.Bitmap f0 = mm2.getFrameAtTime(0,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        android.graphics.Bitmap f2 = mm2.getFrameAtTime(2_000_000,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        android.graphics.Bitmap f4 = mm2.getFrameAtTime(4_000_000,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (f0 == null || f2 == null || f4 == null) {
                            throw new Exception("could not sample frames for the motion check");
                        }
                        int w = Math.min(320, f0.getWidth()), h = w * f0.getHeight() / Math.max(1, f0.getWidth());
                        android.graphics.Bitmap s0 = android.graphics.Bitmap.createScaledBitmap(f0, w, h, true);
                        android.graphics.Bitmap s2 = android.graphics.Bitmap.createScaledBitmap(f2, w, h, true);
                        android.graphics.Bitmap s4 = android.graphics.Bitmap.createScaledBitmap(f4, w, h, true);
                        double diff01 = 0, diff12 = 0;
                        int px = w * h;
                        int[] p0 = new int[px], p2 = new int[px], p4 = new int[px];
                        s0.getPixels(p0, 0, w, 0, 0, w, h);
                        s2.getPixels(p2, 0, w, 0, 0, w, h);
                        s4.getPixels(p4, 0, w, 0, 0, w, h);
                        for (int i = 0; i < px; i++) {
                            int a = p0[i], b = p2[i], cc = p4[i];
                            diff01 += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                                    + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                                    + Math.abs((a & 255) - (b & 255));
                            diff12 += Math.abs(((b >> 16) & 255) - ((cc >> 16) & 255))
                                    + Math.abs(((b >> 8) & 255) - ((cc >> 8) & 255))
                                    + Math.abs((b & 255) - (cc & 255));
                        }
                        double motion = Math.min(diff01, diff12) / (double) px;
                        f0.recycle(); f2.recycle(); f4.recycle();
                        s0.recycle(); s2.recycle(); s4.recycle();
                        if (motion < 5.0) {
                            throw new Exception("low-motion clip (motion score "
                                    + String.format(java.util.Locale.ROOT, "%.1f", motion)
                                    + " < 5) — it would look frozen as a wallpaper");
                        }
                    } finally {
                        mm2.release();
                    }
                    // Codec check: the codec must have a decoder ON THIS TV (the X1+ has no AV1,
                    // so a valid AV1 file would still freeze on frame one forever).
                    android.media.MediaExtractor mex = new android.media.MediaExtractor();
                    try {
                        mex.setDataSource(tmp.getPath());
                        String mime = null;
                        int w = 0, h = 0;
                        for (int i = 0; i < mex.getTrackCount(); i++) {
                            android.media.MediaFormat f = mex.getTrackFormat(i);
                            String m = f.getString(android.media.MediaFormat.KEY_MIME);
                            if (m != null && m.startsWith("video/")) {
                                mime = m;
                                w = f.getInteger(android.media.MediaFormat.KEY_WIDTH);
                                h = f.getInteger(android.media.MediaFormat.KEY_HEIGHT);
                            }
                        }
                        if (mime == null) throw new Exception("no video track");
                        android.media.MediaCodecList mcl = new android.media.MediaCodecList(
                                android.media.MediaCodecList.ALL_CODECS);
                        android.media.MediaFormat probe = android.media.MediaFormat.createVideoFormat(mime, w, h);
                        if (mcl.findDecoderForFormat(probe) == null) {
                            throw new Exception("this TV cannot decode " + mime
                                    + " — pick an H.264 clip or transcode it");
                        }
                    } finally {
                        mex.release();
                    }
                    finishDownload(c, key, tmp, state);
                    return;
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "download attempt " + (attempt + 1) + " failed", e);
                    new File(getCacheDir(), "clip.part").delete();
                }
            }
            String msg = lastError == null || lastError.getMessage() == null
                    ? lastError == null ? "" : lastError.getClass().getSimpleName()
                    : lastError.getMessage();
            String shortMsg = msg.length() > 60 ? msg.substring(0, 60) + "…" : msg;
            String httpHint = lastError != null && lastError instanceof java.io.FileNotFoundException
                    ? " (clip not on CDN)" : "";
            runOnUiThread(() -> {
                state.setText("Failed: " + shortMsg + httpHint);
                downloadingKey = null;
            });
        }, "clip-download").start();
    }

    private void finishDownload(WallperCatalogParse.Clip c, String key, File tmp, TextView state) throws Exception {
        {
            File dst = new File(VideoCache.downloadDir(this), key + ".mp4");
            if (!tmp.renameTo(dst)) {
                try (java.io.FileInputStream in2 = new java.io.FileInputStream(tmp);
                     FileOutputStream out2 = new FileOutputStream(dst)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in2.read(buf)) > 0) out2.write(buf, 0, n);
                }
                tmp.delete();
            }
            copyPoster(c, key, c.previewUrl);
            VideoCache.setCurrent(this, key, c.slug, c.name, c.category);
            VideoCache.evict(this);
            runOnUiThread(() -> {
                state.setText("✓ current");
                downloadingKey = null;
                Toast.makeText(this, "Applied — back to Home to see it", Toast.LENGTH_LONG).show();
            });
        }
    }


    private void copyPoster(WallperCatalogParse.Clip c, String key, String previewUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(c.previewUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            File dst = VideoCache.posterFile(this, key);
            File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
            long written = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); written += n; }
            } finally {
                conn.disconnect();
            }
            // A 0-byte or undecodable poster must never replace a good one: the still fallback
            // and the glass bake both read this file.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), o);
            if (written > 0 && o.outWidth > 0) {
                if (!tmp.renameTo(dst)) tmp.delete();
            } else {
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "poster download failed", e);
        }
    }

    private static String bareKey(WallperCatalogParse.Clip c) {
        return c.videoKey.endsWith(".mp4")
                ? c.videoKey.substring(0, c.videoKey.length() - 4) : c.videoKey;
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
