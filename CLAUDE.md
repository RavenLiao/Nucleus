# ComposeDeskKit (Nucleus)

A multi-module Gradle plugin and runtime library toolkit for shipping production-ready JVM desktop applications on macOS, Windows, and Linux.

## Project Structure

- `core-runtime` - Executable type detection, single instance, deep links, platform detection, app metadata (`NucleusApp`)
- `aot-runtime` - AOT cache mode detection for JDK 25+ (Project Leyden)
- `updater-runtime` - Auto-update engine (GitHub/S3), SHA-512 verification, progress tracking, update level detection, post-update events
- `freedesktop-icons` - Type-safe freedesktop Icon Naming Specification constants (shared by notification-linux and launcher-linux)
- `notification-linux` - Freedesktop Desktop Notifications API via JNI (D-Bus org.freedesktop.Notifications)
- `notification-windows` - Windows Toast Notifications API via JNI (WinRT)
- `launcher-linux` - Unity Launcher API via JNI (badge, progress, urgency, quicklist via com.canonical.Unity.LauncherEntry + com.canonical.dbusmenu)
- `taskbar-progress` - Native taskbar/dock progress bar and attention requests (Windows ITaskbarList3, macOS NSDockTile, Linux delegates to launcher-linux)
- `darkmode-detector` - Reactive OS dark mode detection via JNI
- `system-color` - Reactive system accent color and high contrast detection via JNI
- `energy-manager` - Energy efficiency & screen-awake APIs
- `native-ssl` / `native-http` / `native-http-okhttp` / `native-http-ktor` - OS trust store integration
- `linux-hidpi` - Native HiDPI scale detection on Linux
- `graalvm-runtime` - GraalVM native-image bootstrap
- `decorated-window-core` - Shared types, layout, styling (design-system agnostic)
- `decorated-window-jbr` - JBR-based implementation (requires JetBrains Runtime)
- `decorated-window-jni` - JNI-based implementation (any JVM, GraalVM compatible)
- `decorated-window-jewel` - Jewel (IntelliJ theme) integration
- `decorated-window-material2` - Material 2 color mapping
- `decorated-window-material3` - Material 3 color mapping
- `plugin-build/plugin` - Gradle plugin for packaging & distribution
- `example` / `jewel-sample` / `sample-cmp` - Sample applications

## Build & Run

```bash
./gradlew run                                    # Run example app
./gradlew packageDistributionForCurrentOS        # Package for current OS
./gradlew packageReleaseDistributionForCurrentOS # Release build with ProGuard
./gradlew preMerge                               # Full CI verification
./gradlew reformatAll                            # Format all code
```

## Key Technologies

- Kotlin 2.3+ with Compose Desktop 1.10+
- JNI for all native interop (no JNA in runtime modules)
- JBR (JetBrains Runtime) API for decorated-window-jbr
- Gradle 9+ with version catalog (`gradle/libs.versions.toml`)
- Detekt + KtLint for code quality

## Development Notes

- Target: JDK 17+ runtime, JDK 25+ recommended for AOT
- JNI code: be careful with macOS ARC/retain and weak references
- Native modules use platform-specific JNI implementations — test on each OS
- Plugin is published via included build in `plugin-build/`
- Version catalog is the source of truth for all dependency versions
- `decorated-window-jni` is the recommended backend for new projects (fixes resize artifacts, true Windows fullscreen, GraalVM compatible)
- macOS Liquid Glass enabled by default via `macOsSdkVersion = "26.0"` (vtool SDK patching)

## GraalVM Native Image

- Reflection metadata is centralized in 3 levels — users no longer copy hundreds of entries:
    - **L1**: Generic cross-platform metadata shipped in `graalvm-runtime` JAR (`reachability-metadata.json` with ~300+ types)
    - **L2**: Oracle GraalVM Reachability Metadata Repository — auto-resolved for classpath deps (enabled by default, `metadataRepository {}` DSL)
    - **L3**: Platform-specific metadata (macOS/Windows/Linux) shipped inside the plugin JAR under `nucleus/graalvm/platform-metadata/`
- `graalvm-runtime` auto-includes `.svg`, `.ttf`, `.otf`, `composeResources/*`, `nucleus/native/*`, and `META-INF/services/*` via `native-image.properties` glob patterns
- The tracing agent (`runWithNativeAgent`) is only needed for app-specific reflection, uncommon libraries, and resource bundles
- Agent output is automatically deduplicated against library metadata on the classpath
- Sample apps have near-empty `reachability-metadata.json` — only app-specific entries remain
- `GraalVmInitializer.initialize()` must be the first call in `main()` for native-image builds
- Font substitutions (`@TargetClass`) in `graalvm-runtime` fix `InternalError: platform encoding not initialized` on Windows/Linux
- Only BellSoft Liberica NIK 25 (full) is supported — standard GraalVM CE lacks AWT support
