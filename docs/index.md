# Nucleus

<p align="center">
  <img src="assets/header.png" alt="Nucleus" />
</p>

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.kdroidfilter.nucleus?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.github.kdroidfilter.nucleus)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kdroidfilter/nucleus.core-runtime?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.kdroidfilter.nucleus)
[![Pre Merge Checks](https://github.com/kdroidFilter/Nucleus/actions/workflows/pre-merge.yaml/badge.svg)](https://github.com/kdroidFilter/Nucleus/actions/workflows/pre-merge.yaml)
[![License: MIT](https://img.shields.io/github/license/kdroidFilter/Nucleus)](https://github.com/kdroidFilter/Nucleus/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

**Nucleus is the native desktop platform for the JVM** — a complete toolkit that turns Compose Desktop into a production-grade alternative to Electron, with none of the compromises.

Electron brought cross-platform desktop apps to the masses, but at a cost: bloated bundles, sluggish performance, and a web runtime pretending to be native. Nucleus takes the opposite approach. It builds on the **JVM** — the most battle-tested runtime in history — and on **Compose Multiplatform** — the most advanced declarative UI framework available today — to deliver desktop applications that are truly native, truly fast, and truly lightweight.

Where Electron ships a browser, Nucleus ships a compiled application. Where Electron emulates, Nucleus integrates. Where Electron is an atom, **Nucleus is its full power unleashed**.

## The Promise

### Native without compromise

Every pixel is rendered by Skia, the same engine behind Chrome and Android. But unlike Electron, there is no DOM, no JavaScript bridge, no web runtime overhead. Window decorations, notifications, taskbar integration, system tray, global hotkeys, dark mode detection, accent colors — everything talks directly to the OS through **JNI**, not through an abstraction layer pretending to be native.

On macOS, Nucleus speaks Cocoa. On Windows, it speaks Win32 and WinRT. On Linux, it speaks D-Bus and X11. Natively. In each case.

### Performance at every level

- **~0.5s cold start** with GraalVM native image, **~1.5s** with AOT cache (Project Leyden) — faster than most Electron apps even after they've been "optimized"
- **100–150 MB RAM** as a native image vs. **300+ MB** for a typical Electron app doing the same job
- **No garbage collection pauses visible to the user** — the JVM's G1/ZGC collectors are decades ahead of anything in the browser runtime world
- **Full JIT compilation** when running on the JVM — hot paths get optimized at runtime, something a static binary can never do

### From high-level abstraction to bare metal

Nucleus meets you where you are:

- **Just ship an app?** — One Gradle DSL, 16 packaging formats, auto-update, code signing, notarization. Done.
- **Need native OS integration?** — Runtime libraries for notifications, launchers, taskbar progress, system colors, energy management, dark mode — all cross-platform, all via JNI.
- **Need to call a platform API directly?** — [Native Access](native-access/index.md) lets you write Kotlin/Native code and call it from the JVM with zero glue. No C, no JNI boilerplate, no build scripts.
- **Need maximum performance?** — [GraalVM native image](graalvm/index.md) compiles your entire app ahead of time into a standalone binary. Instant startup, minimal RAM, no JRE bundled.

### The most advanced UI framework

Compose Multiplatform is not "React Native for desktop". It is a **compiled, type-safe, GPU-accelerated UI toolkit** with:

- A reactive state model that makes React look verbose
- Hardware-accelerated rendering via Skia — animations at 120fps without thinking about it
- A component model that scales from a button to a full IDE (JetBrains builds their entire product line with it)
- Shared code across Android, iOS, desktop, and web — write once, render natively everywhere

Nucleus extends this foundation with decorated windows, platform-accurate window controls, Material 2/3 and Jewel (IntelliJ theme) integration, and deep OS hooks that make your Compose app feel indistinguishable from a native one.

## What Nucleus provides

### Distribution

- **16 packaging formats** — DMG, PKG, NSIS, MSI, AppX, Portable, DEB, RPM, AppImage, Snap, Flatpak, ZIP, TAR, 7Z
- **Store-ready outputs** — Mac App Store (PKG), Microsoft Store (AppX), Snapcraft (Snap), Flathub (Flatpak)
- **Code signing & notarization** — Windows (PFX, Azure Trusted Signing) and macOS (Apple Developer ID)
- **Auto-update** — Built-in update metadata generation and runtime update library with SHA-512 verification; publish to GitHub Releases or S3

### Native integration

- **Decorated windows** — Custom title bar content while preserving native window controls on all platforms
- **Notifications** — Full native notification APIs on macOS (UserNotifications), Windows (Toast/WinRT), and Linux (freedesktop)
- **Launchers** — Badge counts, progress bars, jump lists, overlay icons, dock menus, quicklists — per-platform, via JNI
- **System APIs** — Dark mode detection, accent colors, high contrast, energy management, system info (CPU/GPU/memory metrics), global hotkeys, taskbar progress
- **Native Access** — Write Kotlin/Native, call it from the JVM. The plugin generates the FFM bridge automatically.

### Performance

- **AOT Cache (Project Leyden)** — Near-instant cold startup with one Gradle flag, no GraalVM required
- **GraalVM Native Image** — Standalone binary with transparent metadata resolution (5-level automatic analysis, zero manual config for most apps)
- **Linux HiDPI** — Native scale detection via JNI for crisp rendering on high-DPI displays

### CI/CD

- **Reusable GitHub Actions** — `setup-nucleus` composite action + workflows for multi-platform matrix builds, universal macOS binaries, MSIX bundles
- **Deep links & file associations** — Cross-platform protocol handlers and file type registration

## Quick start

```kotlin
plugins {
    id("io.github.kdroidfilter.nucleus") version "<version>"
}

nucleus.application {
    mainClass = "com.example.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "MyApp"
        packageVersion = "1.0.0"
    }
}
```

```bash
./gradlew run                           # Run locally
./gradlew packageDistributionForCurrentOS  # Build installer for current OS
```

## Try the demo

A pre-built demo application is available on the [GitHub Releases page](https://github.com/kdroidFilter/Nucleus/releases). Download the installer for your platform and see Nucleus in action.

=== "macOS"
    ```bash
    curl -fsSL https://nucleus.kdroidfilter.com/install.sh | bash
    ```
    Detects your architecture (Apple Silicon or Intel), downloads, installs to `/Applications`, and launches.

=== "Linux"
    ```bash
    curl -fsSL https://nucleus.kdroidfilter.com/install-linux.sh | bash
    ```
    Detects your architecture and package manager, downloads and installs the appropriate `.deb` or `.rpm`.

=== "Windows"
    Download the installer from the [releases page](https://github.com/kdroidFilter/Nucleus/releases).

What you'll see:

- **AOT Cache** — Near-instant cold startup powered by JDK 25+
- **Decorated Window** — Custom title bar with native window controls, Material 3 themed
- **Dark Mode Detection** — Toggle your OS theme and watch the app switch in real time
- **Auto-Update** — Checks for updates on launch, downloads with progress tracking, installs & restarts in one click

!!! tip "Test auto-update"
    Download an **older release**, install it, and launch. It will detect the newer version and offer to update — automatically.

The demo source code is in the [`example/`](https://github.com/kdroidFilter/Nucleus/tree/main/example) directory.

## Requirements

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | JBR 25 recommended |
| Gradle | 8.0+ | |
| Kotlin | 2.0+ | |

## License

MIT — See [LICENSE](https://github.com/kdroidFilter/Nucleus/blob/main/LICENSE).
