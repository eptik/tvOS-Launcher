# Plan — Shield stabilization + video-wallpaper polish (2026-11-11)

Owner context: daily-driver issues reported on the NVIDIA Shield (192.168.1.72); the AF8
(192.168.1.73) is stable (60 fps verified, all clips 1080p). Every fix lands on
`eptik/tvOS-Launcher` via commits; verification is passive (logs + screenshots) while the
owner uses the TV.

## P0 — shipped this round (build 03748a9b + follow-up)
1. **Giant glass clock shrank** — `baseClockSizeU *= 0.6` in SceneView (owner: "reduce the
   hourglass by a lot"); clock stays top-anchored and centred, all styles.
2. **Clock no longer disappears during video playback** — drawScene no longer early-returns on
   a null backdrop; only the backdrop draw is skipped, clock/greeting/shade keep painting.
3. **Next-wallpaper no longer flashes a static poster** — startVideoWallpaper now replaces the
   running player in place (stop inside start) instead of guarding; skip/rotation swap clips
   with the still hidden throughout.
4. **Widget glass follows the current clip** — in video mode the glass bakes from the clip's
   poster while the backdrop stays the live surface (was: glass kept the previous scene's bake).
5. **Bitchord widget returns** — NowPlaying queries sessions through MediaWatchService (the
   enabled notification listener, whose own queries are permitted) instead of the
   activity-side call that `MediaSessionService.enforceMediaPermissions` rejects on the
   Shield; MediaWatchService pushes refreshes on session changes.

## P1 — verify passively, no device input
6. **"Redbull F1 2026 RB22" (87f59643, 44.5 MB)** — registered in the rotation meta (12 clips).
   Watch a full rotation pass (rotate=90 s): if the watchdog stalls/skips it, pull the file and
   check integrity; re-transcode or re-download as needed.
7. **"Always the same videos"** — pool is now 12 clips at rotate=90; confirm by watching
   `current_name` change across two cycles before concluding anything is still wrong.
8. **TizenTube Cobalt crash capture** — one clean launch played video on the Shield; no crash
   entries exist. Owner to reproduce; then read `logcat -b crash` for the trace.

## P2 — optional polish, owner's call
9. Re-encode clips at 60 fps (or drive TextureView invalidation from SurfaceTexture) to remove
   3:2 pulldown judder on 24–30 fps clips.
10. AF8 GPU headroom: reduce full-screen overdraw / use hardware bitmaps for the glass layers
    (HWUI p99 23 ms, 8 % janky — interaction roughness only).
11. AF8 pipeline note: any clip picked on the AF8 still arrives 4K and stalls its decoder — the
    Mac-side transcode + `run-as` push remains the bridge, or an on-device transcode step later.

## Environment facts (already fixed, for the record)
- Shield: setup_done written, HOME role + preferred = tvOS launcher, stock Google launcher
  disabled again (my earlier set-home call had re-enabled it — that is what "the TV shows its
  own Android TV" was), `tvrecommendations` disabled (crash loop source).
- Screensaver (dream-on-sleep) disabled on both TVs.
- All 8 AF8 clips are 1080p MD5-verified; the Shield's cache holds 12 clips (some 4K — the
  Shield decodes 4K natively).

## Gate ledger
`GATES-media-widget.md` round 5: P0 items 1–5 verified by build + device evidence (clock
visible over video — owner-confirmed; frame diffs; session bridge exercised on next Bitchord
playback); P1 items recorded as pending passive checks.
