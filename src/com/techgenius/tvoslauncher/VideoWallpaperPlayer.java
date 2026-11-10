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

    void start(SurfaceTexture texture, String path, boolean loop);

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

    @Override
    public void start(SurfaceTexture texture, String path, boolean loop) {
        stop();
        try {
            // Buffer size is already set to the display size by MainActivity's
            // SurfaceTextureListener; do not override it here (a 4K panel wants native buffers).
            player = new android.media.MediaPlayer();
            player.setDataSource(path);
            player.setSurface(new android.view.Surface(texture));
            player.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            player.setVolume(0f, 0f);                       // a wallpaper is silent
            player.setLooping(loop);
            player.setOnInfoListener((mp, what, extra) -> {
                android.util.Log.i("VideoWallpaper", "info what=" + what);
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                        && listener != null) {
                    listener.onRenderingStarted();          // first frame is on screen
                }
                return false;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (listener != null) listener.onError(new Exception("decoder error " + what));
                return true;
            });
            player.prepare();
            player.start();
            android.util.Log.i("VideoWallpaper", "MediaPlayer started");
        } catch (Exception e) {
            stop();
            if (listener != null) listener.onError(e);
        }
    }

    @Override
    public void stop() {
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
    }

    @Override
    public boolean playing() {
        return player != null;
    }
}
