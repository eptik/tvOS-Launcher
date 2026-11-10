# Media island, video wallpapers, upstream sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the fork onto upstream v2.1.5 (adopting its Nuvio dock popup), add a dynamic-island now-playing pill for Spotify/Bitchord, and add opt-in 4K video wallpapers harvested from wallper.app.

**Architecture:** The fork merges upstream/main (their `NuvioCloud`/`WatchPopup` replace our port entirely). Now-playing reuses our proven `NowPlaying` session poll behind a new glass pill in tvOS visual language. Video wallpapers ride a new `VideoWallpaper` module: catalog parsing (allowed HTML only), a browsable picker, a bounded cache, and a `MediaPlayer`+`SurfaceView` player behind a Settings toggle — behind an interface so VLC/ExoPlayer can replace it later.

**Tech Stack:** Pure Java, plain-SDK build (`build.sh`, no Gradle), platform `MediaPlayer` (zero new dependencies), `adb-shell` Python client for device work, Node scripts for runnable checks, scrapling (Mac-side only) for the wallper URL spike.

**Spec:** This file; grilling decisions recorded 2026-11-04 (see Decisions).

## Decisions (owner, 2026-11-04)

1. Adopt upstream `WatchPopup`/`NuvioCloud` wholesale; our Nuvio commits are dropped in the merge.
2. Video wallpaper engine: platform player first, engine behind an interface; enable/disable lives in Settings.
3. Wallper clips are chosen **manually on the TV** from a browsable list.
4. Content filter: exclude `anime`, `games`, `manga`, `pixelart`, `people`; keep only clips ≥ 3840×2160.
5. Now-playing: dynamic-island-style glass pill under the clock; **round artwork inside the pill**, no app icon.

## Global Constraints

- Pure Java, no Gradle; every change must build with `bash build.sh` (JDK 17, build-tools 34).
- No third-party runtime dependencies in this task (README claims zero; adding ExoPlayer/VLC is a separate, explicit decision).
- `wallper.app/robots.txt` disallows `/api/` and `/_next/` — the TV and scripts read rendered pages (`/wallpapers?...`) only.
- The AF8 has 1 GB RAM: video playback must be OFF by default there, and the feature must survive the launcher without a decoder (graceful fallback to the still wallpaper).
- RAM budget (Shield Pro): video decodes in NVDEC buffers with a 1080p surface — never a 4K surface, never a second player instance. Wallper catalog is parsed once and cached on disk (24 h TTL); stills decode at ≤ 1920 px.
- Continue Watching is row-only: it must never replace the home wallpaper/hero. Upstream's `cw_enabled` stays OFF; the dock-tile popup is the only CW surface (owner decision 2026-11-04).
- Wallpaper content: no anime, no games, no manga, no pixelart, no people; minimum 3840×2160.
- Apple motion: crossfades ≤ 400 ms, focus reacts on the frame it lands, no animation while the system animator scale is 0.

## Review Focus

1. **Signed CDN URLs expire mid-session** — a clip chosen yesterday must not 403 silently; expect: on download failure the row marks the clip stale and offers re-pick. Test: Task 5 step 4 (403 path).
2. **1 GB decode pressure** — a 4K HEVC clip plus the UI must not OOM the AF8; expect: AF8 refuses to start playback and falls back to the still. Test: Task 6 step 2 (device check on 192.168.1.73).
3. **Focus leaks** between pill, dock and popup — Nuvio's dock tile owns a popup (DPAD Up); the pill sits above the clock. Expect: focus never lands on an invisible control; DPAD navigation reaches every visible control in ≤ 2 presses from the dock. Test: Task 3 step 5 (uiautomator dump focus walk).
4. **Stale cache after a filter change** — excluded clips already on disk must be purged, not played. Test: Task 4 step 5 (purge assertion).
5. **Bitchord may be absent** (not installed on the AF8 today) — the pill must show Spotify-only and not render an empty slot. Test: Task 3 step 4 (session-merge check with one app).

---

### Task 0: Sync the fork onto upstream v2.1.5

**Files:**
- Modify: repo history only (merge `upstream/main` into `eptik:main`); close PR #1; delete `feat/nuvio-account-sync`.

**Interfaces:**
- Produces: a clean base at upstream v2.1.5 with `NuvioCloud`, `WatchPopup`, `WatchCloud` present; branch `feat/media-and-video-wallpapers` cut from it.

