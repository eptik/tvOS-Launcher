# Video wallpaper mode (`video_wall`) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `video_wall` wallpaper scene — 4K video wallpapers pulled from a most-liked wallper.app catalogue, picked manually on the TV, played behind the launcher exactly like the existing Reddit wallpaper mode plugs in.

**Architecture:** `Scenes` already treats wallpapers as registered scene specs (Reddit mode is `REDDIT_WALLPAPER_ID` with a spec, an order slot and an enable flag); `CustomMedia.fetchNextRedditWallpaperAsync` is the fetch-and-set precedent. `video_wall` follows the same shape: a spec in `Scenes`, a catalog/cache service (`WallperCatalog` — already built and JVM-tested), a picker overlay, a bounded video cache, and a player behind an interface that the wallpaper layer starts/stops on mode selection. The Mac does the one thing the TV cannot: discovering wallper's signed video URLs (scrapling spike).

**Tech Stack:** Pure Java, `build.sh` (no Gradle), platform `MediaPlayer` (zero dependencies; VLC/ExoPlayer is a later, explicit decision), Node gate scripts, scrapling (Mac-side only), adb-shell client for device work.

**Spec:** Owner decisions 2026-11-04/10 (grilling + this plan); supersedes `2026-11-04-media-and-video-wallpapers.md` Tasks 2–5 where they overlap. The media chip/Now Playing work is already shipped.

## Global Constraints

- Pure Java, no Gradle; every task must build with `bash build.sh`.
- No new runtime dependencies (MediaPlayer first; adding VLC/ExoPlayer requires a separate owner decision).
- `wallper.app/robots.txt`: only rendered pages (`/wallpapers?sort=Most+Liked`) — never `/api/` or `/_next/`.
- Content filter fixed: exclude `anime`, `games`, `manga`, `pixelart`, `people`; clips must be ≥ 3840×2160. Already enforced by `WallperCatalogParse.kept()` and covered by `work/WallperCatalogTest.java`.
- Cache: disk only, ≤ 500 MB LRU in `files/videos/`; the signed URL is never cached, only the file.
- AF8 (1 GB, API 26): playback OFF by default; any decode failure must fall back to the still wallpaper without crashing. Shield Pro is the performance target.
- Apple motion: transitions ≤ 400 ms EASE; respect `ANIMATOR_DURATION_SCALE == 0`.

## Review Focus

1. **Signed URL expiry mid-download** → the picker marks the clip stale and re-requests; the cache never stores URLs. Test: Task 4 step 4 (403 path).
2. **AF8 decode pressure** → OOM or codec error must degrade to the still wallpaper, never crash the launcher. Test: Task 6 step 4.
3. **Mode leaks** — switching from `video_wall` to another scene must stop playback and release the surface within one frame budget. Test: Task 6 step 3.
4. **Stale cache after filter change** → excluded clips are purged at startup, not played. Test: Task 5 step 1.
5. **Catalog staleness** → the 24 h cache must not block the picker when empty; a failed refresh shows the cached list with its age. Test: Task 2 step 3.

---

### Task 1: Register the `video_wall` scene

**Files:**
- Modify: `src/com/techgenius/tvoslauncher/Scenes.java` — mirror `REDDIT_WALLPAPER_ID` exactly: `VIDEO_WALLPAPER_ID = "video_wall"`, a spec entry (`VIDEO_WALLPAPER_SPEC`) with EN/TR labels ("Video Wallpapers" / "Video Duvar Kağıtları"), appended to `scene_order` after `reddit_wall`, an `isVideoWallpaperEnabled(c)` helper alongside `isRedditWallpaperEnabled`, and the `getSceneSpec` branch.
- Modify: `res/` if scene icons are drawable-keyed (check how reddit renders its tile).

**Interfaces:**
- Produces: `Scenes.VIDEO_WALLPAPER_ID`, `Scenes.isVideoWallpaperEnabled(Context)`, `Scenes.setSelected(c, Scenes.VIDEO_WALLPAPER_ID)` usable by later tasks.

- [ ] **Step 1: Implement the spec mirroring `reddit_wall` (read every reddit touchpoint first: `Scenes.java:47,161,214,236,287,379,398` and `CustomMedia.java:770-830`).**
- [ ] **Step 2: Build.** `bash build.sh` → zero errors.
- [ ] **Step 3: Device check (AF8, 192.168.1.73):** Settings → Look & Scenes shows "Video Wallpapers"; selecting it does nothing yet except switch `wallpaper` pref to `video_wall` (screenshot `work/shots/video_scene.png`).
- [ ] **Step 4: Commit.**

### Task 2: Catalog service on device

**Files:**
- Already built: `WallperCatalogParse.java` (pure parser, JVM-tested), `WallperCatalog.java` (fetch + 24 h prefs cache + `kept()` ranking).
- Modify: `WallperCatalog.java` — expose `loadAsync(Context, CatalogCallback)` so the picker never blocks, and `Clip.videoUrlOrNull()` per the Task 3 decision.

**Interfaces:**
- Consumes: the wallper page (allowed HTML only).
- Produces: `List<Clip>` ranked by likes, filtered; the 24 h prefs cache; `CatalogCallback.onCatalog(List<Clip>, long fetchedAtMs)`.

