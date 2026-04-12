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

**Nucleus is the native desktop platform for the JVM** — a complete toolkit that makes Compose Desktop production-ready for macOS, Windows, and Linux.

Every technology eventually finds its mature form. Java evolved into **Kotlin**. JavaScript evolved into **TypeScript**. Desktop development is going through the same shift: Electron was the pioneer — it proved that cross-platform desktop apps could work. **Nucleus + Compose** is what comes next.

Not an alternative. An evolution.

Electron gave developers reach but asked them to accept a browser as a runtime, a DOM as a UI layer, and hundreds of megabytes as a baseline. Nucleus builds on the **JVM** — the most battle-tested runtime in history — and on **Compose Multiplatform** — the most advanced declarative UI toolkit available today — to deliver desktop applications that are natively compiled, natively integrated, and natively fast. No browser engine. No JavaScript bridge. No compromise.

## The Promise

### Native without compromise

Every pixel is rendered by Skia, the same engine behind Chrome and Android. But unlike Electron, there is no DOM, no JavaScript bridge, no web runtime overhead. Window decorations, notifications, taskbar integration, system tray, global hotkeys, dark mode detection, accent colors — everything talks directly to the OS through **JNI**, not through an abstraction layer pretending to be native.

On macOS, Nucleus speaks Cocoa. On Windows, it speaks Win32 and WinRT. On Linux, it speaks D-Bus and X11. Natively. In each case.

### Performance at every level

- **~0.5s cold start** with GraalVM native image, **~1.5s** with AOT cache (Project Leyden) — faster than most Electron apps even after they've been "optimized"
- **100–150 MB RAM** as a native image vs. **500 MB–1.5 GB** for a typical Electron app doing the same job
- **No garbage collection pauses visible to the user** — the JVM's G1/ZGC collectors are decades ahead of anything in the browser runtime world
- **Full JIT compilation** when running on the JVM — hot paths get optimized at runtime, something a static binary can never do

### From high-level abstraction to bare metal

Nucleus meets you where you are:

- **Just ship an app?** — One Gradle DSL, 16 packaging formats, auto-update, code signing, notarization. Done.
- **Need native OS integration?** — Runtime libraries for notifications, launchers, taskbar progress, system colors, energy management, dark mode — all cross-platform, all via JNI.
- **Need to call a platform API directly?** — [Native Access](native-access/index.md) lets you write Kotlin/Native code and call it from the JVM with zero glue. No C, no JNI boilerplate, no build scripts.
- **Need maximum performance?** — [GraalVM native image](graalvm/index.md) compiles your entire app ahead of time into a standalone binary. Instant startup, minimal RAM, no JRE bundled.

### The most advanced desktop UI stack in existence

Compose Multiplatform is not "React Native for desktop". It is a **compiled, type-safe, GPU-accelerated UI toolkit** built for desktop from the ground up:

- A reactive state model that makes React look verbose
- Hardware-accelerated rendering via Skia — animations at 120fps without thinking about it
- Shared code across Android, iOS, desktop, and web — write once, render natively everywhere

And on top of Compose sits **[Jewel](https://github.com/JetBrains/jewel)** — the most advanced desktop UI framework in the world. Not a web framework adapted for desktop. A desktop framework, period. Jewel carries behind it the entire experience of JetBrains and its IDEs — IntelliJ IDEA, Android Studio, Fleet — applications used daily by millions of developers and built with desktop in mind from day one. It is the same design system, the same component library, the same pixel-perfect attention to detail that powers the tools professionals trust most.

Nucleus integrates deeply with both: decorated windows with native window controls, Material 2/3 color mapping, Jewel theme integration, and OS-level hooks that make your Compose app feel indistinguishable from a native one.

## What Nucleus provides

### Ship everywhere

- **16 packaging formats** — DMG, PKG, NSIS, MSI, AppX, Portable, DEB, RPM, AppImage, Snap, Flatpak, ZIP, TAR, 7Z
- **Store-ready** — Mac App Store, Microsoft Store, Snapcraft, Flathub — one build, all stores
- **Code signing & notarization** — Windows and macOS, built into the build pipeline
- **Auto-update** — Your app checks for updates, downloads them, verifies integrity, and installs — all built-in
- **Deep links & file associations** — Protocol handlers and file type registration on all platforms

### Feel native on every OS

- **Decorated windows** — Custom title bar with your own content, while the OS keeps its native window controls
- **Notifications** — Native notification APIs on every platform, not browser-style popups
- **Launchers** — Badge counts, progress bars, jump lists, dock menus, quicklists — what each OS does best
- **System integration** — Dark mode, accent colors, high contrast, energy management, global hotkeys, taskbar progress, system info
- **Pixel-perfect rendering** — Crisp on every display, every DPI, every platform

### Perform like native code

- **Blazing cold start** — Sub-second startup with GraalVM native image, near-instant with AOT cache — a single Gradle flag
- **The world's most advanced JIT** — The HotSpot JVM optimizes your hot paths at runtime with decades of engineering behind it. No static binary can match sustained JVM throughput.
- **Lightweight** — 100–150 MB RAM as a native image. Your app, not a browser engine.
- **GraalVM native image** — Compile your entire app ahead of time into a standalone binary. Nucleus resolves all reflection metadata transparently — zero manual config for most apps.

### Go deeper when you need to

- **Native Access** — Call any platform API from Kotlin. Write Kotlin/Native, the plugin generates the bridge. No C, no JNI, no build scripts.
- **30+ JNI runtime modules** — Notifications, launchers, dark mode, system colors, taskbar, energy, HiDPI — all via JNI, all cross-platform
- **CI/CD ready** — Reusable GitHub Actions, multi-platform matrix builds, universal macOS binaries, MSIX bundles

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
