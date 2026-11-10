package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Now Playing popover, opened from the SceneHome media chip. Same family as the weather
 * panel: fixed size, LiquidGlass skin set by the host, sharp content, transport on glass.
 *
 * Layout: round artwork + title/artist/app caption, a thin live progress bar, and two transport
 * buttons (play/pause, next). The progress bar ticks from the live session, not from snapshots.
 */
public class NowPlayingPanel extends LinearLayout {

    static final float W = 320, H = 232;   // dp, like WeatherView's constants

    public interface Host {
        void onPrevious();

        void onToggle();

        void onNext();
    }

    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final TextView app;
    private final android.widget.FrameLayout bar;
    private final View barFill;
    private final ImageView prevBtn;
    private final ImageView toggleBtn;
    private final ImageView nextBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private NowPlaying.Track track;
    private Host host;
    private int glyphPx;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    public NowPlayingPanel(Context c) {
        this(c, null);
    }

    public NowPlayingPanel(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
        int pad = dp(18);
        setPadding(pad, pad, pad, pad);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        glyphPx = dp(16);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        art = new ImageView(c);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(84), dp(84));
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        art.setClipToOutline(true);
        head.addView(art, alp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(14);
        col.setLayoutParams(clp);

        title = new TextView(c);
        title.setTypeface(Ui.font(c, "semibold"));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);