- [ ] **Step 1: Add the async wrapper + callback; the picker renders from the callback, showing "cached N h ago" when offline.**
- [ ] **Step 2: JVM tests stay green:** `bash work/run-jvm-tests.sh` → "JVM tests green".
- [ ] **Step 3: Device check:** temporarily log `kept.size()` on refresh; expect ~680 with the live page (recon number), then remove the log.
- [ ] **Step 4: Commit.**

### Task 3: Mac-side spike — the signed URL flow (blocks Tasks 4–5 download path)

**Files:**
- Create: `work/wallper_spike.py` (scrapling `DynamicFetcher`) — open a wallpaper page, trigger playback, capture the video request (URL, headers, expiry), repeat after 5 min to measure TTL.
- Create: `work/wallper_urls.json` — 5 samples + the decision.

**Interfaces:**
- Produces exactly one decision, written down before any TV code touches downloads:
  - **A — static CDN path:** `Clip.videoUrl` buildable on-device; Task 4 downloads directly.
  - **B — short-lived signed URL:** the TV fetches it at pick time the way a browser does; the picker never caches URLs, only files.

- [ ] **Step 1: Capture the flow with scrapling; measure TTL.**
- [ ] **Step 2: Write the decision into `work/wallper_urls.json` and this plan.**

### Task 4: Picker overlay + video cache

**Files:**
- Create: `src/com/techgenius/tvoslauncher/VideoWallpaperPicker.java` — glass overlay (same popover mechanics as Now Playing: fixed size, LiquidGlass, `pickerLayer`/`pickerCells`/`popoverReturn`) listing kept clips ranked by likes: preview thumbnail, name, category, likes; OK downloads with inline progress; long-press deletes from cache.
- Create: `src/com/techgenius/tvoslauncher/VideoCache.java` — `files/videos/`, ≤ 500 MB LRU, `purgeExcluded(Set<String> categories)` on startup, `current()` → the chosen clip's file.
- Modify: `SettingsActivity.java` — Apps page rows: "Video wallpapers" On/Off (device-gated), "Browse clips…", "Clear cache".

**Interfaces:**
- Consumes: `WallperCatalog.loadAsync`, the Task 3 decision, `Scenes.VIDEO_WALLPAPER_ID`.
- Produces: `VideoCache.current()`, `VideoCache.evict()`, the Settings rows.

- [ ] **Step 1: Cache eviction test (runnable):** `work/check_video_cache.js` over a JSON fixture — 700 MB of entries → after evict ≤ 500 MB, newest 4K clip survives, excluded-category file gone. Run: `node work/check_video_cache.js` → "video cache OK".
- [ ] **Step 2: Picker UI** — preview grid, D-pad ring focus, download progress in-row, back closes and returns focus to the caller.
- [ ] **Step 3: Download with the Task 3 URL decision; on 403 mark stale and re-request once.**
- [ ] **Step 4: 403 path test (device): point the picker at an expired URL → stale mark + re-request, no crash, no toast spam (screenshot).**
- [ ] **Step 5: Commit.**

### Task 5: Playback behind an interface

**Files:**
- Create: `src/com/techgenius/tvoslauncher/VideoWallpaperPlayer.java` — `interface { start(File, SurfaceView); stop(); setListener(Listener) }`, `Listener.onError(Throwable)`.
- Create: `src/com/techgenius/tvoslauncher/MediaPlayerWallpaper.java` — `MediaPlayer` + `SurfaceView` behind the scene layer: muted, looping, surface sized to the panel (never a 4K surface), `VIDEO_SCALING_MODE_SCALE_TO_FIT`.
- Modify: `MainActivity.java` / `SceneHome.java` — when `wallpaper` pref == `video_wall` and a clip is cached and playback enabled: mount the SurfaceView under the scene layer; stop on `onPause`, on mode switch, and on heavy overlays; `onError` → disable for the session + fall back to the still wallpaper + one Toast.

**Interfaces:**
- Consumes: `VideoCache.current()`, the Settings toggle, the scene-selection flow.
- Produces: nothing downstream.

- [ ] **Step 1: Interface + MediaPlayer implementation.**
- [ ] **Step 2: Failure test (device): start against a 1 KB fake `.mp4` → `onError` within 5 s, still wallpaper visible, launcher alive (screenshot + logcat).**
- [ ] **Step 3: Mode-switch test (device): video_wall → night scene → surface released (`dumpsys SurfaceFlinger` or layer visibility), still wallpaper returns.**
- [ ] **Step 4: AF8 check: default OFF; force-enable → either plays or degrades cleanly; record which.**
- [ ] **Step 5: Shield check (192.168.1.72): 4K clip ≥ 60 s, `dumpsys gfxinfo` p95 frame < 32 ms while idle.**
- [ ] **Step 6: Commit.**

### Task 6: Ledger, docs, install

**Files:**
- Create: `GATES-video-wall.md` — gates: G1 scene registered + visible (runnable grep + screenshot), G2 catalog ranks/filters (JVM test), G3 cache eviction (node), G4 picker opens/downloads (device), G5 playback + fallback (device), G6 Shield performance (device), G7 README documents the mode.
- Modify: `README.md` — replace the "in planning" bullet with the shipped feature + constraints.

- [ ] **Step 1: `gate-lint` → 0 errors; approve and run; report met/unmet; `ABANDON:` with reason for anything impossible.**
- [ ] **Step 2: README update; install final APK on both devices; PR against `eptik:main`.**
