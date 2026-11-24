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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView head;
    private TextView status;
    /** The owned-clip list, probed off the UI thread (each check tail-decodes a video). */
    private List<String[]> library = new ArrayList<>();
    private List<WallperCatalogParse.Clip> clips = new ArrayList<>();
    private String downloadingKey;

    @Override
    protected void onCreate(Bundle saved) {
        VideoCache.purgeExcluded(this);   // drop cached clips from categories the owner excluded
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(build());
        // The library probe decodes video tails: never on the UI thread (it hung the picker's
        // first paint — the pause-timeout in the logs).
        new Thread(() -> {
            List<String[]> lib = VideoCache.cachedClips(this);
            for (String[] row : lib) VideoCache.ensurePoster(this, row[0]);
            runOnUiThread(() -> {
                library = lib;
                populate();
            });
        }, "library-probe").start();
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

        head = new TextView(this);
        head.setText("Video Wallpapers");
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

    private int page = 0;
    private static final int PAGE_COLS = 4, PAGE_ROWS = 2;
    private static final int PAGE_SIZE = PAGE_COLS * PAGE_ROWS;

    /** The page strip above the grid: previous / indicator / next. */
    private View pageBar(int pages) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, dp(2));
        TextView prev = navBtn("‹  " + I18n.s(this, "Prev", "Önceki"));
        prev.setOnClickListener(v -> { if (page > 0) { page--; populate(); } });
        TextView next = navBtn(I18n.s(this, "Next", "Sonraki") + "  ›");
        next.setOnClickListener(v -> { if (page < pages - 1) { page++; populate(); } });
        TextView ind = new TextView(this);
        ind.setText("Page " + (page + 1) + " / " + pages);
        ind.setTextColor(0xB3FFFFFF);
        ind.setTextSize(13);
        ind.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ind.setLayoutParams(ilp);
        bar.addView(prev);
        bar.addView(ind);
        bar.addView(next);
        return bar;
    }

    private TextView navBtn(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(0xFFF4F4F6);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setFocusable(true);
        // Focus/hover must keep the label readable: a LIGHT fill appears behind the dark text.
        b.setOnFocusChangeListener((v, f) -> {
            b.setTextColor(f ? 0xFF111114 : 0xFFF4F4F6);
            b.setBackground(new MainActivity.LiquidGlass(dp(14),
                    f ? 0xFFE8E8EC : 0x2210161C, null, 0, 0, 1f).adaptive());
        });
        b.setBackground(new MainActivity.LiquidGlass(dp(14), 0x2210161C, null, 0, 0, 1f).adaptive());
        return b;
    }

    /** One catalog tile: large poster, name, state badge; the bottom edge D-pad-down flips the page. */
    private View tile(WallperCatalogParse.Clip c, int idxInPage) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(6), dp(6), dp(6), dp(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = idxInPage % PAGE_COLS == 0 ? 0 : dp(8);
        col.setLayoutParams(clp);
        col.setFocusable(true);
        col.setClickable(true);
        col.setBackground(new MainActivity.LiquidGlass(dp(12), 0x1A10161C, null, 0, 0, 1f).adaptive());
        col.setOnFocusChangeListener((v, f) -> {
            v.animate()
                    .scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start();
            col.setBackground(new MainActivity.LiquidGlass(dp(12),
                    f ? 0x66E8E8EC : 0x1A10161C, null, 0, 0, 1f).adaptive());
        });

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int tw = dp(200), th = dp(112);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(tw, th);
        thumb.setLayoutParams(tlp);
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(dp(6));
        ph.setColor(0x2EFFFFFF);
        thumb.setBackground(ph);
        thumb.setClipToOutline(true);
        col.addView(thumb);

        TextView name = new TextView(this);
        name.setText(c.name == null ? "" : c.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(12);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, dp(5), 0, 0);
        col.addView(name);

        TextView badge = new TextView(this);
        badge.setTextSize(10);
        badge.setGravity(Gravity.CENTER_HORIZONTAL);
        badge.setPadding(0, dp(2), 0, 0);
        String key = bareKey(c);
        if (VideoCache.isCurrent(this, key)) {
            badge.setText("✓ " + I18n.s(this, "now playing", "şimdi oynatılıyor"));
            badge.setTextColor(0xFF9CE29C);
        } else if (VideoCache.isCached(this, key)) {
            badge.setText(I18n.s(this, "in library", "kitaplıkta"));
            badge.setTextColor(0x99FFFFFF);
        }
        col.addView(badge);

        loadThumbSized(thumb, c.previewUrl, tw, th);
        col.setOnClickListener(v -> pick(c, badge));
        // Page flip on the bottom edge: pressing down on the last row advances.
        if (idxInPage >= PAGE_SIZE - PAGE_COLS) {
            col.setOnKeyListener((v, k, ev) -> {
                if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && k == android.view.KeyEvent.KEYCODE_DPAD_DOWN && page < (clips.size() + PAGE_SIZE - 1) / PAGE_SIZE - 1) {
                    page++;
                    populate();
                    list.postDelayed(() -> {
                        if (list.getChildCount() > 0) {
                            View g = list.getChildAt(list.getChildCount() - 2);
                            if (g instanceof ViewGroup && ((ViewGroup) g).getChildCount() > 0) {
                                ((ViewGroup) g).getChildAt(0).requestFocus();
                            }
                        }
                    }, 80);
                    return true;
                }
                return false;
            });
        }
        return col;
    }

    private void populate() {
        list.removeAllViews();
        // ---- the owner's library first: real files they keep, with an explicit delete
        // (the clip playability probes are slow — the list is loaded off the UI thread in onCreate)
        List<String[]> lib = library;
        long totalBytes = 0;
        for (String[] e : lib) {
            File vf = VideoCache.videoFile(this, e[0]);
            if (vf.exists()) totalBytes += vf.length();
        }
        head.setText("Video Wallpapers — library " + lib.size()
                + " · " + (totalBytes / 1048576) + " MB — catalog " + clips.size());
        if (!lib.isEmpty()) {
            list.addView(sectionLabel(I18n.s(this, "Your library", "Kitaplığınız") + " — " + lib.size()
                    + (totalBytes > 0 ? " · " + (totalBytes / 1048576) + " MB" : "")));
            for (String[] e : lib) list.addView(libraryRow(e[0], e[2]));
            list.addView(sectionLabel(I18n.s(this, "From wallper.app — most liked",
                    "wallper.app'tan — en beğenilenler")));
        }
        // ---- the catalog as a paged grid: 4x2 large tiles, D-pad down at the bottom edge
        // flips forward, the page bar flips either way (the owner asked for grid + pages).
        int pages = (clips.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page >= pages) page = Math.max(0, pages - 1);
        if (page < 0) page = 0;
        list.addView(pageBar(pages));
        int start = page * PAGE_SIZE;
        int end = Math.min(clips.size(), start + PAGE_SIZE);
        for (int r = start; r < end; r += PAGE_COLS) {
            LinearLayout gridRow = new LinearLayout(this);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            gridRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams grlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            grlp.topMargin = r == start ? 0 : dp(8);
            gridRow.setLayoutParams(grlp);
            for (int i = r; i < Math.min(r + PAGE_COLS, end); i++) {
                gridRow.addView(tile(clips.get(i), i - start));
            }
            list.addView(gridRow);
        }
        status.setText("Page " + (page + 1) + " of " + pages
                + " — clips " + (clips.isEmpty() ? 0 : start + 1) + "–" + end + " of " + clips.size());
    }

    private TextView sectionLabel(String t) {
        TextView l = new TextView(this);
        l.setText(t.toUpperCase(java.util.Locale.ROOT));
        l.setTextColor(0x8CFFFFFF);
        l.setTextSize(11);
        l.setPadding(dp(4), dp(12), dp(4), dp(4));
        l.setLetterSpacing(0.08f);
        return l;
    }

    /** One owned clip: poster, name, size, now-playing badge, explicit delete button. */
    private View libraryRow(String key, String name) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        row.setLayoutParams(rlp);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(new MainActivity.LiquidGlass(dp(12), 0x2210161C, null, 0, 0, 1f).adaptive());
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
        File pf = VideoCache.posterFile(this, key);
        if (pf.exists()) {
            Bitmap bm = android.graphics.BitmapFactory.decodeFile(pf.getPath());
            if (bm != null) thumb.setImageBitmap(bm);
        }
        row.addView(thumb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(10);
        col.setLayoutParams(clp);
        TextView nm = new TextView(this);
        nm.setText(name == null || name.isEmpty() ? key : name);
        nm.setTextColor(Color.WHITE);
        nm.setTextSize(13);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nm);
        File vf = VideoCache.videoFile(this, key);
        TextView meta = new TextView(this);
        meta.setText((vf.exists() ? vf.length() / 1048576 + " MB" : "missing")
                + (VideoCache.isCurrent(this, key) ? "  ·  ✓ " + I18n.s(this, "now playing", "şimdi oynatılıyor") : "")
                + (VideoCache.decoderFrozen(this, key)
                        ? "  ·  " + I18n.s(this, "freezes on this TV", "bu TV'de donuyor") : ""));
        meta.setTextColor(0x99FFFFFF);
        meta.setTextSize(11);
        meta.setPadding(0, dp(2), 0, 0);
        col.addView(meta);
        row.addView(col);

        TextView del = new TextView(this);
        del.setText(I18n.s(this, "Delete", "Sil"));
        del.setTextSize(12);
        del.setTextColor(0xFFFF8A80);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(10);
        del.setLayoutParams(dlp);
        del.setBackground(new MainActivity.LiquidGlass(dp(14), 0x33FF5252, null, 0, 0, 1f).adaptive());
        del.setFocusable(true);
        del.setOnClickListener(v -> {
            boolean wasCurrent = VideoCache.isCurrent(this, key);
            VideoCache.deleteClip(this, key);
            android.widget.Toast.makeText(this, I18n.s(this, "Deleted ", "Silindi: ") + (name == null ? key : name),
                    android.widget.Toast.LENGTH_SHORT).show();
            if (wasCurrent) {
                // the current clip is gone: pick another owned clip so the wallpaper keeps playing
                List<String[]> rest = VideoCache.cachedClips(this);
                if (!rest.isEmpty()) VideoCache.setCurrentKey(this, rest.get(0)[0]);
            }
            new Thread(() -> {
                List<String[]> fresh = VideoCache.cachedClips(this);
                runOnUiThread(() -> {
                    library = fresh;
                    populate();
                });
            }, "library-probe").start();
        });
        row.addView(del);
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
                    // FROZEN wallpaper. The shared 5-sample spread judges the whole clip,
                    // not just the first seconds (the owl clip passed 0/2/4 s but is still).
                    String mvKey = key;
                    File mvTmp = tmp;
                    double motion = -1;
                    android.media.MediaMetadataRetriever mmv = new android.media.MediaMetadataRetriever();
                    try {
                        mmv.setDataSource(mvTmp.getPath());
                        String dur = mmv.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                        long durationMs = dur == null ? 0 : Long.parseLong(dur);
                        if (durationMs >= 4000) {
                            long[] at = {durationMs / 10, durationMs * 3 / 10, durationMs / 2,
                                    durationMs * 7 / 10, durationMs * 9 / 10};
                            Bitmap[] fr = new Bitmap[5];
                            boolean ok = true;
                            for (int si = 0; si < 5; si++) {
                                fr[si] = mmv.getFrameAtTime(at[si] * 1000,
                                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                                if (fr[si] == null) { ok = false; break; }
                            }
                            if (ok) {
                                int w = 160, h = 90;
                                double mTotal = 0;
                                for (int si = 1; si < 5; si++) {
                                    Bitmap a = Bitmap.createScaledBitmap(fr[si - 1], w, h, true);
                                    Bitmap b = Bitmap.createScaledBitmap(fr[si], w, h, true);
                                    int[] pa = new int[w * h], pb = new int[w * h];
                                    a.getPixels(pa, 0, w, 0, 0, w, h);
                                    b.getPixels(pb, 0, w, 0, 0, w, h);
                                    long diff = 0;
                                    for (int p = 0; p < pa.length; p++) {
                                        int la = ((pa[p] >> 16 & 255) * 3 + (pa[p] >> 8 & 255) * 6 + (pa[p] & 255)) / 10;
                                        int lb = ((pb[p] >> 16 & 255) * 3 + (pb[p] >> 8 & 255) * 6 + (pb[p] & 255)) / 10;
                                        diff += Math.abs(la - lb);
                                    }
                                    mTotal += diff / (double) pa.length;
                                }
                                motion = mTotal / 4.0;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { mmv.release(); } catch (Throwable ignored) { }
                    }
                    if (motion >= 0 && motion < 6.0) {
                        throw new Exception("low-motion clip (motion score "
                                + String.format(java.util.Locale.ROOT, "%.1f", motion)
                                + " < 6) — it would look frozen as a wallpaper");
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

    private void loadThumbSized(ImageView target, String url, int wDp, int hDp) {
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
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, wDp * 2, hDp * 2, true);
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
