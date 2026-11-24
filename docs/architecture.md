# Architecture

tvOS Launcher is a tvOS 26-style home screen for Android TV: plain SDK Java, no Gradle, no
third-party runtime libraries, built to stay smooth on 1 GB / Android-9-era TV boxes. This fork
(`eptik/tvOS-Launcher`, branch `feat/media-and-video-wallpapers`) tracks upstream
(`techgenius1/tvOS-Launcher`, at v2.2.5) and adds a video wallpaper subsystem; it merges upstream
releases while keeping the fork features. Everything below describes the code as it exists at
the current branch head.

## Goals & non-goals

**Goals:** a launcher that stays responsive on low-end hardware; zero runtime dependencies;
first-class video wallpapers; overlays and button remapping that work over any app.

**Non-goals:** no new runtime libraries or build system (a full rebuild each time is accepted);
no telemetry — every network call is a content fetch (JustWatch / TMDB / Cinemeta, wallper.app,
Google News RSS, ESPN scores, aladhan prayer times, Open-Meteo / MET Norway / wttr.in plus
IP-geolocation, Reddit and Unsplash wallpapers, GitHub releases). The Presentation subsystem
(LAN slide control) is deliberately self-contained and
should not grow — it is a second application riding in the launcher APK.

## Build

No Gradle and no runtime libraries. `build.sh` runs `aapt2` (resources + assets) → `javac
--release 11` against `android.jar` (API 34) → `d8` (min API 23) → `zipalign` → `apksigner`
(local debug key, or the private release key via `RELEASE_KEYSTORE`). The script also expects
`zip` and `keytool` on the PATH; `build.ps1` is the Windows counterpart. Fonts (Inter, OFL) and
the Presentation web app are static files under `assets/`; the QR encoder is vendored source
(Nayuki, MIT) under `src/io/nayuki/qrcodegen`.

**Full rebuild on purpose.** `build.sh` starts from a clean build directory every run. At the
current size (~58,000 lines under `src/`) that costs a long-but-linear `javac`+`d8` pass, and it
is accepted so the script stays obvious and stale incremental state can never poison a build.
Caching javac output keyed by source hashes was considered and rejected (the classic
incremental-build invalidation bug class, for under a minute of gain).

## Code map

### Home shell & appearance

| Path | Responsibility |
|---|---|
| `MainActivity.java` | Home shell: grid/dock, focus & move, launch zoom, carousel (Continue Watching + news + recommendations), status row, Liquid Glass, profile switcher, idle mode, menus; hosts the video-wallpaper `SurfaceView` and player wiring, the search overlay and the auto-update hook |
| `SceneHome.java` | The "Night scenes" home layout: giant refractive glass clock, profile pill, Moon sleep-timer quick control, Keep-Watching frosted cards, glass dock with app-name callouts |
| `Scenes.java` | Multi-layer scene backgrounds: foreground depth cutouts, waterline reflection, date/weather header; scene picker state (incl. the `video_wall` scene), sticky `prefer_scene_id` preference |
| `HomeDashboard.java` | Default Home Dashboard: 4 Liquid Glass widgets shown when nothing is focused — Prayer Times, Football Matches, Latest Releases (JustWatch), Weather |
| `SettingsActivity.java` | All settings pages, tvOS list layout |
| `SetupActivity.java` | First-time setup pages (one question per page, tvOS list style) |
| `Profiles.java` | Profiles (max 3), unique emoji avatars, per-profile storage names |
| `CustomMedia.java` | Custom app icons (`.jpg`/`.png`/`.ico`, pure-Java ICO decoding), custom still wallpapers (Night Scenes & Classic), remote-friendly TV file selector |
| `Ui.java` | Resolution-based sizing (960×540 design grid), fonts |
| `Glyphs.java` | Line glyphs drawn in code: Control Panel tiles, Presentation tile, inputs, refresh |
| `I18n.java` | Languages: dictionary keyed by the English string, active-language switching, date formats, reading direction (`isRtl`, `applyDirection`) |
| `I18nHe.java`, `I18nHeData.java` | Hebrew: patterns for strings built with values, and the generated dictionary |
| `Clicks.java` | Navigation sounds: bundled Apple-tvOS-style WAVs (`Navigate` / `Click` / `Back` under `assets/sounds/`) played through a SoundPool at 85%; Android system sound effects only as a load-failure fallback |

