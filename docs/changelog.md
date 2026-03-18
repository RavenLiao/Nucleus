# Changelog

## Unreleased

### New Features

- **`Modifier.clientRegion()` for JBR title bar hit testing** — New modifier function that registers composable elements as interactive client regions within a `DecoratedWindow`'s title bar. Client regions receive mouse events (clicks, presses) instead of triggering window dragging. Uses AWT-level mouse listeners with precise coordinate-based hit testing, replacing the old pointer-event-based `customTitleBarMouseEventHandler`. See [Decorated Window](runtime/decorated-window.md).

### Bug Fixes

- **Fix title bar drag on Windows (`decorated-window-jbr`)** — Window dragging via the title bar no longer occasionally fails on the first attempt when another window has focus. The new `WindowMouseEventEffect` approach uses AWT mouse listeners for reliable hit-test forwarding to JBR's `CustomTitleBar`, fixing the intermittent missed drag events. ([#53](https://github.com/kdroidFilter/Nucleus/issues/53))

---

## v1.4.0

**Released: 2026-03-09**

### New Features

- **Native fullscreen with sliding title bar** — Platform-native fullscreen experience: Safari-like on macOS, Edge-like on Windows, Firefox-like on Linux. When the window enters fullscreen, the title bar becomes a floating overlay that slides down on hover near the top edge and slides back up when the pointer moves away. Enable with `Modifier.newFullscreenControls()` on `TitleBar` / `MaterialTitleBar`. See [Decorated Window](runtime/decorated-window.md).
- **macOS large corner radius** — New `Modifier.macOSLargeCornerRadius()` modifier applies the 26pt window corner radius used by Finder and Safari. Installs an invisible `NSToolbar` and repositions traffic light buttons to match Apple's native inset. See [Decorated Window](runtime/decorated-window.md).
- **System Color module** (`nucleus.system-color`) — Reactive detection of OS accent color and high contrast mode via JNI. Supports macOS (`NSColor.controlAccentColor`), Windows (DWM registry), and Linux (XDG Desktop Portal D-Bus). Composable APIs: `systemAccentColor()`, `isSystemInHighContrast()`. See [System Color](runtime/system-color.md).
- **Energy Manager module** (`nucleus.energy-manager`) — Comprehensive energy management with three tiers: full efficiency mode (EcoQoS + IDLE_PRIORITY_CLASS on Windows, `PRIO_DARWIN_BG` + task_policy_set on macOS, nice +19/ioprio/timerslack on Linux), light efficiency mode (CPU deprioritization only, no I/O throttling), and thread-level efficiency mode. Includes screen-awake (caffeine) API to prevent display sleep (IOPMAssertion on macOS, SetThreadExecutionState on Windows, D-Bus/X11 on Linux). Coroutine helpers: `withEfficiencyMode()`, `withLightEfficiencyMode()`. See [Energy Manager](runtime/energy-manager.md).
- **Auto-center `DecoratedDialog` on parent window** — Dialogs are now automatically centered on their parent with reliable positioning via `windowOpened` event. See [Decorated Window](runtime/decorated-window.md).
- **macOS live resize sync and RTL traffic-light support** — Smooth live resize synchronization and correct traffic light button positioning in right-to-left layouts. See [Decorated Window](runtime/decorated-window.md).
- **Centralized native library loading** — New `NativeLibraryLoader` with persistent cache replaces per-module loading logic.
- **Fullscreen-aware window controls** — Maximize button shows exit-fullscreen icon when in fullscreen mode on Linux and Windows, with new SVG icon variants (active/inactive/dark). See [Decorated Window](runtime/decorated-window.md).
- **AWT window background sync on macOS** — Idempotent property application prevents redundant `PropertyChangeEvent` firings, reducing visual jitter during layout passes. See [Decorated Window](runtime/decorated-window.md).
- **Sample CMP module** (`sample-cmp`) — New Kotlin Multiplatform Compose sample with Android and Desktop targets.
- **Example app gallery** — Material 3 component showcase with actions, communication, containment, selection, text inputs, typography, elevation, and color screens.

### Bug Fixes

