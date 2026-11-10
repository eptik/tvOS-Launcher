package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Storage for the chosen video-wallpaper clips. Stub on purpose for now: the picker and the
 * download path land together (plan Tasks 3-5), and until then the video_wall scene falls back
 * to the standard still wallpaper instead of a black screen.
 */
final class VideoCache {

    private VideoCache() {
    }

    /**
     * Poster frame of the clip the owner picked, scaled for the panel, or null when no clip is
     * chosen yet — the scene then uses its asset fallback so the screen is never black.
     */
    static Bitmap currentPoster(Context c, int w, int h) {
        return null;   // Task 4: decode files/videos/<current>.jpg
    }

    /** Whether a clip has been picked and downloaded. */
    static boolean hasCurrent(Context c) {
        return false;
    }
}
