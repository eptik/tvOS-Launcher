# Impeccable critique — video-wallpaper integration (tvOS-Launcher fork, eptik)

Method: design review only (detector not applicable, native platform — Android TV)
Target: the fork's video-wallpaper, clock, media chip and wheel-sheet changes as rendered on
the Shield (192.168.1.72), live screenshots 2026-11-11 (work/shots/g4full.png, chip2.png).
Mode: Operate (app UI — scanability and native expectations outrank expression).

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Swaps are silent-by-design (good), but the active wallpaper engine is invisible |
| 2 | Match System / Real World | 4 | French locale, plain labels ("Skip to the next video clip"), 24h clock |
| 3 | User Control and Freedom | 3 | Skip only goes forward; no "previous clip"; loop/rotate/pause all present |
| 4 | Consistency and Standards | 3 | Wheel row mirrors Reddit's row; "Next clip" vs "Next wallpaper" label split |
| 5 | Error Prevention | 3 | Stall watchdog + poster temp-rename prevent the silent-static class |
| 6 | Recognition Rather Than Recall | 3 | Row subtitles state the action; current clip name not shown on home |
| 7 | Flexibility and Efficiency | 3 | 90s/5min/off + manual skip; short D-pad paths |
| 8 | Aesthetic and Minimalist Design | 2 | Clock reduced, but greeting+date+weather+caption still crowd the top-left |
| 9 | Error Recovery | 3 | Watchdog auto-skips, falls back to poster with a notice; listener self-repair |
| 10 | Help and Documentation | 2 | No in-app explanation of the wallpaper modes |

**Total 29/40** — solid Operate surface; the two 2s are the same root cause (mode identity
and text density at the top-left).

## Design Specificity Verdict
Authored, not category-interchangeable: the video-behind-glass composition, the luminance-adaptive
glass baked from the current clip's own poster, and the cosy-cinematic clip pool are specific to
this owner's brief. The chrome reuses upstream's exact recipes (chips, wheel rows, toasts), which
is why nothing reads as bolted-on. Deterministic scan: not applicable (native platform).

## What's Working
1. **The video-behind-glass architecture** — the clip plays on a surface under the view tree, so
   every logo, chip and panel composites above by construction; the scene's own backdrop simply
   declines to paint. This is why the mode feels native rather than a video window pasted in.
2. **The wheel row is upstream's own row** — same geometry, glass, focus treatment as the Reddit
   "Next wallpaper" row it sits beside. Consistency is the feature.
3. **Graceful degradation** — the stall watchdog auto-skips a clip the decoder cannot sustain and
   only then falls back to the poster with a named notice. The AF8's 4K hardware limit became a
   handled case, not a bug report.

## Priority Issues
1. **[P1] The active wallpaper engine is invisible.** The owner lost an hour to "static image"
   that was actually Reddit mode. Nothing on the home screen names the current engine.
   Fix: a one-line mode caption beside the wallpaper caption, or the chip tooltip.
   Suggested command: /impeccable clarify
2. **[P1] The top-left text stack fights the "minimalist screen" brief.** Greeting + date +
   weather + clock + caption all persist; the chips already fade on idle but the text does not.
   Fix: fade greeting/date after idle, keep clock + temperature. Suggested command: /impeccable distill
3. **[P2] No backward skip.** Twelve clips at 90 s means up to 18 minutes to return to one.
   Fix: long-press OK on the wheel row = previous clip. Suggested command: /impeccable shape
4. **[P2] A ~0.3–1 s poster flash during swaps.** The scene rebuild paints the new poster before
   the decoder's first frame. Fix: hold the last frame and crossfade. Suggested command: /impeccable animate
5. **[P3] Download failures are anonymous.** "Many failed to download" without a cause.
   Fix: surface the HTTP status/size cap and offer retry. Suggested command: /impeccable clarify

## Persona Red Flags
**Alex (Power User, D-pad)**: mode state invisible on the home screen; "previous clip" missing;
mode switching lives two settings deep. Would keep ADB open out of necessity — that is a failure
for this persona.
**Sam (10-foot/contrast)**: the scrim protects the dock, but on bright clips the clock's frost
can approach wash-out; the luminance bake mostly covers it. Watch contrast on a white-sky clip.
**Robin (owner — cosy/cinematic, minimalist-by-request)**: the video pool and rotation cadence
deliver the ambience; the greeting stack and the caption are the remaining noise.

## Minor Observations
- "Next clip" (Control Panel) vs "Next wallpaper" (wheel) — same action, two labels.
- 24–30 fps clips on a 60 Hz panel give 3:2 judder (encode-side, not UI).
- The clock at 0.6× measures 29% of panel width; 0.5× is one line if "a lot" meant smaller still.

## Questions to Consider
- What if the clock were the only chrome at idle — greeting and date fading out after 10 s?
- What if each swap showed the clip's name for 3 s — a "now showing" moment instead of a caption?
- What would a confident version of the mode indicator look like — one word, or none?

Trend: first run for this target, no prior snapshots.
(Snapshot persistence note: the impeccable launcher is not installed here; this file is the
hand-written archive.)
