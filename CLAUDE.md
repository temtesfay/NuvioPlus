# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Android debug APK
./gradlew :composeApp:assembleDebug

# Run on connected device/simulator (wrapper script)
./scripts/run-mobile.sh android
./scripts/run-mobile.sh ios

# iOS IPA (release) — open Xcode, Product → Archive, then Distribute
# Or via xcodebuild:
xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Release archive

# Run a single Gradle test
./gradlew :composeApp:testDebugUnitTest --tests "com.nuvio.app.SomeTest"
```

## Required: local.properties

The Gradle build runs `GenerateRuntimeConfigsTask` (in `composeApp/build.gradle.kts`) that reads `local.properties` and generates Kotlin config objects at build time. Without these keys the build will fail:

```
sdk.dir=/path/to/android/sdk
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
# Optional (needed for Trakt integration):
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...
# Optional (needed for intro-skip DB):
INTRO_DB_URL=...
INTRO_DB_KEY=...
```

Generated files land in `composeApp/build/generated/` and are gitignored.

## Architecture Overview

**Kotlin Multiplatform (KMP) + Compose Multiplatform.** Shared UI and business logic live in `composeApp/`, native iOS glue in `iosApp/`.

### Source Sets

```
composeApp/src/
  commonMain/   — shared UI (Compose), ViewModels, repositories, domain logic
  androidMain/  — Android actual implementations (DataStore, ExoPlayer surface, etc.)
  iosMain/      — iOS actual implementations (NSUserDefaults, MPV bridge calls)

iosApp/iosApp/  — Swift/UIKit host: app entry, native tab bar, player bridge
```

### expect/actual Pattern

Platform-specific behaviour is declared with `expect` in `commonMain` and implemented with `actual` in `androidMain`/`iosMain`. Key examples:

- `PlayerEngine.kt` declares `expect fun PlatformPlayerSurface(...)` — iOS actual wraps `MPVPlayerViewController`
- `PlayerSettingsStorage.kt` declares `expect` CRUD for all settings — iOS actual uses `NSUserDefaults` with profile-scoped keys

### Feature & Core Packages

```
commonMain/kotlin/com/nuvio/app/
  features/
    player/     — PlayerEngine, SubtitleModal, SubtitleStylePanel, PlayerSettingsRepository/Storage
    profiles/   — ProfileRepository (activeProfileId, ProfileScopedKey)
    browse/     — content browsing
    settings/   — app-wide settings
    …
  core/
    …
```

### iOS Bridge Pattern

`iosApp/iosApp/Player/` contains the native Swift player implementation. Before Compose initialises, `NuvioPlayerRegistration.register()` is called (in `ContentView.swift → makeUIViewController`) which registers the Swift factory with the KMP side. KMP then calls through the registered `NuvioPlayerBridgeFactory` when it needs a native player view.

Entry-point chain: `iOSApp.swift → ContentView → ComposeView → RootComposeViewController → MainViewController (KMP)`.

## Player / Subtitle System

### MPV on iOS

The iOS player uses **MPVKit** (libmpv via Metal/MoltenVK). Key property used for subtitle rendering: `sub-ass-override=strip` puts libmpv into plain-text mode.

**MPVKit font limitation:** `sub-font` / `osd-font` properties are silently ignored by the bundled renderer — no font loading. Rounded corners are also impossible in libmpv.

### UIKit Subtitle Overlay

To support custom fonts and rounded-corner backgrounds, `MPVPlayerBridge.swift` includes a UIKit overlay that **replaces** MPV's subtitle rendering when needed:

- Overlay activates when `bgAlpha > 0.01 || hasCustomFont` (hasCustomFont = font family is not empty and not `"sans-serif"`)
- When active: sets `sub-visibility = no`, renders text via `UILabel` over a `UIView` with `cornerRadius = 6`
- Subtitle text is driven by `mpv_observe_property(mpv, 0, "sub-text", MPV_FORMAT_STRING)` — polled via `getString("sub-text")` on the event queue thread, dispatched to main for UI update
- Font mapping: `Menlo*` → `UIFont.monospacedSystemFont` (SF Mono), `Georgia*` → `UIFont(name: "Georgia[-Bold]")`, everything else → `UIFont.systemFont` (SF Pro)
- Font size: KMP sends `fontSizeSp * 3` to MPV; UIKit overlay reverses with `CGFloat(fontSize) / 3.0`
- Bottom position: `bottomInset = CGFloat(24 + max(0, 100 - subPos) * 2)` — maps `sub-pos` (default 90) to ~44 pt above safe-area bottom

### Settings Persistence

All settings repositories are **object singletons** with `MutableStateFlow<UiState>`, `ensureLoaded()` for lazy disk reads, and `onProfileChanged()` for profile switching.

**Profile-scoped keys:** Every NSUserDefaults key is suffixed with `_${activeProfileId}` (default `_1`) via `ProfileScopedKey.of(key)`.

**Cloud sync protocol — critical pattern:** `exportToSyncPayload` serialises settings to a map; `replaceFromSyncPayload` **deletes all keys in `syncKeys`** then restores from the payload. If a setting is added to `syncKeys` but not added to both `exportToSyncPayload` AND `replaceFromSyncPayload`, it will be **wiped on every sync**. When adding new settings fields, always update all four locations:
1. `syncKeys` list
2. `exportToSyncPayload()`
3. `replaceFromSyncPayload()`
4. Both `PlayerSettingsStorage.android.kt` and `PlayerSettingsStorage.ios.kt`

### MPV Color Format

libmpv uses `#AARRGGBB` (alpha first, not last). `00` = transparent, `FF` = opaque. Helpers in `MPVPlayerBridge.swift`: `uiColorRGB(hex:)` for `#RRGGBB`, `uiColorAARRGGBB(hex:)` for MPV format, `overlayAlpha(fromAARRGGBB:)` to extract alpha byte.

## Version Management (iOS)

`iosApp/Configuration/Version.xcconfig` is the single source of truth for iOS version numbers:

```
MARKETING_VERSION = 0.1.x
CURRENT_PROJECT_VERSION = N
```

Bump both when releasing. The xcconfig feeds `CFBundleShortVersionString` and `CFBundleVersion` in the Info.plist.
