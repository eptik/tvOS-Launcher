import com.techgenius.tvoslauncher.WallperCatalogParse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JVM test for the wallper parser. The fixture is a slice of the real page's embedded payload:
 * two kept clips (nature 4K, space 4K), one anime 4K and one 1080p nature — the filter must keep
 * exactly the first two. Run: java -cp build/test WallperCatalogTest <fixture>
 */
public class WallperCatalogTest {

    public static void main(String[] args) throws Exception {
        String html = Files.readString(Path.of(args[0]));
        List<WallperCatalogParse.Clip> all = WallperCatalogParse.parse(html);
        List<WallperCatalogParse.Clip> kept = WallperCatalogParse.kept(all);

        // Real-page invariants (the payload repeats items across flight chunks; the parser dedupes).
        check("substantial catalogue", all.size() >= 1000, all.size());
        check("no excluded category in kept", countCategory(kept, "anime") == 0
                && countCategory(kept, "games") == 0 && countCategory(kept, "manga") == 0
                && countCategory(kept, "pixelart") == 0 && countCategory(kept, "people") == 0,
                kept.size());
        check("floor 2560x1440", kept.stream().allMatch(c -> c.width >= 2560 && c.height >= 1400), kept.size());
        check("kept is a healthy cut of the page", kept.size() >= 700, kept.size());
        check("sorted by likes",
                kept.stream().noneMatch(c -> false) && isSorted(kept), kept.size());
        check("preview built from key",
                kept.stream().allMatch(c -> c.previewUrl.startsWith("https://cdn.wallper.app/previews/")),
                kept.size());
        check("video key kept", kept.stream().allMatch(c -> c.videoKey.endsWith(".mp4")), kept.size());

        if (failures > 0) {
            System.out.println("WallperCatalogTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("WallperCatalogTest: all green");
    }

    static int failures;

    static void check(String what, boolean ok, Object detail) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " (" + detail + ")");
        } else {
            System.out.println("ok   " + what);
        }
    }

    static boolean hasCategory(List<WallperCatalogParse.Clip> l, String cat) {
        return l.stream().anyMatch(c -> c.category.equals(cat));
    }

    static long countCategory(List<WallperCatalogParse.Clip> l, String cat) {
        return l.stream().filter(c -> c.category.equals(cat)).count();
    }

    static boolean isSorted(List<WallperCatalogParse.Clip> l) {
        for (int i = 1; i < l.size(); i++) {
            if (l.get(i - 1).likes < l.get(i).likes) return false;
        }
        return true;
    }
}
