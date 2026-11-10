package com.techgenius.tvoslauncher;

/**
 * Which media session the now-playing pill follows. Pure Java on purpose: the preference order is
 * small but easy to get subtly wrong, so it is kept free of Android types and tested on the JVM
 * (work/SessionPickTest.java) instead of only on a device.
 *
 * Order: a playing session beats a paused one; among playing sessions Spotify first, then
 * Bitchord (package name may carry a suffix, so it is matched by substring), then anything else.
 * The session already on screen wins ties, so the pill never flickers between equals.
 */
public final class SessionPick {

    public static final class Candidate {
        public final String pkg;
        public final boolean playing;
        public final boolean hasMetadata;

        public Candidate(String pkg, boolean playing, boolean hasMetadata) {
            this.pkg = pkg == null ? "" : pkg;
            this.playing = playing;
            this.hasMetadata = hasMetadata;
        }
    }

    private SessionPick() {
    }

    public static boolean isSpotify(String pkg) {
        return "com.spotify.tv.android".equals(pkg);
    }

    public static boolean isBitchord(String pkg) {
        return pkg != null && pkg.toLowerCase(java.util.Locale.ROOT).contains("bitchord");
    }

    public static int score(Candidate c) {
        if (!c.hasMetadata) return -1;
        int base = c.playing ? 100 : 0;
        if (isSpotify(c.pkg)) base += 30;
        else if (isBitchord(c.pkg)) base += 20;
        return base;
    }

    /** Index of the session to follow, or -1 when nothing qualifies. */
    public static int pick(Candidate[] sessions, int current) {
        int best = -1, bestScore = -1;
        for (int i = 0; i < sessions.length; i++) {
            int s = score(sessions[i]);
            if (s > bestScore || (s == bestScore && i == current && s >= 0)) {
                best = i;
                bestScore = s;
            }
        }
        return best;
    }
}