### Video wallpapers (this fork)

| Path | Responsibility |
|---|---|
| `VideoWallpaperPlayer.java` | Playback contract (`start`/`stop`/`positionMs`/listener) and the `MediaPlayerWallpaper` engine. The launcher wiring builds a fresh player per clip: every rotation or manual skip releases the running player (`stopVideoWallpaper` — `release()` is valid from any decoder state) and `startVideoWallpaperWith` constructs a new `MediaPlayerWallpaper` on the same surface; a still wallpaper covers the gap and a poster covers the surface until `onRenderingStarted` |
| `VideoCache.java` | Clip + poster cache with a bounded LRU (1–3 GB): the owner's `Downloads/tvos-wallpapers` folder first, then legacy private clips; poster regeneration from clip frames; a duration probe that decodes the moov; a motion score (mean abs luma diff between samples — near-still clips read as frozen and are never rotated in); a decoder-freeze ban list backed by live PixelCopy checks, with a one-time purge of stale bans |
| `VideoPickerActivity.java` | The wallpaper picker: the owner's library first (with sizes and explicit delete), then the wallper.app "Most liked" catalogue as a paged 4×2 poster grid with a page bar; picking downloads clip + poster into the cache and applies the scene |
| `WallperCatalog.java` | Reads the wallper.app catalogue the way a browser reads it (the rendered page, which is what robots.txt permits): URLs, categories, clips list; filter rules for the catalogue (landscape 4K, owner's category exclusions) |
| `WallperCatalogParse.java` | Parses the doubly-escaped JSON embedded in the catalogue page (unescapes twice, walks plain-JSON objects) |
| `SessionPick.java` | Which media session the now-playing pill follows — pure Java on purpose, tested on the JVM (`work/SessionPickTest.java`) |

`MainActivity` owns the `SurfaceView`: the decoder writes straight into the display compositor
(no per-frame GPU work in Java), and the drawn layers punch a transparent hole for it. That
hole's frame buffer must be `ARGB_8888` — `RGB_565` has no alpha, the hole renders opaque black
and buries the video surface. A poster still covers the surface until the player reports
`onRenderingStarted()`. The current clip is self-healing: if it vanishes (picker delete, cache
eviction) while active, the launcher restores the wallpaper preference rather than dropping to a
black screen.

### Overlays, input & power

| Path | Responsibility |
|---|---|
| `ControlPanelOverlay.java` | Control Panel shown over ANY app (`TYPE_ACCESSIBILITY_OVERLAY` when `PowerService` is on) or in-app on Home: Wi-Fi, Bluetooth, Memory, DND and quick-action tiles; the projector-safe screen-off (`showScreenOff()`) and the sleep-timer quick control |
| `RecentAppsOverlay.java` | Recent Apps switcher + RAM cleaner, over any app via the accessibility overlay or on Home |
| `ButtonMapper.java` | System-wide remote button remapping through `PowerService`: independent actions for single/double/triple tap (open a panel, launch an app, send a key code) |
| `PowerService.java` | The accessibility service the user turns on (manifest label: "tvOS Launcher – Button Mapper & Control Panel"): global key interception for `ButtonMapper`, host for the two overlays above, projector-safe sleep + sleep timer, volume-hold repeat fix, and Caffeine Mode (denies system idle / Ambient so the TV stays awake) |
| `SleepReceiver.java` | Sleep-timer alarm; performs the sleep via `ControlPanelOverlay.showScreenOff()` → `PowerService.sleep()` (accessibility). If the accessibility service is off, sleep silently does nothing — there is no device-admin path in this build |
| `Inputs.java` | TV inputs from the TV input framework; switching via the passthrough channel URI |
| `AirPlayGuard.java` | Keeps the (separately installed) AirPlay receiver app visible: `_airplay._tcp` mDNS check; restarts the receiver with ordered broadcasts — `LIST_SETTINGS`→`SET_SETTING` to its settings receiver, and `QUICKBOOT_POWERON` to its `BootReceiver` (`launchBriefly`, no screen shown) |
| `Perms.java` | On-screen permission and default-home requests. ADB is a designed setup channel, not a last resort: "Set Default via Local ADB (Recommended)" in the UI; `LocalAdb`-based in-app repair stays available afterwards |
| `LocalAdb.java` | Pure-Java local ADB client (127.0.0.1:5555) used during Easy Setup and self-repair: grants `WRITE_SECURE_SETTINGS`, `DUMP`, `SET_VOLUME_KEY_LONG_PRESS_LISTENER` on the TV itself so port 5555 does not have to stay open |
| `CleanYourTv.java` | On-device debloater: matches the TV against 341 CleanYourTV profiles (read from the TV's `Download/cleanyourtv` folder when writable, else the app's private files dir — one location at a time; the private dir also holds the rollback lists of disabled packages), manual system-app debloater, stock-launcher disabler — safety regexes and recovery lists ported from the upstream tools |
| `AppUpdater.java` | Checks `techgenius1/tvOS-Launcher` GitHub Releases, downloads and installs the APK. Fork policy: the automatic check is OFF by default (see Risks) — the Settings row and the manual "check now" path remain |

### Content & services

| Path | Responsibility |
|---|---|
| `Suggestions.java` | Top-shelf recommendations: JustWatch / TMDB / Stremio (Cinemeta) fetch, cache, artwork, deep links |
| `Search.java` | Search data: JustWatch title search (the services JustWatch lists for India) and Cinemeta, matching each service to its installed app, Open / Get actions, recent searches (last 8) |
| `SearchPanel.java` | The search overlay in `MainActivity` (no new activity): one-row keyboard (Latin, digits, Hindi, Hebrew or Turkish — by app language — plus voice), Apps & Settings and Movies & TV rows, the How to Watch sheet (which services carry a title) |
| `MediaWatchService.java` | Notification listener → active `MediaSession`s → the Continue Watching store, merged with TvProvider Watch Next / preview-program rows (see the decision below); DND-listener access |
| `NowPlaying.java` | Single source of truth for "what is playing right now" — read by the UI, never polls the network |
| `NowPlayingPanel.java` | The Now Playing popover: round artwork, live progress bar ticking from the session, transport buttons |
| `WatchCloud.java` | Base class for a streaming app's cloud account as used by the dock popup: QR / device-code sign-in only, so no email or password is ever stored. Subclasses: `NuvioCloud.java` (Nuvio public API), `StremioCloud.java` (Stremio `link.stremio.com` device flow; session tokens only) |
| `WatchPopup.java` | Continue-watching popup for a streaming app's dock tile (Nuvio, Stremio): instant cached row on focus, one-time QR login when not signed in |
| `News.java` | Top shelf news: RSS from Indian newsrooms (India, local city, world), images, freshness |
| `Weather.java` | Forecast from Open-Meteo, MET Norway or wttr.in (a genuine three-provider fallback chain), IP location / city search, cache, refresh schedule |
| `WeatherView.java`, `WeatherIcons.java` | Weather overview card and forecast panel (drawn in code), multicolour weather icons |

### Presentation subsystem (`present/`)

`present/Presenter.java` (entry point: settings, server lifecycle, player bridge, boot start);
`present/AdminServer.java` (HTTP API on port 8080, streaming multipart uploads);
`present/Repo.java` (SQLite storage), `Model.java` (slide model & validation), `Pairing.java`
(paired-device tokens); `PresentationActivity.java` (full-screen player: images, MediaPlayer
video, auto-scrolling WebView); `PresentIcon.java`; `LegacyCss.java` (`@layer` unwrapping for
old WebViews), `Qr.java`, `Net.java`; `BootReceiver.java` (Direct Boot via
`LOCKED_BOOT_COMPLETED` plus multi-stage foreground re-assertion for TVs whose stock launcher
cannot be disabled); `assets/present/` (the browser admin app: `index.html`, `app.css`, `app.js`).

## Design decisions

- **Measure Apple first.** Geometry comes from Apple's tvOS 26 press screenshot scaled to
  1920×1080 (dock 48 px from the edges, 248×148 px cards, 51 px gaps, dock top at 73%, focus 1.2×)
  and the HIG (60/80 pt safe area, 800×480 icons, focus/pressed states).
- **Liquid Glass without a backdrop-blur API.** The launcher knows what is behind its glass (its
  wallpaper or the carousel artwork), so it bakes a small blurred, saturation-boosted copy once per
  background and the GPU draws glass as shader-filled rounded rectangles aligned to the surface's
  screen position, plus magnified edge bands (lensing) and a gradient specular rim.
- **Recommendations and Continue Watching without system-write access.** The top shelf uses
  JustWatch / TMDB / Stremio (Cinemeta). Continue Watching is a merge: published media sessions
  from the notification listener, plus rows other apps published to Android's TvProvider
  (`watch_next_program` / `preview_program`, readable with the normal-level
  `READ_EPG_DATA` / `READ_TV_LISTINGS` permissions). The launcher does not try to write the
  system's own channels — the manifest's `WRITE_EPG_DATA` declaration is unused and could be
  dropped.
- **Search without a list of services.** JustWatch says which services in India have a title and
  gives each one's links; tvOS Launcher finds the app for each service itself (the package in the Android
  TV link, an installed non-browser handler of the web link, a remembered match, or an installed
  app with the same name), so a new service needs no app update when an installed app or
  resolvable link exists. Android 11+ hides other apps unless
  declared, so the manifest's `<queries>` lists launcher apps, voice input, the speech service
  and https handlers.