        artist = new TextView(c);
        artist.setTypeface(Ui.font(c, "regular"));
        artist.setTextColor(0x99FFFFFF);
        artist.setTextSize(12);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(4), 0, 0);
        col.addView(artist);

        app = new TextView(c);
        app.setTypeface(Ui.font(c, "medium"));
        app.setTextColor(0x66FFFFFF);
        app.setTextSize(10);
        app.setAllCaps(true);
        app.setLetterSpacing(0.08f);
        app.setPadding(0, dp(6), 0, 0);
        col.addView(app);
        head.addView(col);

        bar = new android.widget.FrameLayout(c);
        bar.setBackgroundResource(0);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(dp(2));
        trackBg.setColor(0x26FFFFFF);
        bar.setBackground(trackBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(4));
        blp.topMargin = dp(18);
        addView(bar, blp);

        barFill = new View(c);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setCornerRadius(dp(2));
        fillBg.setColor(0xFFF4F4F6);
        barFill.setBackground(fillBg);
        android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                0, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        bar.addView(barFill, flp);

        LinearLayout transport = new LinearLayout(c);
        transport.setOrientation(HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        addView(transport, tlp);

        prevBtn = transportButton("prev");
        toggleBtn = transportButton("play");
        nextBtn = transportButton("next");
        LinearLayout.LayoutParams t0 = new LinearLayout.LayoutParams(dp(44), dp(44));
        t0.rightMargin = dp(14);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(dp(44), dp(44));
        t1.rightMargin = dp(14);
        transport.addView(prevBtn, t0);
        transport.addView(toggleBtn, t1);
        transport.addView(nextBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private ImageView transportButton(String kind) {
        final ImageView b = new ImageView(getContext());
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageBitmap(glyph(kind.equals("next") ? "next" : "play", 0xFFF4F4F6, glyphPx));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(null);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), 0xFFFFFFFF);
        // Inset so the 2 dp stroke sits inside the view bounds — flush to the edge it gets clipped
        // at 12 and 6 o'clock, which read as a broken ring.
        android.graphics.drawable.InsetDrawable ringInset =
                new android.graphics.drawable.InsetDrawable(ring, dp(2));
        b.setOnFocusChangeListener((v, has) -> {
            b.setForeground(has ? ringInset : null);
            b.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(180).start();
        });
        b.setOnClickListener(v -> {
            if (host == null) return;
            if (b == prevBtn) host.onPrevious();
            else if (b == toggleBtn) host.onToggle();
            else host.onNext();
        });
        return b;
    }

    /**
     * Interior D-pad routing. MainActivity routes keys here BEFORE the generic pickerMove()
     * fallback, whose single-cell grid logic would otherwise re-focus the panel and drop focus on
     * its first button — which is how RIGHT ended up on the button to the LEFT.
     */
    public boolean dispatch(android.view.KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == android.view.KeyEvent.ACTION_DOWN;
        View f = findFocus();
        if (f != prevBtn && f != toggleBtn && f != nextBtn) return false;
        if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && down) {
            if (f == prevBtn) toggleBtn.requestFocus();
            else if (f == toggleBtn) nextBtn.requestFocus();
            return true;                                   // edge: stay, do not wrap or escape
        }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT && down) {
            if (f == nextBtn) toggleBtn.requestFocus();
            else if (f == toggleBtn) prevBtn.requestFocus();
            return true;
        }
        if (k == android.view.KeyEvent.KEYCODE_DPAD_UP || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            return true;                                   // single-row popover: nothing above or below
        }
        return false;                                      // OK/BACK keep their default handling
    }

    /** Puts the remote focus on play/pause so the popover is immediately operable. */
    public void focusTransport() {
        toggleBtn.requestFocus();
        // The hierarchy may not be laid out when the popover opens; retry once it is.
        post(() -> {
            if (findFocus() == null) toggleBtn.requestFocus();
        });
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** Called on the main thread whenever the track or its play state changes. */
    public void setTrack(NowPlaying.Track t) {
        track = t;
        if (t == null) {
            title.setText("Nothing playing");
            artist.setText("");
            app.setText("");
            art.setImageDrawable(null);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(0x2EFFFFFF);
            art.setBackground(disc);
            toggleBtn.setImageBitmap(glyph("play", 0xFFF4F4F6, glyphPx));
            updateProgress();
            return;
        }
        if (t.art != null && !t.art.isRecycled()) {
            art.setImageBitmap(t.art);
            art.setBackground(null);
        }
        title.setText(t.title == null ? "" : t.title);
        artist.setText(t.artist == null ? "" : t.artist);
        app.setText(appName(t.pkg));
        toggleBtn.setImageBitmap(glyph(t.playing ? "pause" : "play", 0xFFF4F4F6, glyphPx));
        prevBtn.setImageBitmap(glyph("prev", 0xFFF4F4F6, glyphPx));
        updateProgress();
    }

    private static String appName(String pkg) {
        if (pkg == null) return "";
        if (pkg.contains("spotify")) return "Spotify";
        if (pkg.toLowerCase().contains("bitchord")) return "Bitchord";
        return "";
    }

    private void updateProgress() {
        // A media widget must never take the launcher down: wrong-LP mistakes here are logged, not thrown.
        try {
            long duration = track == null ? 0 : track.durationMs;
            long position = duration > 0 ? Math.min(duration, NowPlaying.positionMs()) : 0;
            float fraction = duration > 0 ? (float) position / duration : 0f;
            int width = Math.round(bar.getWidth() * fraction);
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) barFill.getLayoutParams();
            if (lp.width != width) {
                lp.width = width;
                barFill.setLayoutParams(lp);
            }
        } catch (Exception e) {
            android.util.Log.w("NowPlayingPanel", "progress update skipped", e);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticking = true;
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** Transport glyphs drawn once per (shape, colour): play, pause and next. */
    private Bitmap glyph(String kind, int color, int s) {
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        Path path = new Path();
        float u = s / 14f;
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
            case "prev":
                path.moveTo(11.5f * u, 2.5f * u);
                path.lineTo(4.5f * u, 7 * u);
                path.lineTo(11.5f * u, 11.5f * u);
                path.close();
                c.drawPath(path, p);
                c.drawRoundRect(new RectF(2.5f * u, 2.5f * u, 3.6f * u, 11.5f * u), u / 2, u / 2, p);
                break;
            default:
                path.moveTo(4 * u, 2.5f * u);
                path.lineTo(11.5f * u, 7 * u);
                path.lineTo(4 * u, 11.5f * u);
                path.close();
                c.drawPath(path, p);
        }
        return bmp;
    }
}
