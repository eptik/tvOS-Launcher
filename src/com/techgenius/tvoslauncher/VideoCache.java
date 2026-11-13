package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage for the video-wallpaper mode: the clip the owner picked, its poster, and the bounded
 * cache. Files live in {@code files/videos/<fileKey>.mp4} with a {@code .png} poster beside them;
 * per-clip metadata (slug, name, category, timestamp) lives in prefs so the LRU purge and the
 * category filter can run without touching the filesystem.
 */
final class VideoCache {

    private static final String TAG = "VideoCache";
    // The old 500 MB cap evicted the owner's library while they browsed the picker (4K clips
    // are 30-110 MB each). Size the cap from real free space instead: half of what is free,
    // bounded 1-3 GB, re-read on every eviction.
    private static final long CAP_MIN = 1024L * 1024 * 1024;
    private static final long CAP_MAX = 3L * 1024 * 1024 * 1024;

    private static long capBytes(Context c) {
        try {
            long free = new File(c.getFilesDir(), "videos").getParentFile().getUsableSpace();
            long cap = free / 2;
            return Math.max(CAP_MIN, Math.min(CAP_MAX, cap));
        } catch (Exception e) {
            return CAP_MIN;
        }
    }

    private VideoCache() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("video_wall", Context.MODE_PRIVATE);
    }

    /** The owner's clip folder: plain MP4 files in Downloads/tvos-wallpapers, visible and manageable. */
    static File downloadDir(Context c) {
        File dir = new File(android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "tvos-wallpapers");
        dir.mkdirs();
        return dir;
    }

    /** True when the key names a file that lives in the owner's Download folder. */
    static boolean isDownloadClip(String key) {
        return key != null && !key.matches("[0-9A-Fa-f-]{36}");
    }

    static File videoFile(Context c, String key) {
        if (isDownloadClip(key)) {
            File dl = new File(downloadDir(c), key + ".mp4");
            if (dl.exists()) return dl;
        }
        File dir = new File(c.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, key + ".mp4");
    }

    static File posterFile(Context c, String key) {
        File dir = new File(c.getFilesDir(), "videos");
        dir.mkdirs();
        return new File(dir, key + ".png");
    }

    static boolean hasCurrent(Context c) {
        String key = prefs(c).getString("current_key", null);
        return key != null && videoFile(c, key).exists();
    }

    /** Identity of the current clip for cheap cache checks (0 when nothing is picked). */
    static long currentKeyHash(Context c) {
        String key = prefs(c).getString("current_key", null);
        return key == null ? 0L : key.hashCode();
    }

    static File current(Context c) {
        String key = prefs(c).getString("current_key", null);
        return key == null ? null : videoFile(c, key);
    }

    /** The picked clip's poster, decoded at the panel's size, or null when nothing is picked. */
    static Bitmap currentPoster(Context c, int w, int h) {
        String key = prefs(c).getString("current_key", null);
        if (key == null) return null;
        File f = posterFile(c, key);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), o);
        if (bmp == null || (bmp.getWidth() <= w && bmp.getHeight() <= h)) return bmp;
        float sc = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * sc),
                Math.round(bmp.getHeight() * sc), true);
        if (scaled != bmp) bmp.recycle();
        return scaled;
    }

    /** Duration verdicts per file, cached for the process: probing decodes the moov, not free. */
    private static final java.util.HashMap<String, Boolean> playableCache = new java.util.HashMap<>();

    /** True when the file carries a real video track (>= 3 s): truncated downloads play a tiny
     * head fragment on loop and read as a static wallpaper forever. */
    static boolean playable(Context c, String key) {
        File f = videoFile(c, key);
        String path = f.getPath();
        Boolean cached = playableCache.get(path);
        if (cached != null) return cached;
        boolean ok = false;
        try {
            android.media.MediaMetadataRetriever mm = new android.media.MediaMetadataRetriever();
            try {
                mm.setDataSource(path);
                String dur = mm.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = dur == null ? 0 : Long.parseLong(dur);
                // The moov's declared duration is a LIE on truncated downloads: the container
                // claims 50 s while the data stops at 1 s. Prove the tail actually decodes.
                Bitmap tail = durationMs >= 3000L
                        ? mm.getFrameAtTime((long) (durationMs * 0.75 * 1000),
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        : null;
                ok = tail != null;
            } finally {
                mm.release();
            }
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) Log.w(TAG, "unplayable clip (truncated/corrupt): " + key);
        playableCache.put(path, ok);
        return ok;
    }

    /** Cached clips that still exist on disk: the Download folder first, then legacy private clips. */
    static List<String[]> cachedClips(Context c) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        File[] downloads = downloadDir(c).listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
        if (downloads != null) {
            java.util.Arrays.sort(downloads, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : downloads) {
                String key = f.getName().replaceAll("(?i)\\.mp4$", "");
                if (seen.add(key) && playable(c, key)) {
                    out.add(new String[]{key, key, key, "download"});
                }
            }
        }
        try {
            JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
            for (int i = 0; i < meta.length(); i++) {
                JSONObject o = meta.getJSONObject(i);
                String key = o.optString("key");
                if (videoFile(c, key).exists()
                        && !WallperCatalogParse.Clip.excluded(o.optString("category"))
                        && seen.add(key) && playable(c, key)) {
                    out.add(new String[]{key, o.optString("slug"), o.optString("name"), o.optString("category")});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Re-points the current clip without re-downloading (rotation). */
    static void setCurrentKey(Context c, String key) {
        if (key == null) return;
        Log.i(TAG, "setCurrentKey " + (key.length() > 8 ? key.substring(0, 8) : key)
                + " stack=" + android.util.Log.getStackTraceString(new Throwable()).split("\n")[2].trim());
        SharedPreferences p = prefs(c);
        p.edit().putString("current_key", key)
                .putLong("ts_" + key, System.currentTimeMillis()).apply();
        for (String[] m : cachedClips(c)) {
            if (m[0].equals(key)) {
                p.edit().putString("current_slug", m[1]).putString("current_name", m[2])
                        .putString("current_category", m[3]).apply();
            }
        }
        evict(c);
    }

    /** Records a freshly downloaded clip as the current one and trims the cache to its cap. */
    static void setCurrent(Context c, String key, String slug, String name, String category) {
        SharedPreferences p = prefs(c);
        p.edit()
                .putString("current_key", key)
                .putString("current_slug", slug)
                .putString("current_name", name)
                .putString("current_category", category)
                .putLong("ts_" + key, System.currentTimeMillis())
                .apply();
        rememberMeta(c, key, slug, name, category);
        evict(c);
    }

    static String currentName(Context c) {
        return prefs(c).getString("current_name", "");
    }

    static boolean isCurrent(Context c, String key) {
        return key != null && key.equals(prefs(c).getString("current_key", null));
    }

    /** Deletes any cached clip by key (picker long-press), regardless of whether it is current. */
    static void deleteClip(Context c, String key) {
        if (key == null) return;
        videoFile(c, key).delete();
        posterFile(c, key).delete();
        try {
            JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < meta.length(); i++) {
                JSONObject o = meta.getJSONObject(i);
                if (!key.equals(o.optString("key"))) out.put(o);
            }
            prefs(c).edit().putString("meta", out.toString()).apply();
        } catch (Exception ignored) {
        }
        if (key.equals(prefs(c).getString("current_key", null))) {
            Log.w(TAG, "deleteClip cleared the CURRENT clip: " + key);
            prefs(c).edit().remove("current_key").remove("current_slug")
                    .remove("current_name").remove("current_category").apply();
        }
    }

    /** True when the clip's video file is already in the cache. */
    static boolean isCached(Context c, String key) {
        return key != null && videoFile(c, key).exists();
    }

    /** Deletes the picked clip (picker long-press). */
    static void deleteCurrent(Context c) {
        String key = prefs(c).getString("current_key", null);
        if (key == null) return;
        videoFile(c, key).delete();
        posterFile(c, key).delete();
        prefs(c).edit().remove("current_key").remove("current_slug")
                .remove("current_name").remove("current_category").apply();
    }

    /** Remembers a clip's category so the filter can purge it later even if it is not current. */
    static void rememberMeta(Context c, String key, String slug, String name, String category) {
        try {
            JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
            JSONArray out = new JSONArray();
            boolean found = false;
            for (int i = 0; i < meta.length(); i++) {
                JSONObject o = meta.getJSONObject(i);
                if (key.equals(o.optString("key"))) {
                    out.put(o.put("category", category).put("name", name));
                    found = true;
                } else {
                    out.put(o);
                }
            }
            if (!found) {
                out.put(new JSONObject().put("key", key).put("slug", slug)
                        .put("name", name).put("category", category));
            }
            prefs(c).edit().putString("meta", out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "meta update failed", e);
        }
    }

    /** Copies legacy private-cache clips into the Download folder once; the pool then lives there. */
    static void migrateToDownloads(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("migrated_to_downloads", false)) return;
        File privateDir = new File(c.getFilesDir(), "videos");
        File[] files = privateDir.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"));
        boolean copied = false;
        if (files != null && files.length > 0) {
            for (File f : files) {
                File dst = new File(downloadDir(c), f.getName());
                if (dst.exists()) continue;
                try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    copied = true;
                    Log.i(TAG, "migrated " + f.getName() + " to Downloads");
                } catch (Exception e) {
                    Log.w(TAG, "migrate failed for " + f.getName(), e);
                }
            }
        }
        // Only latch the flag when there was nothing to move or the move succeeded — a silent
        // permission failure must not disable the migration forever.
        if (!copied && (files == null || files.length == 0)) {
            p.edit().putBoolean("migrated_to_downloads", true).apply();
        }
    }

    /** Keeps the videos directory under its cap, oldest first; the current clip is never evicted. */
    static void evict(Context c) {
        try {
            File dir = new File(c.getFilesDir(), "videos");
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= capBytes(c)) return;
            String currentKey = prefs(c).getString("current_key", "");
            List<File> sorted = new ArrayList<>();
            for (File f : files) {
                if (f.getName().endsWith(".mp4") && f.getParentFile().equals(new File(c.getFilesDir(), "videos")))
                    sorted.add(f);   // Download-folder clips belong to the owner and are never evicted
            }
            sorted.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : sorted) {
                if (total <= capBytes(c)) break;
                if (f.getName().equals(currentKey + ".mp4")) continue;
                long len = f.length();
                String key = f.getName().replace(".mp4", "");
                f.delete();
                posterFile(c, key).delete();
                try {
                    JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < meta.length(); i++) {
                        if (!key.equals(meta.getJSONObject(i).optString("key"))) kept.put(meta.getJSONObject(i));
                    }
                    prefs(c).edit().putString("meta", kept.toString()).apply();
                } catch (Exception ignored) {
                }
                total -= len;
                Log.i(TAG, "evicted " + key);
            }
        } catch (Exception e) {
            Log.w(TAG, "evict failed", e);
        }
    }

    /** Drops clips from categories the owner excluded, so stale picks never come back. */
    static void purgeExcluded(Context c) {
        try {
            JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < meta.length(); i++) {
                JSONObject o = meta.getJSONObject(i);
                String key = o.optString("key");
                if (WallperCatalogParse.Clip.excluded(o.optString("category"))) {
                    Log.w(TAG, "purgeExcluded removes " + key + " (" + o.optString("category") + ")");
                    videoFile(c, key).delete();
                    posterFile(c, key).delete();
                    if (key.equals(prefs(c).getString("current_key", null))) {
                        deleteCurrent(c);
                    }
                } else {
                    kept.put(o);
                }
            }
            prefs(c).edit().putString("meta", kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "purge failed", e);
        }
    }
}
