import com.techgenius.tvoslauncher.SessionPick;

/** JVM test for the pill's session preference. Run: java -cp build/test SessionPickTest */
public class SessionPickTest {

    static int failures;

    static void eq(String what, int got, int want) {
        if (got != want) {
            failures++;
            System.out.println("FAIL " + what + ": got " + got + " want " + want);
        } else {
            System.out.println("ok   " + what);
        }
    }

    static SessionPick.Candidate c(String p, boolean playing, boolean meta) {
        return new SessionPick.Candidate(p, playing, meta);
    }

    public static void main(String[] args) {
        // Nuvio paused, Spotify and Bitchord both playing: Bitchord is preferred? No — Spotify first.
        eq("spotify beats bitchord while both play",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.nuvio.tv", false, true), c("com.spotify.tv.android", true, true),
                        c("com.bitchord.app", true, true)}, -1), 1);

        // Bitchord playing alone with Spotify paused: Bitchord wins.
        eq("bitchord beats paused spotify",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.spotify.tv.android", false, true), c("com.bitchord.app", true, true)}, -1), 1);

        // Nothing playing: a paused session with metadata still shows, Spotify first.
        eq("paused spotify still selected when nothing plays",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.nuvio.tv", false, true), c("com.spotify.tv.android", false, true)}, -1), 1);

        // Sessions without metadata never qualify.
        eq("no metadata is skipped",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.spotify.tv.android", true, false), c("com.other", true, true)}, -1), 1);

        // Empty world.
        eq("empty is -1", SessionPick.pick(new SessionPick.Candidate[]{}, -1), -1);

        // Stability: equal scores keep the session already on screen.
        eq("equal score keeps current",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.a", true, true), c("com.b", true, true)}, 1), 1);

        // Bitchord matched by substring (fork/package suffix).
        eq("bitchord substring match",
                SessionPick.pick(new SessionPick.Candidate[]{
                        c("com.other", true, true), c("tv.bitchord.plus", true, true)}, -1), 1);

        if (failures > 0) {
            System.out.println("SessionPickTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("SessionPickTest: all green");
    }
}
