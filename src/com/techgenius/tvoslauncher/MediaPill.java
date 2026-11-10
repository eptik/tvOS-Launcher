package com.techgenius.tvoslauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.animation.DecelerateInterpolator;

/**
 * The now-playing pill: a small glass island under the clock with round artwork inside — no app
 * icon — plus the track title and play/pause and next glyphs. Display-only except for OK, which
 * toggles playback; the remote's media keys work everywhere anyway, so the pill never traps
 * D-pad navigation on its way to the dock.
 */
public class MediaPill extends View {

    public interface Host {
        void onToggle();
        void onNext();
    }

    private static final long FADE_MS = 180;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path path = new Path();

    private NowPlaying.Track track;
    private Host host;
    private float pressed;      // 0..1, drawn as a darker fill
    private ValueAnimator pressAnim;

    private float density;
    private int artDiameter, heightPx, pad;
    private boolean focused;

    public MediaPill(Context c) {
        super(c);
        init();
    }

    public MediaPill(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        heightPx = Math.round(40 * density);
        artDiameter = Math.round(30 * density);
        pad = Math.round(5 * density);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x26FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1, Math.round(density)));
        stroke.setColor(0x40FFFFFF);
        title.setColor(0xF2FFFFFF);
        title.setTextSize(12 * density);
        title.setFakeBoldText(true);
        glyph.setColor(0xFFFFFFFF);
        glyph.setStyle(Paint.Style.FILL);

        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        setWillNotDraw(false);
        setOnFocusChangeListener((v, f) -> {
            focused = f;
            stroke.setColor(f ? 0x8CFFFFFF : 0x40FFFFFF);
            invalidate();                    // focus lands on this frame, not after an animation
        });
        setOnClickListener(v -> {
            if (host != null) host.onToggle();
        });
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** Called on the main thread whenever the track or its play state changes. */
    public void setTrack(NowPlaying.Track t) {
        track = t;
        int target = t == null ? 0 : 255;
        if (getAlpha() > 0.99f && target == 255) {
            invalidate();
            return;
        }
        boolean reduced = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        if (reduced) {
            setAlpha(t == null ? 0f : 1f);
            setVisibility(t == null ? View.GONE : View.VISIBLE);
            return;
        }
        animate().alpha(target / 255f).setDuration(FADE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        setVisibility(t == null ? View.GONE : View.VISIBLE);
                    }
                }).start();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int textW = track == null ? 0
                : (int) Math.min(210 * density, title.measureText(clip(track.title)) + 8 * density);
        int width = pad + artDiameter + Math.round(6 * density) + textW
                + Math.round(10 * density) + 2 * (artDiameter / 2) + pad;
        setMeasuredDimension(Math.max(width, heightPx * 3), heightPx);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 25 ? s.substring(0, 25) + "…" : s;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bounds.set(0, 0, getWidth(), getHeight());
        float r = getHeight() / 2f;
        canvas.drawRoundRect(bounds, r, r, fill);
        if (pressed > 0f) {
            Paint p = new Paint(fill);
            p.setAlpha(Math.round(40 * pressed));
            canvas.drawRoundRect(bounds, r, r, p);
        }
        canvas.drawRoundRect(bounds, r, r, stroke);

        // round artwork, left inside the pill
        float cx = pad + artDiameter / 2f, cy = getHeight() / 2f;
        if (track != null && track.art != null) {
            canvas.save();
            path.reset();
            path.addCircle(cx, cy, artDiameter / 2f, Path.Direction.CW);
            canvas.clipPath(path);
            float scale = Math.max(artDiameter / (float) track.art.getWidth(),
                    artDiameter / (float) track.art.getHeight());
            float w = track.art.getWidth() * scale, h2 = track.art.getHeight() * scale;
            canvas.drawBitmap(track.art, cx - w / 2f, cy - h2 / 2f, artPaint);
            canvas.restore();
        } else {
            Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
            disc.setColor(0x33FFFFFF);
            canvas.drawCircle(cx, cy, artDiameter / 2f, disc);
        }

        // title
        if (track != null) {
            String text = clip(track.title);
            float tx = pad + artDiameter + 6 * density;
            float ty = getHeight() / 2f - (title.descent() + title.ascent()) / 2f;
            canvas.drawText(text, tx, ty, title);

            // play/pause + next glyphs, right-aligned
            float gx = getWidth() - pad - artDiameter / 2f;
            drawNext(canvas, gx, cy);
            float px = gx - artDiameter - 4 * density;
            if (track.playing) drawPause(canvas, px, cy);
            else drawPlay(canvas, px, cy);
        }
    }

    private void drawPlay(Canvas c, float cx, float cy) {
        float s = 4.5f * density;
        path.reset();
        path.moveTo(cx - s * 0.8f, cy - s);
        path.lineTo(cx + s, cy);
        path.lineTo(cx - s * 0.8f, cy + s);
        path.close();
        c.drawPath(path, glyph);
    }

    private void drawPause(Canvas c, float cx, float cy) {
        float w = 1.6f * density, h = 4.5f * density;
        c.drawRoundRect(new RectF(cx - w * 1.6f, cy - h, cx - w * 1.6f + w, cy + h), w / 2, w / 2, glyph);
        c.drawRoundRect(new RectF(cx + w * 0.6f, cy - h, cx + w * 1.6f, cy + h), w / 2, w / 2, glyph);
    }

    private void drawNext(Canvas c, float cx, float cy) {
        float s = 3.6f * density;
        path.reset();
        path.moveTo(cx - s * 1.6f, cy - s);
        path.lineTo(cx + s * 0.2f, cy);
        path.lineTo(cx - s * 1.6f, cy + s);
        path.close();
        c.drawPath(path, glyph);
        c.drawRect(cx + s * 0.4f, cy - s, cx + s * 0.9f, cy + s, glyph);
    }
}