- [ ] **Step 1: Merge upstream.** `git merge upstream/main` on `main`; keep upstream's `NuvioCloud.java`, `WatchPopup.java`, `WatchCloud.java`, `StremioCloud.java`; do not carry over `NuvioAccount.java`/`NuvioSync.java`/`NuvioLoginActivity.java` (drop them in the merge resolution).
- [ ] **Step 2: Build.** `bash build.sh` → `Built build/tvos-home.apk`, zero javac errors.
- [ ] **Step 3: Install and smoke the popup on the AF8 (192.168.1.73).** Install, open Settings → Apps → Continue Watching section; from the dock, focus the Nuvio tile and press DPAD UP; expect the Continue Watching row with real titles from the owner's account (they are signed in via upstream's QR flow). Screenshot to `work/shots/upstream_popup.png`.
- [ ] **Step 4: Check the artwork shape upstream chose.** If popup/hero still draws a 2:3 poster full-screen, re-apply the metahub-landscape fix inside *their* code path (same one-line change: prefer `background` over `poster`). Record the decision in the commit message either way.
- [ ] **Step 5: Commit + push `feat/media-and-video-wallpapers`; close PR #1 with a note pointing at the upstream feature.**

### Task 1: Now-playing session plumbing (port from cinemaglassTV)

**Files:**
- Create: `src/com/techgenius/tvoslauncher/MediaPill.java` (view) —ported from our `NowPlayingPill.java`, restyled to tvOS glass.
- Create: `src/com/techgenius/tvoslauncher/NowPlaying.java` (session poller; near-verbatim port, package rename).
- Modify: `AndroidManifest.xml` — register `MediaPillService` (NotificationListenerService) if the upstream build lacks listener access, and grant via the existing Perms flow.
- Modify: `src/com/techgenius/tvoslauncher/MainActivity.java` — host the pill under the clock, `visibility = gone` when idle.

**Interfaces:**
- Produces: `static Track NowPlaying.current()`, `NowPlaying.addListener(Listener)`, `togglePlayPause()`, `skipNext()`; `MediaPill.setTrack(Track)`.

- [ ] **Step 1: Port `NowPlaying.java`** (the poller already handles "stay on the playing session, prefer Spotify" — extend preference order to `com.spotify.tv.android`, then any `bitchord`-matched package, then any playing session).
- [ ] **Step 2: Session-merge check (node, runnable):** feed the preference function a synthetic list [Nuvio(paused), Spotify(playing), Bitchord(playing)] → picks Bitchord; [Nuvio(paused), Spotify(paused)] → picks Spotify; [] → null. Write as `work/check_media_pref.js`, assert all three.
- [ ] **Step 3: Round artwork decode** in `MediaPill`: circular-clipped bitmap, 96 dp, downsampled to 192 px before decode (1 GB rule).
- [ ] **Step 4: Host in MainActivity** under the clock; alpha 0 when no session; 180 ms fade in/out; skip `ANIMATOR_DURATION_SCALE==0`.
- [ ] **Step 5: Device check:** start Spotify playback via adb (`media session` dispatch or `input keyevent KEYCODE_MEDIA_PLAY` after launching Spotify), screenshot: pill visible with round artwork, title ≤ 25 chars + "…", play/next glyphs crisp. Focus walk: DPAD from dock reaches pill in ≤ 2 presses and never lands on a hidden control (uiautomator dump).

### Task 2: Wallper catalog (TV-side parse of the allowed page)

**Files:**
- Create: `src/com/techgenius/tvoslauncher/WallperCatalog.java` — fetches `https://www.wallper.app/wallpapers?sort=Most+Liked`, extracts the embedded item JSON (keys: `slug, name, categorySlug, resolution, likes, duration, previewImageKey, fileKey`), filters per Decisions 4.
- Create: `work/check_wallper_parse.js` — parses a saved copy of the page (`/tmp/wallper.html`) and asserts the same numbers measured during recon: ≥ 1,500 items kept out of 1,692 ≥ 4K after exclusions, zero items from excluded categories, every kept item ≥ 3840×2160.

**Interfaces:**
- Produces: `static List<Clip> WallperCatalog.load(Context)` where `Clip {slug, name, category, width, height, likes, durationSec, previewUrl, videoUrlOrNull}`.

- [ ] **Step 1: Write the parser** against the saved HTML first (offline), then wire the fetch (8 s connect / 15 s read timeouts).
- [ ] **Step 2: Run `work/check_wallper_parse.js`** → prints `kept N of 1692, excluded categories 0 hits` and exits 0.
- [ ] **Step 3: Commit.**

### Task 3: Wallper URL spike (Mac, scrapling)

