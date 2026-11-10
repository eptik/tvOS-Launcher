package com.techgenius.tvoslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "what is playing right now" on the TV.
 *
 * <p>Fed by {@link NowPlayingService}, which is a notification listener and therefore allowed to read the
 * active {@link android.media.session.MediaSession}s. The launcher only reads this class; it never opens a
 * network connection or polls the session list.
 */
public final class NowPlaying {

    private static final String TAG = "MediaPill";

    public static final class Track {
        public final String title;
        public final String artist;
        public final String pkg;
        public final Bitmap art;
        public final boolean playing;
        public final long durationMs;

        Track(String title, String artist, String pkg, Bitmap art, boolean playing, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.pkg = pkg;
            this.art = art;
            this.playing = playing;
            this.durationMs = durationMs;
        }

        boolean sameAs(Track other) {
            if (other == null) return false;
            return eq(title, other.title) && eq(artist, other.artist) && eq(pkg, other.pkg)
                    && playing == other.playing && art == other.art;
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    public interface Listener {
        /** Called on the main thread whenever the current track or its play state changes. */
        void onNowPlaying(Track track);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static volatile Track current;
    private static MediaController controller;
    private static boolean callbackRegistered;
    private static ComponentName component;
    private static Context appContext;
    // Artwork + identity caches: Spotify pushes a fresh MediaMetadata on every state change, and decoding
    // (or swapping) the bitmap each time made the island's artwork flicker.
    private static Bitmap cachedArt;
    private static String cachedTrackKey;
    private static Track published;

    private NowPlaying() {
    }

    public static Track current() {
        return current;
    }

    /** Playback position in ms, read straight from the live controller (used for the thin progress bar). */
    public static long positionMs() {
        MediaController c = controller;
        if (c == null) return 0L;
        PlaybackState state = c.getPlaybackState();
        if (state == null) return 0L;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getPlaybackSpeed() > 0f) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        return Math.max(0L, pos);
    }

    public static void addListener(Listener listener) {
        if (listener == null || LISTENERS.contains(listener)) return;
        LISTENERS.add(listener);
        listener.onNowPlaying(current);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void attach(Context context) {
        appContext = context.getApplicationContext();
        // MediaWatchService is already the launcher's notification listener; reusing its component
        // means one permission instead of two.
        component = new ComponentName(context, MediaWatchService.class);
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            List<MediaController> sessions = manager.getActiveSessions(component);
            // Selection lives in SessionPick (pure Java, JVM-tested): playing beats paused, Spotify
            // beats Bitchord beats anything else, and the session already followed wins ties so the
            // pill never flickers between equals.
            int currentIndex = -1;
            SessionPick.Candidate[] candidates = new SessionPick.Candidate[sessions.size()];
            for (int i = 0; i < sessions.size(); i++) {
                MediaController c = sessions.get(i);
                if (controller != null && c.getSessionToken().equals(controller.getSessionToken())) {
                    currentIndex = i;
                }
                PlaybackState st = c.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                candidates[i] = new SessionPick.Candidate(c.getPackageName(), playing, c.getMetadata() != null);
            }
            int picked = SessionPick.pick(candidates, currentIndex);
            bindController(picked >= 0 ? sessions.get(picked) : null);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification listener access not granted yet", e);
        } catch (Exception e) {
            Log.w(TAG, "Could not read media sessions", e);
        }
    }

    private static void bindController(final MediaController next) {
        if (next == null) {
            controller = null;
            publish(null);
            return;
        }
        if (controller != null && next.getSessionToken().equals(controller.getSessionToken())) {
            publish(read(next));
            return;
        }
        if (controller != null && callbackRegistered) {
            try {
                controller.unregisterCallback(callback);
            } catch (Exception ignored) {
            }
        }
        controller = next;
        callbackRegistered = true;
        try {
            next.registerCallback(callback, MAIN);
        } catch (Exception e) {
            callbackRegistered = false;
        }
        publish(read(next));
    }

    private static final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish(read(controller));
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish(read(controller));
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            // Re-scan before clearing the island: a session is often re-created straight away.
            if (appContext != null) attach(appContext);
            if (controller == null) publish(null);
        }
    };

