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

    private static final String TAG = "WallperCatalog";
    private static final String PAGE = "https://www.wallper.app/wallpapers?sort=Most+Liked";
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private static final String PREFS = "wallper";
    /** Bumped when the parsed shape changes: stale caches re-fetch instead of serving old keys. */
    private static final int PARSE_SCHEMA = 2;

    private WallperCatalog() {
    }

    /** Pure text -> clips, no Android calls; covered by work/WallperCatalogTest.java. */
    static List<WallperCatalogParse.Clip> parse(String html) {
        List<WallperCatalogParse.Clip> out = new ArrayList<>();
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
                WallperCatalogParse.Clip c = new WallperCatalogParse.Clip(
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

    static List<WallperCatalogParse.Clip> kept(List<WallperCatalogParse.Clip> all) {
        List<WallperCatalogParse.Clip> out = new ArrayList<>();
        for (WallperCatalogParse.Clip c : all) if (c.kept()) out.add(c);
        out.sort((a, b) -> Integer.compare(b.likes, a.likes));
        return out;
    }

    /** Cached catalogue (24 h). Network and parsing run off the main thread; callers get the cache. */
    static List<WallperCatalogParse.Clip> load(Context c) {
        List<WallperCatalogParse.Clip> cached = fromCache(c);
        if (cached != null) return cached;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                List<WallperCatalogParse.Clip> clips = WallperCatalogParse.kept(WallperCatalogParse.parse(fetch(PAGE)));
                JSONArray arr = new JSONArray();
                for (WallperCatalogParse.Clip k : clips) {
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

    private static List<WallperCatalogParse.Clip> fromCache(Context c) {
        if (prefs(c).getInt("parse_schema", 0) < PARSE_SCHEMA) return null;
        long fetched = prefs(c).getLong("fetched", 0);
        if (System.currentTimeMillis() - fetched > TTL_MS) return null;
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("catalog", "[]"));
            List<WallperCatalogParse.Clip> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new WallperCatalogParse.Clip(o.optString("slug"), o.optString("name"), o.optString("category"),
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

    /** Callback for {@link #loadAsync}. */
    public interface CatalogCallback {
        void onCatalog(List<WallperCatalogParse.Clip> kept, long fetchedAtMs);
    }

    /**
     * Cached-first, never blocking: a fresh catalogue answers on the calling thread, a stale or
     * missing one refreshes in the background and answers once.
     */
    public static void loadAsync(Context c, CatalogCallback cb) {
        List<WallperCatalogParse.Clip> cached = fromCache(c);
        if (cached != null) {
            cb.onCatalog(WallperCatalogParse.kept(cached), prefs(c).getLong("fetched", 0));
            return;
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<WallperCatalogParse.Clip> out = new ArrayList<>();
            long at = 0;
            try {
                out = WallperCatalogParse.kept(WallperCatalogParse.parse(fetch(PAGE)));
                JSONArray arr = new JSONArray();
                for (WallperCatalogParse.Clip k : out) {
                    arr.put(new JSONObject()
                            .put("slug", k.slug).put("name", k.name).put("category", k.category)
                            .put("w", k.width).put("h", k.height).put("likes", k.likes)
                            .put("dur", k.durationSec).put("preview", k.previewUrl)
                            .put("key", k.videoKey));
                }
                at = System.currentTimeMillis();
                prefs(app).edit().putString("catalog", arr.toString())
                        .putLong("fetched", at).putInt("parse_schema", PARSE_SCHEMA).apply();
                Log.i(TAG, "catalog " + out.size());
            } catch (Exception e) {
                Log.w(TAG, "catalog fetch failed", e);
            }
            final List<WallperCatalogParse.Clip> result = out;
            final long atFinal = at;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onCatalog(result, atFinal));
        }, "wallper-catalog").start();
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
