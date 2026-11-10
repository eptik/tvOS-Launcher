package com.techgenius.tvoslauncher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The wallper.app "Most liked" catalogue, read the way a browser reads it: from the rendered page
 * (robots.txt allows / and disallows /api/ and /_next/, so the page is the only permitted source).
 * The page embeds its item list as a doubly-escaped JSON string, so the parser unescapes twice and
 * then walks plain-JSON objects.
 *
 * Owner rules: no anime, games, manga, pixelart or people; landscape 4K only.
 */
final class WallperCatalog {

    static final class Clip {
        final String slug, name, category, previewUrl, videoKey;
        final int width, height, likes, durationSec;

        Clip(String slug, String name, String category, String previewUrl, String videoKey,
             int width, int height, int likes, int durationSec) {
            this.slug = slug;
            this.name = name;
            this.category = category;
            this.previewUrl = previewUrl;
            this.videoKey = videoKey;
            this.width = width;
            this.height = height;
            this.likes = likes;
            this.durationSec = durationSec;
        }

        boolean kept() {
            return width >= 3840 && height >= 2100 && !excluded(category);
        }

        static boolean excluded(String category) {
            if (category == null) return false;
            switch (category.toLowerCase(Locale.ROOT)) {
                case "anime":
                case "games":
                case "manga":
                case "pixelart":
                case "people":
                    return true;
                default:
                    return false;
            }
        }
    }

    private static final String PAGE = "https://www.wallper.app/wallpapers?sort=Most+Liked";
    private static final String TAG = "WallperCatalog";
    private static final String PREFS = "wallper";
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private WallperCatalog() {
    }

    /** Pure text -> clips, no Android calls; covered by work/WallperCatalogTest.java. */
    static List<Clip> parse(String html) {
        List<Clip> out = new ArrayList<>();
        if (html == null) return out;
        String unescaped = html
                .replace("\\\\\\\"", "\"")     // \" -> " (JSON-in-JSON, first pass)
                .replace("\\\\/", "/")
                .replace("\\\"", "\"");        // second pass
        int from = 0;
        while (true) {
            int idAt = unescaped.indexOf("\"id\":\"", from);
            if (idAt < 0) break;
            int objEnd = unescaped.indexOf("}", idAt);
            if (objEnd < 0) break;
            String chunk = unescaped.substring(idAt, objEnd);
            from = objEnd;
            try {
                // The item is a flat object of string/number fields; rebuild it as real JSON.
                JSONObject o = new JSONObject("{" + chunk + "}");
                String fileKey = o.optString("fileKey");
                if (!fileKey.endsWith(".mp4")) continue;
                String res = o.optString("resolution");
                int x = res.indexOf('x');
                if (x <= 0) continue;
                Clip c = new Clip(
                        o.optString("slug"),
                        o.optString("name"),
                        o.optString("categorySlug"),
                        "https://cdn.wallper.app/previews/" + o.optString("previewImageKey"),
                        fileKey,
                        parseInt(res.substring(0, x)),
                        parseInt(res.substring(x + 1)),
                        o.optInt("likes"),
                        o.optInt("duration"));
                if (out.stream().noneMatch(k -> k.slug.equals(c.slug))) out.add(c);
            } catch (Exception ignored) {
                // A malformed chunk is skipped; the surrounding ones still parse.
            }
        }
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static List<Clip> kept(List<Clip> all) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : all) if (c.kept()) out.add(c);
        out.sort((a, b) -> Integer.compare(b.likes, a.likes));
        return out;
    }

    /** Cached catalogue (24 h). Network and parsing run off the main thread; callers get the cache. */
    static List<Clip> load(Context c) {
        List<Clip> cached = fromCache(c);
        if (cached != null) return cached;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                List<Clip> clips = kept(parse(fetch(PAGE)));
                JSONArray arr = new JSONArray();
                for (Clip k : clips) {
                    arr.put(new JSONObject()
                            .put("slug", k.slug).put("name", k.name).put("category", k.category)
                            .put("w", k.width).put("h", k.height).put("likes", k.likes)
                            .put("dur", k.durationSec).put("preview", k.previewUrl)
                            .put("key", k.videoKey));
                }
                prefs(app).edit().putString("catalog", arr.toString())
                        .putLong("fetched", System.currentTimeMillis()).apply();
                Log.i(TAG, "catalog " + clips.size());
            } catch (Exception e) {
                Log.w(TAG, "catalog fetch failed", e);
            }
        }, "wallper-catalog").start();
        return cached == null ? new ArrayList<>() : cached;
    }

    private static List<Clip> fromCache(Context c) {
        long fetched = prefs(c).getLong("fetched", 0);
        if (System.currentTimeMillis() - fetched > TTL_MS) return null;
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("catalog", "[]"));
            List<Clip> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Clip(o.optString("slug"), o.optString("name"), o.optString("category"),
                        o.optString("preview"), o.optString("key"), o.optInt("w"), o.optInt("h"),
                        o.optInt("likes"), o.optInt("dur")));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fetch(String page) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(page).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "text/html");
        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