**Files:**
- Create: `work/wallper_spike.py` (scrapling DynamicFetcher) — opens a wallpaper, captures the video request URL (method, host, path, query, expiry), saves `work/wallper_urls.json` with 5 samples + observed TTL.

**Interfaces:**
- Produces: the documented way to obtain a playable URL. One of:
  - **A:** static CDN path → `Clip.videoUrl` is buildable TV-side; Task 4 fetches directly.
  - **B:** short-lived signed URL → the TV fetches it at pick time from the same public page flow the browser uses; Task 4 requests it on selection, never caches the URL, only the file.

- [ ] **Step 1: Capture the network trace for one playback.**
- [ ] **Step 2: Write the decision (A/B) + TTL into `work/wallper_urls.json` and the plan addendum.** No TV code before this lands.

### Task 4: Browsable picker + bounded cache (TV)

**Files:**
- Create: `src/com/techgenius/tvoslauncher/VideoWallpaperPicker.java` — overlay listing kept clips (preview thumbnail, name, category, likes); OK downloads the clip; long-press deletes.
- Create: `src/com/techgenius/tvoslauncher/VideoCache.java` — `files/videos/`, hard cap 500 MB, LRU eviction, purge of excluded categories on startup.
- Modify: `SettingsActivity.java` — Apps page rows: "Video wallpapers" On/Off, "Browse clips…", "Clear cache".

**Interfaces:**
- Consumes: `WallperCatalog.load`, the Task 3 URL decision, `VideoCache.put(InputStream, name)`.
- Produces: `VideoCache.current()` → the chosen clip's local file; `VideoCache.purgeExcluded(Set<String>)`.

- [ ] **Step 1: Cache eviction test (node over a JSON fixture):** insert 6 entries totalling 700 MB → after `evict`, total ≤ 500 MB and the newest 4K clip survives; excluded-category file is gone. `work/check_video_cache.js`.
- [ ] **Step 2: Picker UI:** grid of preview cards, D-pad focus ring, back exits; download shows progress in the same row.
- [ ] **Step 3: Download with resume-on-failure; on 403 (expired URL) mark stale and re-request per the Task 3 decision.**
- [ ] **Step 4: Purge excluded categories at startup (Step-1 assertion wired into `VideoCache` init).**
- [ ] **Step 5: Commit.**

### Task 5: Playback engine behind an interface

**Files:**
- Create: `src/com/techgenius/tvoslauncher/VideoWallpaperPlayer.java` — interface `start(File, SurfaceView)`, `stop()`, `setListener(Listener)`.
- Create: `src/com/techgenius/tvoslauncher/MediaPlayerWallpaper.java` — `MediaPlayer` + `SurfaceView` behind the UI, muted, `looping`, 1080p surface for the decoder (never 4K surfaces), `setVideoScalingMode(VIDEO_SCALING_MODE_SCALE_TO_FIT)`.
- Modify: `MainActivity.java` — when enabled and a clip exists, mount the SurfaceView under the scrim layer; on `onPause`/focus-into-heavy-UI, stop; on OOM/codec error, disable for the session and fall back to the still wallpaper with a Toast.

**Interfaces:**
- Consumes: `VideoCache.current()`, Settings toggle.
- Produces: nothing else depends on it.

- [ ] **Step 1: Interface + MediaPlayer implementation.**
- [ ] **Step 2: Failure test (runnable on device):** point `start()` at a 1 KB fake `.mp4` → expect `Listener.onError` within 5 s and the still wallpaper visible; no crash. Script it with adb + screenshot.
- [ ] **Step 3: Shield check (192.168.1.72):** 4K clip plays ≥ 60 s with launcher navigation responsive; `dumpsys gfxinfo` frame time p95 < 32 ms while idle.
- [ ] **Step 4: AF8 check (192.168.1.73):** feature OFF by default; when force-enabled, either it plays or it degrades cleanly (no OOM crash) — record which.
- [ ] **Step 5: Commit.**

### Task 6: Ledger + install + docs

**Files:**
- Create: `GATES-media.md` (gates for Tasks 1–5; runnable checks are the `work/check_*.js` scripts above; device gates manual with screenshot/resolved-activity evidence).
- Modify: `README.md` — new Settings rows, the wallper policy (robots-respecting, manual picks), the AF8 default-off note, NOTICE unchanged.

- [ ] **Step 1: `node gate-lint.mjs GATES-media.md` → 0 errors.**
- [ ] **Step 2: Approve and run; report met/unmet counts; any impossible gate gets `ABANDON:` with a reason, never silent removal.**
- [ ] **Step 3: Install final APK on both devices; PR against `eptik:main` with screenshots.**
