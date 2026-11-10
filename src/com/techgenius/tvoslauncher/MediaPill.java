package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The now-playing pill, built from the same parts as the top-bar chips: the SceneHome glass recipe
 * (42 dp capsule, 0x6614161C tint, lens 5, rim 0.45 — provided by MainActivity, which owns the
 * glass context), round artwork, Inter semibold title and transport glyphs. Focus draws the same
 * 2 dp white ring the chips use, instead of a white fill.
 *
 * OK toggles playback; next is a second tap target. Horizontal D-pad passes through so the dock
 * and shelf stay reachable.
 */
public class MediaPill extends LinearLayout {

    public interface Host {
        void onToggle();

        void onNext();
    }

    private static final long FADE_MS = 180;

    private final ImageView art;
    private final TextView title;
    private final ImageView toggle;
    private final ImageView next;

    private NowPlaying.Track track;
    private Host host;
    private int glyphPx;

    public MediaPill(Context c) {
        this(c, null);
    }

    public MediaPill(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        int pad = dp(11);
        setPadding(pad, 0, pad, 0);
        glyphPx = dp(16);

        art = new ImageView(c);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(28), dp(28));
        alp.rightMargin = dp(8);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        art.setClipToOutline(true);
        addView(art, alp);

        title = new TextView(c);
        title.setTypeface(Ui.font(c, "semibold"));
        title.setTextColor(0xF2FFFFFF);
        title.setTextSize(13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addView(title, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        toggle = new ImageView(c);
        next = new ImageView(c);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(16), dp(16));
        glp.leftMargin = dp(10);
        next.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        next.setScaleType(ImageView.ScaleType.CENTER);
        addView(toggle, glp);
        addView(next);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void setHost(Host host) {
        this.host = host;
        setOnClickListener(v -> {
            if (host != null) host.onToggle();
        });
        next.setOnClickListener(v -> {
            if (host != null) host.onNext();
        });
    }

    /** Called on the main thread whenever the track or its play state changes. */
    public void setTrack(NowPlaying.Track t) {
        track = t;
        boolean show = t != null;
        if (show) {
            if (t.art != null && !t.art.isRecycled()) {
                art.setImageBitmap(t.art);
                art.setBackground(null);
            } else {
                art.setImageBitmap(null);
                GradientDrawable disc = new GradientDrawable();
                disc.setShape(GradientDrawable.OVAL);
                disc.setColor(0x2EFFFFFF);
                art.setBackground(disc);
            }
            title.setText(t.title == null ? "" :
                    (t.title.length() > 25 ? t.title.substring(0, 25) + "…" : t.title));
            toggle.setImageBitmap(glyph(t.playing ? "pause" : "play", 0xFFF4F4F6));
            next.setImageBitmap(glyph("next", 0xFFF4F4F6));
        }
        boolean reduced = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        if (reduced) {
            setAlpha(show ? 1f : 0f);
            setVisibility(show ? VISIBLE : GONE);
            return;
        }
        animate().alpha(show ? 1f : 0f).setDuration(FADE_MS).withEndAction(
                () -> setVisibility(show ? VISIBLE : GONE)).start();
    }

    /** Transport glyphs drawn once per (shape, colour): play, pause and next. */
    private Bitmap glyph(String kind, int color) {
        int s = glyphPx;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        float u = s / 14f;   // design grid
        switch (kind) {
            case "pause":
                c.drawRoundRect(new RectF(3 * u, 2.5f * u, 3 * u + 2.2f * u, 11.5f * u), u, u, p);
                c.drawRoundRect(new RectF(8.8f * u, 2.5f * u, 8.8f * u + 2.2f * u, 11.5f * u), u, u, p);
                break;
            case "next":
                path.moveTo(2.5f * u, 2.5f * u);
                path.lineTo(9.5f * u, 7 * u);
                path.lineTo(2.5f * u, 11.5f * u);
                path.close();
                c.drawPath(path, p);
                c.drawRoundRect(new RectF(10.4f * u, 2.5f * u, 11.5f * u, 11.5f * u), u / 2, u / 2, p);
                break;
            default: // play
                path.moveTo(4 * u, 2.5f * u);
                path.lineTo(11.5f * u, 7 * u);
                path.lineTo(4 * u, 11.5f * u);
                path.close();
                c.drawPath(path, p);
        }
        return bmp;
    }
}