- **Fix Windows fullscreen** — Compose for Desktop does not handle fullscreen correctly on Windows (window does not cover the taskbar). Now uses native Win32 APIs for true fullscreen, matching Edge and other native Windows applications.
- **Eliminate white resize flash on Windows** — Adjust Skiko's clear color to transparent for dark themes and synchronize DWM caption/border colors for consistent Windows 11 window chrome styling.
- **Skip Android configurations in `CleanNativeLibsTransform`** — Fixes build issues when Android targets are present. ([#79](https://github.com/kdroidFilter/ComposeDeskKit/issues/79))
- **Skip ZIP stapling to preserve blockmap** — Prevents breaking auto-update blockmap integrity during notarization. ([#70](https://github.com/kdroidFilter/ComposeDeskKit/issues/70))
- **Detect target arch from JDK release file for cross-building** — Fixes architecture detection when cross-compiling. ([#71](https://github.com/kdroidFilter/ComposeDeskKit/issues/71))
- Move Windows dark mode monitoring to native thread for reliability

### Deprecations

- **`appStore` property deprecated** — PKG distributions are now always treated as App Store builds. The `appStore` property is no longer needed. ([#65](https://github.com/kdroidFilter/ComposeDeskKit/issues/65))

### Refactoring

- Simplify tab drag-and-drop using Reorderable library
- Centralize native library loading with persistent cache

### Documentation

- Rewrite energy-manager docs for full platform coverage
- Add system-color module documentation
- Update decorated window docs with fullscreen title bar and large corner radius sections
- Document Windows resize white flash fix in decorated-window-jni
- Simplify single-instance and deep-links code examples

### CI/CD

- Add energy-manager native build and pre-merge verification for all platforms
- Add system-color native build workflows

---

## v1.3.6

**Released: 2026-03-02**

### Bug Fixes

- Fix fullscreen button transitions and alignment
- Restore title bar appearance before fullscreen exit animation
- Fallback to default icon for GraalVM native image on Windows
- Update `latest-mac.yml` checksums and file sizes after notarization
- Remove `xvfb-run` from test-graalvm workflow (Xvfb already started by setup-nucleus)

### Documentation

- Add homepage requirement note for electron-builder DEB packaging

---

## v1.3.5

**Released: 2026-03-02**

### Bug Fixes

- Add `homepage` to jewel-sample `nativeDistributions` for electron-builder DEB packaging

---

## v1.3.4

**Released: 2026-03-02**

### Bug Fixes

- Remove `xvfb-run` from graalvm workflow (Xvfb already started by setup-nucleus)

---

## v1.3.3

**Released: 2026-03-02**

### New Features

- Add `graalvm` option to `setup-nucleus` composite action
- Configure Windows code signing for jewel-sample using shared certificate

### Bug Fixes

- Add `libx11-dev` and `libdbus-1-dev` to graalvm release Linux dependencies
- Configure jewel-sample `nativeDistributions` with icons, deb maintainer, and platform settings
- Use `packageGraalvmDeb`/`Dmg`/`Nsis` tasks instead of raw native image output

### CI/CD

- Simplify graalvm workflows with setup-nucleus graalvm option

---

## v1.3.2

**Released: 2026-03-02**

No user-facing changes (tag only).

---

## v1.3.1

**Released: 2026-03-02**

### Bug Fixes

- Use `packageGraalvmDeb`/`Dmg`/`Nsis` tasks instead of raw native image output
- Add missing native artifact downloads and `libx11-dev` to publish-plugin workflow
- Pass repository to `gh release` commands in graalvm workflow
- Remove custom icons from jewel-sample, use default icons instead

### CI/CD

- Configure jewel-sample `nativeDistributions` with icons, deb maintainer, and platform settings

---

## v1.3.0

**Released: 2026-03-02**

### New Features

- **GraalVM Native Image support (experimental)**: compile Compose Desktop apps into standalone native binaries with instant cold boot (~0.5s), lower memory usage, and smaller bundles
- **New `graalvm-runtime` module** (`nucleus.graalvm-runtime`): centralizes native-image bootstrap logic into a single `GraalVmInitializer.initialize()` call
- **Decorated Window module split**: `decorated-window` split into `decorated-window-core`, `decorated-window-jbr`, and `decorated-window-jni`
- **Linux HiDPI scaling support** with native GDK_SCALE handling
- CI workflow to release Jewel Sample as GraalVM native image on tags
- Auto-notarize macOS distributions in `packageDistributionForCurrentOS`

### Bug Fixes

- Replace `OBJC_ASSOCIATION_ASSIGN` with `RETAIN_NONATOMIC` to prevent dangling pointer on macOS
- Use per-platform winCodeSign archives to fix AppX build on Windows
- Resolve fontmanager loading on Linux native image
- Ensure Skiko library is extracted and loaded in GraalVM Native Image
- Use `onlyIf` instead of `enabled` for native build tasks (configuration cache compatibility)

### Documentation

- Comprehensive GraalVM Native Image guide for Compose Desktop
- macOS 26 window appearance guide for JVM and native image
- Linux HiDPI runtime documentation
- AOT cache documentation rewrite with motivation and Project Leyden reference
- Decorated window docs update with changelog and migration guide

### CI/CD

- GraalVM native-image build workflow for PR CI
- Migrate detekt to 2.0.0-alpha.2 for JDK 25 support

---

## Migration Guide: 1.2.x → 1.3.x

### Decorated Window: monolithic module split

The `decorated-window` module has been split into three modules:

| Before (1.2.x) | After (1.3.x) |
|-----------------|----------------|
| `nucleus.decorated-window` | `nucleus.decorated-window-core` (shared) |
| | `nucleus.decorated-window-jbr` (JBR implementation) |
| | `nucleus.decorated-window-jni` (JNI implementation, new) |

**Dependency update** — replace:

```kotlin
implementation("io.github.kdroidfilter:nucleus.decorated-window:<version>")
```

With one of:

```kotlin
// JBR-based (same behavior as before)
implementation("io.github.kdroidfilter:nucleus.decorated-window-jbr:<version>")

// JNI-based (no JBR dependency, works with GraalVM)
implementation("io.github.kdroidfilter:nucleus.decorated-window-jni:<version>")
```

**Breaking changes in `TitleBarColors`** — the following fields have been **removed**:

- `titlePaneButtonHoveredBackground`
- `titlePaneButtonPressedBackground`
- `titlePaneCloseButtonHoveredBackground`
- `titlePaneCloseButtonPressedBackground`

These platform-specific button state colors are now handled internally by each module's native implementation. If you were constructing `TitleBarColors` explicitly with these fields, remove them.

**No other code changes required** — all composable APIs (`DecoratedWindow`, `DecoratedDialog`, `TitleBar`, `DialogTitleBar`), scopes, and state types are identical. No import changes needed — the package remains `io.github.kdroidfilter.nucleus.window`.

See [Decorated Window](runtime/decorated-window.md) for full details on choosing between JBR and JNI.

### GraalVM Native Image support (experimental)

Nucleus now supports compiling Compose Desktop applications into standalone native binaries using GraalVM Native Image. This brings instant cold boot (~0.5 s), significantly lower memory usage (~100–150 MB vs ~300–400 MB on JVM), and smaller bundle sizes.

- New `graalvm {}` DSL block in `build.gradle.kts`
- `runWithNativeAgent` task to collect reflection metadata with the GraalVM tracing agent
- `packageGraalvmNative` task to compile and package the native binary
- Full packaging pipeline per platform: `.app` bundle on macOS, `.exe` + DLLs on Windows, ELF + `.so` on Linux
- Pre-configured `reachability-metadata.json` files in the example app for all three platforms
- New **`graalvm-runtime`** module (`nucleus.graalvm-runtime`) — centralizes all native-image bootstrap logic into a single `GraalVmInitializer.initialize()` call: Metal L&F, `java.home`/`java.library.path` setup, charset/fontmanager early init, Linux HiDPI detection, and GraalVM `@TargetClass` font substitutions for Windows and Linux
- Requires [BellSoft Liberica NIK 25](https://bell-sw.com/liberica-native-image-kit/) (full distribution)

!!! danger "Experimental"
    This feature requires significant configuration effort (reflection metadata) and is reserved for advanced developers. See [GraalVM Native Image](graalvm-native-image.md) for the full guide.
