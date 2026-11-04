package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nuvio account sign-in. Ported from Flix Launcher (MIT, HatriGt/flix-launcher) — Nuvio's TV app
 * uses the same three calls: ask for a code, poll it, exchange it once it is approved at
 * nuvio.tv/tv-login. This is the only way to read a user's real watch progress; the app itself
 * keeps that in private storage.
 *
 * <p>Tokens live in plain SharedPreferences. // shortcut: unencrypted refresh token on disk,
 * wrap in a Keystore-backed box if the launcher ever ships to other people.
 */
final class NuvioAccount {

    static final String API = "https://api.nuvio.tv";
    private static final String CONFIG_URL = "https://nuvio.tv/config.js";
    private static final String PREFS = "nuvio_account";
    private static final String TAG = "NuvioAccount";
    private static final Pattern ANON =
            Pattern.compile("supabaseAnonKey[\"']?\\s*[:=]\\s*[\"']([^\"']+)");

    /** Nuvio answered with an HTTP error, so callers can tell "rejected" from "offline". */
    static final class HttpError extends Exception {
        final int code;

        HttpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class Login {
        String code = "";
        String webUrl = "";
        String nonce = "";
        int pollSeconds = 3;
    }

    private NuvioAccount() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isSignedIn(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains("refresh_token") || p.contains("refresh_token_enc");
    }

    static String profileName(Context c) {
        return prefs(c).getString("profile_name", "");
    }

    static int profileId(Context c) {
        return prefs(c).getInt("profile_index", 1);
    }

    static void signOut(Context c) {
        String anon = prefs(c).getString("anon_key", null);
        prefs(c).edit().clear().apply();
        if (anon != null) prefs(c).edit().putString("anon_key", anon).apply();
    }

    // ---- Sign-in by code ----

    static Login startLogin(Context c) throws Exception {
        Login login = new Login();
        login.nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        JSONObject body = new JSONObject()
                .put("p_device_nonce", login.nonce)
                .put("p_redirect_base_url", "https://nuvio.tv/tv-login")
                .put("p_device_name", "tvOS Launcher on " + android.os.Build.MODEL);
        JSONArray rows = new JSONArray(rpc(c, "start_tv_login_session", body, true));
        JSONObject row = rows.getJSONObject(0);
        login.code = row.optString("code");
        login.webUrl = row.optString("web_url", "https://nuvio.tv/tv-login");
        login.pollSeconds = row.optInt("poll_interval_seconds", 3);
        return login;
    }

    /** "pending", "approved", "expired". */
    static String poll(Context c, Login login) throws Exception {
        JSONArray rows = new JSONArray(rpc(c, "poll_tv_login_session",
                new JSONObject().put("p_code", login.code).put("p_device_nonce", login.nonce), true));
        JSONObject row = rows.optJSONObject(0);
        return row == null ? "pending" : row.optString("status", "pending");
    }

    static void finishLogin(Context c, Login login) throws Exception {
        String body = request(c, API + "/functions/v1/tv-logins-exchange",
                new JSONObject().put("code", login.code).put("device_nonce", login.nonce).toString(),
                null, true);
        JSONObject json = new JSONObject(body);
        saveSession(c, json.optJSONObject("session") != null ? json.getJSONObject("session") : json);
    }

    // ---- Data ----

    /** Synchronized: Nuvio's refresh tokens are single-use, so two threads must never refresh together. */
    static synchronized String accessToken(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String token = p.getString("access_token", null);
        if (token != null && System.currentTimeMillis() / 1000 < p.getLong("expires_at", 0) - 60) {
            return token;
        }
        String refresh = p.getString("refresh_token", null);
        if (refresh == null) throw new IllegalStateException("Not signed in to Nuvio");
        String body;
        try {
            body = request(c, API + "/auth/v1/token?grant_type=refresh_token",
                    new JSONObject().put("refresh_token", refresh).toString(), null, true);
        } catch (HttpError e) {
            // Revoked or already used: the session is gone. A network failure is not an HttpError
            // and must leave the session alone.
            if (e.code == 400 || e.code == 401) signOut(c);
            throw e;
        }
        saveSession(c, new JSONObject(body));
        String fresh = prefs(c).getString("access_token", null);
        if (fresh == null) throw new IllegalStateException("Not signed in to Nuvio");
        return fresh;
    }

    static String rpc(Context c, String name, JSONObject body, boolean anonymous) throws Exception {
        if (anonymous) {
            return request(c, API + "/rest/v1/rpc/" + name, body.toString(), null, true);
        }
        return request(c, API + "/rest/v1/rpc/" + name, body.toString(), accessToken(c), false);
    }

    private static void saveSession(Context c, JSONObject session) throws Exception {
        String access = session.getString("access_token");
        long expiresAt = session.optLong("expires_at", 0);
        if (expiresAt <= 0) {
            expiresAt = System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600);
        }
        prefs(c).edit()
                .putString("access_token", access)
                .putString("refresh_token", session.getString("refresh_token"))
                .putLong("expires_at", expiresAt)
                .apply();
    }

    // ---- HTTP ----

    private static String request(Context c, String url, String body, String bearer, boolean anon)
            throws Exception {
        String token = anon ? anonKey(c) : bearer;
        try {
            return send(url, body, token, anon || bearer != null ? anonKey(c) : null);
        } catch (HttpError e) {
            // A cached anon key can go stale; drop it once and retry.
            if (e.code == 401 && anon) {
                prefs(c).edit().remove("anon_key").apply();
                return send(url, body, anonKey(c), anonKey(c));
            }
            throw e;
        }
    }

    /** Nuvio's public client key, read from its website so it stays current. */
    private static String anonKey(Context c) throws Exception {
        String cached = prefs(c).getString("anon_key", null);
        if (cached != null) return cached;
        String js = send(CONFIG_URL, null, null, null);
        Matcher m = ANON.matcher(js);
        if (!m.find()) throw new IllegalStateException("Couldn't read Nuvio's sign-in settings");
        prefs(c).edit().putString("anon_key", m.group(1)).apply();
        return m.group(1);
    }

    private static String send(String url, String body, String bearer, String apikey) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        if (apikey != null) conn.setRequestProperty("apikey", apikey);
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = stream == null ? "" : read(stream);
            if (code < 200 || code >= 300) {
                String message = "";
                try {
                    JSONObject o = new JSONObject(text);
                    message = o.optString("error_description");
                    if (message.isEmpty()) message = o.optString("msg");
                    if (message.isEmpty()) message = o.optString("message");
                } catch (Exception ignored) {
                }
                throw new HttpError(code, message.isEmpty() ? "Nuvio returned " + code : message);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "read failed", e);
            throw e;
        }
    }
}
