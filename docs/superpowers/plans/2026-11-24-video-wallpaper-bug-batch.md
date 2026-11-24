# Video Wallpaper Polish — Bug Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five owner-reported defects: stacked clock renders, near-still clips reading as frozen, missing library thumbnails, hover-invisible page buttons, and a stricter download motion gate.

**Architecture:** All fixes live in the fork's own layers: `Scenes.java` (draw path), `VideoCache.java` (motion scoring + poster regeneration), `VideoPickerActivity.java` (focus visuals + download gate). No upstream behavior is reverted; the merge stays.

**Tech Stack:** Pure Java, android.graphics (MediaMetadataRetriever for frame probes), no Gradle (javac/aapt2 build.sh).

**Spec:** Owner messages 2026-11-24: "clocks overlap on the home screen", "video wallpaper aren't playing anymore even though they are activated", "selector shows your library section but there is no thumbnail", "the pages navigation when the mouse is over the text disappears", "i downloaded new video and its frozen now... with vlc it plays instantly".

## Global Constraints
- Package `com.techgenius.tvoslauncher`, branch `feat/media-and-video-wallpapers`, commits pushed to `eptik/tvOS-Launcher`.
- Ponytail ultra: smallest complete change, no unrequested features.
- No emojis in settings panels. Upstream tvOS design language (Glyphs/Ui/LiquidGlass).
- Device verification on the Shield (192.168.1.72) with screencap evidence; never claim "fixed" without a capture.

## Review Focus
- A clip whose motion is below human perception but above the probe threshold — pinned by Task 2's runtime gate test.
- The first paint after the canvas clear (the punch-through must not black out) — pinned by Task 1's screencap.
- Poster regeneration for a corrupt/short clip — Task 3 must not throw.
- Mouse-hover focus state parity with D-pad focus — Task 4's visual check both ways.
- The prefs growth from cached motion scores — bounded to the pool size (Task 2 cleans scores of deleted keys alongside ts_ entries).

---

### Task 1: Clear the window canvas in the video-wallpaper draw path

**Files:**
- Modify: `src/com/techgenius/tvoslauncher/Scenes.java` (drawScene, the `bg == null` video path)

**Interfaces:**
- Consumes: existing `drawScene` null-bg branch (the backdrop skip).
- Produces: a transparent canvas before chrome drawing — no behavior change for bitmap scenes.

- [ ] **Step 1: Locate the null-bg branch and add the clear**

Where the backdrop draw is skipped for the video path, clear the canvas first:
`canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);`
so each frame starts from a fresh window surface instead of accumulating clock digits.

- [ ] **Step 2: Build**
Run: `bash build.sh` → Built build/tvos-home.apk

- [ ] **Step 3: On-device proof**
Install, let the clock cross a minute boundary, screencap ×2 across the minute: the previous minute's digits must be gone (a single clean "H:MM").

- [ ] **Step 4: Commit** `fix: the video-wallpaper draw path must clear the window canvas each frame — the clock digits from previous minutes stacked into overlapping ghosts`

### Task 2: Runtime motion gate so near-still clips never rotate in

**Files:**
- Modify: `src/com/techgenius/tvoslauncher/VideoCache.java` (motionScore + rotation filter + prefs hygiene)
- Modify: `src/com/techgenius/tvoslauncher/MainActivity.java` (the rotation picker uses the gate)

**Interfaces:**
- Consumes: `cachedClips(Context)` → `List<String[]>`; `videoFile(Context, String)`.
- Produces: `static double motionScore(Context c, String key)` (cached in prefs `ms_<key>`), `static boolean tooStill(Context c, String key)` (score < 6.0).

- [ ] **Step 1: motionScore** — MediaMetadataRetriever, 5 samples spread over the duration (10%, 30%, 50%, 70%, 90%), 160px-wide grayscale frames, mean abs luma diff of consecutive pairs. Cache in prefs.
- [ ] **Step 2: tooStill** — score < 6.0 → true. Probe failure → false (never skip on error).
- [ ] **Step 3: the rotation skips tooStill clips** (and falls back to the full pool if every clip is still).
- [ ] **Step 4: the download check upgraded** to the same 5-sample spread, threshold 6.0 (replaces the 3-frame 0/2/4s probe).
- [ ] **Step 5: Build + JVM tests.**
- [ ] **Step 6: Commit** `feat: a motion score gates the rotation and downloads — near-still clips (owl-type) can no longer sit on screen looking frozen`

### Task 3: Regenerate missing library posters from the clips themselves

**Files:**
- Modify: `src/com/techgenius/tvoslauncher/VideoCache.java` (`ensurePoster(Context, String)`)
- Modify: `src/com/techgenius/tvoslauncher/VideoPickerActivity.java` (the library probe calls it)

**Interfaces:**
- Consumes: `posterFile(Context, String)`, `videoFile(Context, String)`.
- Produces: `static void ensurePoster(Context c, String key)` — no-op when the poster exists.

- [ ] **Step 1: ensurePoster** — if the poster is missing and the video exists: the MMR frame at 1.5 s → scale to 480px wide → PNG to posterFile. All failures silent (the placeholder shows).
- [ ] **Step 2: the picker's library probe calls ensurePoster per clip** (off the UI thread, already is).
- [ ] **Step 3: Build.**
- [ ] **Step 4: On-device proof** — the picker's library rows show real frame thumbnails.
- [ ] **Step 5: Commit** `feat: regenerate missing library posters from a clip frame — the reinstall wiped files/videos posters`

### Task 4: Focus/hover visuals must not hide the label

**Files:**
- Modify: `src/com/techgenius/tvoslauncher/VideoPickerActivity.java` (navBtn + tile focus states)

- [ ] **Step 1:** focused/hovered state = a light fill (0xFFE8E8EC) behind dark text; unfocused = the glass with light text. Apply to navBtn and the catalog tiles' badge/name colors.
- [ ] **Step 2: Build.**
- [ ] **Step 3: Commit** `fix: picker focus/hover uses a light fill with dark text — the page labels no longer vanish under the cursor`

### Task 5: Ship + verify on the Shield

- [ ] Install the final APK; verify: the clock clean across a minute boundary, the library thumbnails present, the grid paging intact, the wallpaper playing with motion evidence, the logs green.
- [ ] Push the branch.
