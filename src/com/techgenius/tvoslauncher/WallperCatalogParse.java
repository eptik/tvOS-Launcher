package com.techgenius.tvoslauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-JRE parser for the wallper.app page payload. The launcher's page embeds its item list as a
 * doubly-escaped JSON string inside the rendered HTML, so the parser unescapes and then walks
 * items with bounded regexes — deliberately free of Android and JSON-library types so it can be
 * unit-tested on the JVM (work/WallperCatalogTest.java).
 *
 * Owner rules enforced by {@link #kept}: no anime, games, manga, pixelart or people; 4K only.
 */
public final class WallperCatalogParse {

    /** One wallpaper entry from the page. */
    public static final class Clip {
        public final String slug, name, category, previewUrl, videoKey;
        public final int width, height, likes, durationSec;

        public Clip(String slug, String name, String category, String previewUrl, String videoKey,
                    int width, int height, int likes, int durationSec) {
            this.slug = slug;
            this.name = name;
            this.category = category;
            this.previewUrl = previewUrl;
            this.videoKey = videoKey;
            this.width = width;
            this.height = height;
            this.likes = likes;
            this.durationSec = durationSec;
        }

        public boolean kept() {
            // Owner floor: 2560x1440 — anything lower never reaches the catalogue.
            return width >= 2560 && height >= 1400 && !excluded(category);
        }

        public static boolean excluded(String category) {
            if (category == null) return false;
            switch (category.toLowerCase(Locale.ROOT)) {
                case "anime":
                case "games":
                case "manga":
                case "pixelart":
                case "people":
                    return true;
                default:
                    return false;
            }
        }
    }

    private static final Pattern ITEM_START = Pattern.compile("\\{\"id\":\"");
    private static final Pattern P_SLUG = quoted("slug");
    private static final Pattern P_NAME = quoted("name");
    private static final Pattern P_CAT = quoted("categorySlug");
    private static final Pattern P_PREVIEW = quoted("previewImageKey");
    private static final Pattern P_KEY = Pattern.compile("\"fileKey\":\"([^\"]*\\.mp4)\"");
    private static final Pattern P_RES = Pattern.compile("\"resolution\":\"(\\d+)x(\\d+)\"");
    private static final Pattern P_LIKES = numeric("likes");
    private static final Pattern P_DUR = numeric("duration");

    private WallperCatalogParse() {
    }

    private static Pattern quoted(String field) {
        return Pattern.compile("\"" + field + "\":\"([^\"]*)\"");
    }

    private static Pattern numeric(String field) {
        return Pattern.compile("\"" + field + "\":(\\d+)");
    }

    public static List<Clip> parse(String html) {
        List<Clip> out = new ArrayList<>();
        if (html == null) return out;
        String text = unescape(html);
        Matcher start = ITEM_START.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (start.find()) starts.add(start.start());
        for (int i = 0; i < starts.size(); i++) {
            int begin = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            Clip c = clip(text.substring(begin, Math.min(end, begin + 4096)));
            if (c != null && out.stream().noneMatch(k -> k.slug.equals(c.slug))) out.add(c);
        }
        return out;
    }

    private static Clip clip(String chunk) {
        String slug = str(P_SLUG, chunk);
        String key = str(P_KEY, chunk);
        if (slug.isEmpty() || key.isEmpty()) return null;
        Matcher res = P_RES.matcher(chunk);
        if (!res.find()) return null;
        return new Clip(slug, str(P_NAME, chunk), str(P_CAT, chunk),
                "https://cdn.wallper.app/previews/" + str(P_PREVIEW, chunk),
                key, Integer.parseInt(res.group(1)), Integer.parseInt(res.group(2)),
                intOf(P_LIKES, chunk), intOf(P_DUR, chunk));
    }

    private static String str(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static int intOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** The payload is a JSON string inside a JSON string: two unescape passes reach plain quotes. */
    static String unescape(String html) {
        return html.replace("\\\\\\\"", "\"").replace("\\\\/", "/").replace("\\\"", "\"");
    }

    public static List<Clip> kept(List<Clip> all) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : all) if (c.kept()) out.add(c);
        out.sort((a, b) -> Integer.compare(b.likes, a.likes));
        return out;
    }
}
