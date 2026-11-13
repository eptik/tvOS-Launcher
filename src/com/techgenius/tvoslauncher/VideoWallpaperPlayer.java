package com.techgenius.tvoslauncher;

import android.graphics.SurfaceTexture;

/** Playback contract for the video wallpaper; swap the engine without touching the launcher. */
interface VideoWallpaperPlayer {

    interface Listener {
        /** Called when the decoder is on screen — the moment the poster still can be hidden. */
        void onRenderingStarted();

        /** Any failure: the caller must fall back to the still wallpaper. */
        void onError(Exception e);
    }

    void setListener(Listener listener);

    void start(android.view.Surface surface, String path, boolean loop);

    /** Playback position in ms, or -1 when unknown — the stall watchdog reads this. */
    int positionMs();

    void stop();

    boolean playing();
}

/** Platform engine: MediaPlayer renders straight into the surface, the decoder scales a 4K master
 *  down to the panel's native size, and the app does zero per-frame GPU work. */
class MediaPlayerWallpaper implements VideoWallpaperPlayer {

    private android.media.MediaPlayer player;
    private Listener listener;

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private boolean stopping;
    private android.view.Surface surface;

    @Override
    public void start(android.view.Surface surface, String path, boolean loop) {
        // A destroyed holder surface (activity paused while the SurfaceView released) makes
        // prepare* throw IllegalStateException — wait for surfaceCreated instead.
        if (surface == null || !surface.isValid()) {
            android.util.Log.i("VideoWallpaper", "start skipped — surface not valid yet");
            return;
        }
        stopping = false;   // the next player owns error reporting again
        stop();
        try {
            // Buffer size is already set to the display size by MainActivity's
            // SurfaceTextureListener; do not override it here (a 4K panel wants native buffers).
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.setSurface(surface);
            player.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            player.setVolume(0f, 0f);                       // a wallpaper is silent
            player.setLooping(loop);
            player.setOnInfoListener((mp, what, extra) -> {
                android.util.Log.i("VideoWallpaper", "info what=" + what + " at "
                        + android.os.SystemClock.elapsedRealtime() + " ms");
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        && listener != null) {
                    listener.onRenderingStarted();          // first frame is on screen
                }
                return false;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                // stop() makes some decoders emit onError(1) asynchronously — during teardown
                // that is teardown noise, not a playback failure.
                if (stopping) return true;
                if (listener != null) listener.onError(new Exception("decoder error " + what));
                return true;
            });
            // Defense in depth: some decoders complete instead of looping; the wallpaper must
            // never sit frozen on the last frame until the watchdog notices. And a clip that
            // completes again within 3 s is a truncated head fragment looping itself — treat
            // it as a broken file, not a wallpaper.
            final long[] lastCompletion = {0L};
            player.setOnCompletionListener(mp -> {
                android.util.Log.i("VideoWallpaper", "completed at " + mp.getCurrentPosition() + " ms");
                if (!loop) return;
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastCompletion[0] > 0 && now - lastCompletion[0] < 3000L) {
                    if (listener != null) {
                        listener.onError(new Exception("clip loops under 3s — truncated file"));
                    }
                    return;
                }
                lastCompletion[0] = now;
                mp.seekTo(0);
                mp.start();
            });
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                android.util.Log.i("VideoWallpaper", "prepared+started at "
                        + android.os.SystemClock.elapsedRealtime() + " ms");
            });
            android.util.Log.i("VideoWallpaper", "MediaPlayer started");
        } catch (Exception e) {
            stop();
            if (listener != null) listener.onError(e);
        }
    }

    @Override
    public int positionMs() {
        try {
            return player == null ? -1 : player.getCurrentPosition();
        } catch (Exception e) {
            return -1;
        }
    }

    public void stop() {
        // Stays true until the next start(): the error event lands after stop() returns.
        stopping = true;
        if (player != null) {
            // stop() throws in the Preparing state; release() alone is valid from any state.
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        // The SurfaceView's surface is owned by the compositor — never release it here.
        surface = null;
    }

    @Override
    public boolean playing() {
        return player != null;
    }
}
