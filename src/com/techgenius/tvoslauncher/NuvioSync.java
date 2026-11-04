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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Fills Continue Watching from the user's Nuvio account.
 *
 * The row itself is MediaWatchService's list, so this only has to write the same entries into
 * that store — the poster, progress bar and deep-link handling already exist. Nuvio's progress
 * rows carry ids and positions but no titles, so each one is named through Cinemeta.
 */
final class NuvioSync {

    private static final String TAG = "NuvioSync";
    private static final String NUVIO = "com.nuvio.tv";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/meta/%s/%s.json";
    private static final int MAX_ROWS = 12;
    private static final long LAST_SYNC_KEY_MS = 5 * 60_000L;

    private NuvioSync() {
    }

    /** Bumped when the stored shape changes; a stale cache re-syncs once instead of waiting. */
    private static final int SYNC_SCHEMA = 2;

    static boolean due(Context c) {
        if (NuvioAccount.prefs(c).getInt("sync_schema", 0) < SYNC_SCHEMA) return true;
        long last = NuvioAccount.prefs(c).getLong("last_sync", 0);
        return System.currentTimeMillis() - last > LAST_SYNC_KEY_MS;
    }

    /** Pulls the profile's watch progress and rewrites Nuvio's entries in Continue Watching. */
    static void refresh(Context c) {
        refresh(c, null);
    }

    static void refresh(Context c, Runnable done) {
        if (!NuvioAccount.isSignedIn(c)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                int profile = ensureProfile(app);
                JSONArray rows = new JSONArray(NuvioAccount.rpc(app, "sync_pull_watch_progress",
                        new JSONObject().put("p_profile_id", profile), false));

                Map<String, JSONObject> latest = new LinkedHashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("content_id");
                    if (id.isEmpty()) continue;
                    JSONObject prev = latest.get(id);
                    if (prev == null || r.optLong("last_watched") > prev.optLong("last_watched")) {
                        latest.put(id, r);
                    }
                }

                List<JSONObject> picked = new ArrayList<>();
                for (JSONObject r : latest.values()) {
                    long duration = r.optLong("duration");
                    long position = r.optLong("position");
                    if (duration <= 0) continue;
                    if ((float) position / duration >= 0.95f) continue;   // finished
                    picked.add(r);
                }
                Collections.sort(picked, (a, b) ->
                        Long.compare(b.optLong("last_watched"), a.optLong("last_watched")));
                if (picked.size() > MAX_ROWS) picked = picked.subList(0, MAX_ROWS);

                ExecutorService pool = Executors.newFixedThreadPool(4);
                List<Future<JSONObject>> metas = new ArrayList<>();
                for (JSONObject r : picked) {
                    String type = r.optString("content_type", "movie");
                    String id = r.optString("content_id");
                    metas.add(pool.submit(() -> meta(type, id)));
                }
                pool.shutdown();
                pool.awaitTermination(8, TimeUnit.SECONDS);
                pool.shutdownNow();

                JSONArray mine = new JSONArray();
                for (int i = 0; i < picked.size(); i++) {
                    JSONObject r = picked.get(i);
                    JSONObject meta = null;
                    try {
                        meta = metas.get(i).get(2, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                    JSONObject entry = entry(r, meta);
                    if (entry != null) mine.put(entry);
                }

                // Keep MediaWatchService's own entries for every other app, put Nuvio's on top.
                JSONArray out = new JSONArray();
                for (int i = 0; i < mine.length(); i++) out.put(mine.get(i));
                JSONArray old = MediaWatchService.Store.read(app);
                for (int i = 0; i < old.length() && out.length() < MediaWatchService.Store.MAX; i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o == null || NUVIO.equals(o.optString("pkg"))) continue;
                    out.put(o);
                }
                MediaWatchService.Store.write(app, out);
                NuvioAccount.prefs(app).edit()
                        .putLong("last_sync", System.currentTimeMillis())
                        .putInt("sync_schema", SYNC_SCHEMA)
                        .apply();
                Log.i(TAG, "synced " + mine.length() + " Nuvio titles");
                if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
            } catch (Exception e) {
                Log.w(TAG, "sync failed", e);
            }
        }, "nuvio-sync").start();
    }

    private static JSONObject entry(JSONObject r, JSONObject meta) {
        String id = r.optString("content_id");
        String type = r.optString("content_type", "movie");
        if (id.isEmpty()) return null;
        long duration = r.optLong("duration");
        long position = r.optLong("position");
        int season = r.optInt("season", 0);
        int episode = r.optInt("episode", 0);

        String name = meta == null ? "" : meta.optString("name");
        if (name.isEmpty() || "null".equals(name)) name = id;
        // `art` is the full-screen hero still, not a card thumbnail: a 2:3 poster here is what made
        // the home screen look like a zoomed, blurry crop. Metahub's background is native 1920x1080.
        String art = meta == null ? "" : meta.optString("background");
        if ("null".equals(art)) art = "";
        if (art.isEmpty() && meta != null) art = meta.optString("poster");
        if ("null".equals(art)) art = "";
        if (art.isEmpty()) art = "https://images.metahub.space/background/medium/" + id + "/img.jpg";

        String sub = type.equals("series") && season > 0 ? "S" + season + " E" + episode : "";
        try {
            return new JSONObject()
                    .put("key", NUVIO + "|" + id)
                    .put("pkg", NUVIO)
                    .put("title", name)
                    .put("sub", sub)
                    .put("art", art)
                    .put("pos", position)
                    .put("dur", duration)
                    .put("ts", System.currentTimeMillis())
                    .put("link", "nuvio://detail/" + type + "/" + id);
        } catch (Exception e) {
            return null;
        }
    }

    private static int ensureProfile(Context c) throws Exception {
        int stored = NuvioAccount.profileId(c);
        JSONArray profiles = new JSONArray(NuvioAccount.rpc(c, "sync_pull_profiles", new JSONObject(), false));
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.getJSONObject(i);
            if (p.optInt("id", p.optInt("profile_id", -1)) == stored) {
                NuvioAccount.prefs(c).edit().putString("profile_name", p.optString("name")).apply();
                return stored;
            }
        }
        JSONObject first = profiles.optJSONObject(0);
        if (first == null) return stored;
        int id = first.optInt("id", first.optInt("profile_id", 1));
        NuvioAccount.prefs(c).edit()
                .putInt("profile_index", id)
                .putString("profile_name", first.optString("name"))
                .apply();
        return id;
    }

    /** Cinemeta gives a title and poster for an IMDb id; it is the catalogue Stremio and Nuvio share. */
    private static JSONObject meta(String type, String id) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    String.format(CINEMETA, type, java.net.URLEncoder.encode(id, "UTF-8"))).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8")).optJSONObject("meta");
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