    private static Track read(MediaController c) {
        if (c == null) return null;
        MediaMetadata md = c.getMetadata();
        if (md == null) return null;
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (title == null) title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return null;

        PlaybackState state = c.getPlaybackState();
        boolean playing = state != null
                && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // Read the bitmap every time. MediaMetadata hands back the *same* Bitmap instance until the
        // metadata changes, so identity is a reliable "art changed" signal — and unlike a cache keyed on
        // title/artist it cannot pin the previous track's cover when Spotify updates title and artwork in
        // separate callbacks.
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null && md.getDescription() != null) art = md.getDescription().getIconBitmap();
        if (art == null) art = loadArtworkUri(c, md);

        String trackKey = c.getPackageName() + "|" + title + "|" + artist + "|" + duration;
        if (trackKey.equals(cachedTrackKey) && cachedArt != null) {
            // Spotify republishes the same cover as fresh Bitmap objects (640 px, 160 px, ...) several times a
            // second. Keep the best one and ignore the rest, or the disc is re-set continuously.
            if (art == null || art.getWidth() < cachedArt.getWidth() || sameImage(art, cachedArt)) {
                art = cachedArt;
            }
        }
        cachedArt = art;
        cachedTrackKey = trackKey;

        return new Track(title, artist, c.getPackageName(), art, playing, duration);
    }

    /** Cheap content check: same size and the same 16 sampled pixels means "same cover, new object". */
    static boolean sameImage(Bitmap a, Bitmap b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        int stepX = Math.max(1, a.getWidth() / 4);
        int stepY = Math.max(1, a.getHeight() / 4);
        for (int y = stepY / 2; y < a.getHeight(); y += stepY) {
            for (int x = stepX / 2; x < a.getWidth(); x += stepX) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
            }
        }
        return true;
    }

    private static Bitmap loadArtworkUri(MediaController c, MediaMetadata md) {
        try {
            Uri uri = md.getDescription() != null ? md.getDescription().getIconUri() : null;
            if (uri == null) uri = stringUri(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) uri = stringUri(md, MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) return null;
            if (appContext == null) return null;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if (scheme.equals("http") || scheme.equals("https")) {
                // Spotify TV hands an https cover, which ContentResolver cannot open.
                HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                try (InputStream in = conn.getInputStream()) {
                    return in == null ? null : BitmapFactory.decodeStream(in);
                } finally {
                    conn.disconnect();
                }
            }
            try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                return in == null ? null : BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Transport controls, used by the home-screen island. */
    public static void togglePlayPause() {
        MediaController c = controller;
        if (c == null) return;
        PlaybackState state = c.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (playing) c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    public static void skipNext() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().skipToNext();
    }

    public static void skipPrevious() {
        MediaController c = controller;
        if (c != null) c.getTransportControls().skipToPrevious();
    }

    private static Uri stringUri(MediaMetadata md, String key) {
        CharSequence value = md.getText(key);
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private static String text(MediaMetadata md, String key) {
        CharSequence value = md.getText(key);
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void publish(final Track track) {
        if (track == null && published == null) return;
        if (track != null && published != null
                && track.title.equals(published.title)
                && (track.artist == null ? published.artist == null : track.artist.equals(published.artist))
                && track.playing == published.playing
                && track.art == published.art) {
            current = track;
            return;                       // same song, same artwork, same state: nothing to redraw
        }
        current = track;
        published = track;
        dispatch();
    }

    /** Pushes the current state to listeners; called by the service on every session change. */
    static void dispatch() {
        final Track snapshot = current;
        MAIN.post(() -> {
            for (Listener l : new ArrayList<>(LISTENERS)) l.onNowPlaying(snapshot);
        });
    }

    /** Full refresh: re-read the session list, then notify listeners. */
    static void refresh(Context context) {
        attach(context);
        dispatch();
    }
}