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
    private static final long CAP_BYTES = 500L * 1024 * 1024;

    private VideoCache() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("video_wall", Context.MODE_PRIVATE);
    }

    static File videoFile(Context c, String key) {
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

    /** Cached clips that still exist on disk, oldest metadata first (rotation order). */
    static List<String[]> cachedClips(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray meta = new JSONArray(prefs(c).getString("meta", "[]"));
            for (int i = 0; i < meta.length(); i++) {
                JSONObject o = meta.getJSONObject(i);
                String key = o.optString("key");
                if (videoFile(c, key).exists()) {
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
        SharedPreferences p = prefs(c);
        p.edit().putString("current_key", key)
                .putLong("ts_" + key, System.currentTimeMillis()).apply();
        for (String[] m : cachedClips(c)) {
            if (m[0].equals(key)) {
                p.edit().putString("current_name", m[2]).putString("current_category", m[3]).apply();
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

    /** Keeps the videos directory under its cap, oldest first; the current clip is never evicted. */
    static void evict(Context c) {
        try {
            File dir = new File(c.getFilesDir(), "videos");
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) total += f.length();
            if (total <= CAP_BYTES) return;
            String currentKey = prefs(c).getString("current_key", "");
            List<File> sorted = new ArrayList<>();
            for (File f : files) if (f.getName().endsWith(".mp4")) sorted.add(f);
            sorted.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : sorted) {
                if (total <= CAP_BYTES) break;
                if (f.getName().equals(currentKey + ".mp4")) continue;
                long len = f.length();
                String key = f.getName().replace(".mp4", "");
                f.delete();
                posterFile(c, key).delete();
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