- **Search stays light.** It is an overlay in `MainActivity` (no new activity): apps match from
  the list already in memory on each key press; titles are fetched 350 ms after typing pauses with
  the previous request cut off, the last 8 searches are cached (20 titles are fetched per query),
  posters are decoded at card size
  into a heap-capped LRU, and while Search is open the Home layers under it stop drawing and the
  carousel pauses. Typing measured p50 6 ms / p90 11 ms per frame on the Mi Box (see Performance).
- **Dictation.** `SpeechRecognizer` (Google's TV voice service) with partial results fills the
  field live after Android's microphone prompt; otherwise `RecognizerIntent` opens the TV's voice
  prompt. The remotes' Assistant key is routed by Android to Google Assistant before apps, so the
  remote shortcut is holding Back for 1 s (Back is replayed when released sooner).
- **Video wallpapers without a rendering engine.** The decoder writes straight into the display
  compositor through a `SurfaceView`; Java does zero per-frame work. Every clip swap (the
  rotation timer, manual skip, error recovery) releases the running `MediaPlayer` and
  constructs a fresh one on the same surface — releasing detaches the compositor's media
  layer, and keeping a released decoder around or reusing a stale one reintroduces the
  frozen-clip class of bugs, so a clean session per clip is deliberate. A poster still covers
  the surface until `onRenderingStarted`, so no transparent hole appears early. The punch-through
  hole in the drawn
  layers must be `ARGB_8888`: the cheaper `RGB_565` frame buffer carries no alpha, the hole
  renders opaque black and buries the video surface entirely (this regressed once; it is the
  reason the hole path is pinned). Rotation votes and catalogue downloads are gated by a motion
  score (near-still clips read as frozen on a TV and are skipped) and a decoder-freeze ban list
  built from live PixelCopy checks of the playing surface.
- **The scene choice is sticky.** `prefer_scene_id` persists instead of being consumed once, so
  the picked scene survives restarts. Separately, two heals keep the video wallpaper alive:
  if the active clip's file vanishes (picker delete, cache eviction), the launcher re-points the
  current clip to a surviving cached clip instead of dropping to a black screen; and a one-time
  startup heal restores `prefer_scene_id` to `video_wall` when the preference is missing.
- **HTTP server without a library.** `present/AdminServer.java` is a plain `ServerSocket`
  answering the pairing / upload / streaming API, replacing the NanoHTTPD dependency, so the
  Presentation subsystem keeps the zero-runtime-dependency rule.
- **ADB without shell scripts.** `LocalAdb.java` speaks the local ADB protocol in pure Java so
  first-run setup and the launcher's permission self-repair happen through the TV itself; the
  ADB port does not need to stay open afterwards.

## Risks & mitigations

- **Accessibility service (`PowerService`).** Global key interception and overlays over any app
  are the launcher's most powerful surface. Cap: the user enables it explicitly; until then,
  Button Mapper, both overlays, projector-safe sleep and Caffeine Mode are inert (which is also
  the main silent-failure mode: sleep does nothing if the service is off).
- **ADB provisioning (`LocalAdb` + `Perms`).** Grants `WRITE_SECURE_SETTINGS`, `DUMP` and the
  volume-key listener. Caps: localhost only, needs network debugging switched on in the TV's
  settings, and the fork deletes nothing across the debugger boundary.
- **CleanYourTV (`CleanYourTv`).** Disables stock and system packages. Caps: per-model
  profile data (341 TV profiles), the upstream tools' recovery guards, and explicit rollback
  paths; it never operates outside the selected profile.
- **Auto-update (`AppUpdater`).** Installs official `techgenius1` releases, which would replace
  this fork's build and its features. Fork policy: automatic checks are OFF by default;
  the Settings toggle ("On/Off") and the manual check-now path remain for deliberate updates.
  Retargeting to this fork's own releases is a possible future change; nothing depends on the
  automatic path.
- **Third-party content.** JustWatch / TMDB / Cinemeta, wallper.app, Google News RSS, ESPN
  scores, aladhan prayer times, the weather trio plus IP-geolocation, Reddit and Unsplash
  wallpapers are fetched as a TV
  browser would, with owner-set filters; a failing source serves stale cache or nothing
  (accepted by design — there is no automatic source chain). Weather is the exception: a
  three-provider fallback chain.
- **Account tokens.** Stremio / Nuvio sign-in uses QR / device-code flows; session tokens only,
  no passwords are stored by the launcher.
- **Presentation server.** Plain HTTP on the LAN (port 8080), 6-digit PIN pairing with no
  attempt rate-limit, and tokens accepted from a URL query parameter; accepted as living-room
  convenience. Do not expose the port outside the LAN.
- **Storage & app visibility.** `MANAGE_EXTERNAL_STORAGE` reads wallpapers and video clips from
  user folders / USB drives; `QUERY_ALL_PACKAGES` builds the dock and search; each is required
  for a launcher's job, and no telemetry is sent anywhere — every outbound request is one of
  the content endpoints listed under Goals.
- **Debug builds.** The manifest ships `android:debuggable="true"` and neither build path
  strips it, so even release-keyed APKs are debuggable. Cap: none today; delete the attribute
  or split it by build type before distributing builds widely.

## Performance (Mi Box: Android 9, 1 GHz, 1 GB RAM, Mali-450 MP, GLES 2.0)

All figures below were taken on that one TV, with the measurement command shown at the end.

- Startup: the app list (package manager, banners, icon colours) and the wallpaper are prepared on
  background threads while the window's views are built: cold start ~1.0 s → ~0.86 s.
- No perpetual animations (no wallpaper drift, no idle sway): idle draws 0 frames.
- Nothing is rendered in software on the UI thread during transitions. Software-rendered glass
  blurs froze dock moves for ~1 s in an earlier design; the current path avoids that class.
- Legibility gradients are baked into the wallpaper and artwork; the window background is removed
  to avoid hidden full-screen fills.
- Measure with `adb shell dumpsys gfxinfo com.techgenius.tvoslauncher` — dock navigation runs at a
  16 ms median; full-screen transitions are limited by the GPU's fill rate (~30 fps on this box).
