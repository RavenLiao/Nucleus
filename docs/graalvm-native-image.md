# GraalVM Native Image

!!! danger "Experimental — for advanced developers only"
    GraalVM Native Image compilation for Compose Desktop is **highly experimental**. If reflection is not fully resolved at build time, the application **will crash at runtime**. This mode requires significant effort to configure and debug. Proceed only if you are comfortable with native-image internals.

## Why Native Image?

For most Compose Desktop applications, [AOT Cache (Leyden)](runtime/aot-cache.md) is the recommended way to improve startup. It's simple to set up and provides a major boost. But there are cases where even Leyden isn't enough:

- **Background services / system tray apps** — a lightweight app that mostly sits idle in the background will consume **300–400 MB of RAM** on a JVM, versus **100–150 MB** as a native image. For an app that's always running, this matters.
- **Instant-launch expectations** — Leyden brings cold boot down to ~1.5 s, but a native image starts in ~0.5 s. For utilities, launchers, or CLI-like tools where every millisecond counts, native image is the way to go.
- **Bundle size** — no bundled JRE means a much smaller distributable.

GraalVM Native Image compiles your entire application **ahead of time** into a standalone native binary that feels truly native to the OS.

### Trade-offs

Native image is not a free lunch. In addition to significantly more complex configuration (reflection, see below), there is a real **CPU throughput penalty**: the JVM's JIT compiler optimizes hot loops and polymorphic calls at runtime far better than AOT compilation can. For CPU-intensive workloads (heavy computation, real-time rendering, large data processing), a JVM with Leyden AOT cache will outperform a native image in sustained throughput.

| | JVM + Leyden | Native Image |
|---|---|---|
| Cold boot | ~1.5 s | ~0.5 s |
| RAM (idle) | 300–400 MB | 100–150 MB |
| CPU throughput | Excellent (JIT) | Lower (no JIT) |
| Bundle size | Larger (includes JRE) | Smaller |
| Configuration | Simple (`enableAotCache = true`) | Simplified (centralized metadata) |
| Stability | Stable | Experimental |

**Choose native image when** startup speed and memory footprint are critical and CPU throughput is secondary. **Choose Leyden when** you want the best balance of performance, simplicity, and stability.

## Requirements

### BellSoft Liberica NIK 25 (Full)

GraalVM Native Image compilation **requires [BellSoft Liberica NIK 25](https://bell-sw.com/liberica-native-image-kit/)** (full distribution, not lite). This is the only supported distribution — standard GraalVM CE does not include the AWT/Swing support needed for desktop GUI applications.

!!! failure "Will not work with other distributions"
    Using Oracle GraalVM, GraalVM CE, or Liberica NIK Lite will fail. Desktop GUI applications require the **full** Liberica NIK distribution which includes AWT and Swing native-image support.

### Platform toolchains

| Platform | Required |
|----------|----------|
| **macOS** | Xcode Command Line Tools (Xcode 26 for macOS 26 appearance) |
| **Windows** | MSVC (Visual Studio Build Tools) — `ilammy/msvc-dev-cmd` in CI |
| **Linux** | GCC, `patchelf`, `xvfb` (for headless compilation) |

## Build Configuration

### Gradle DSL

```kotlin
nucleus.application {
    mainClass = "com.example.MainKt"

    graalvm {
        isEnabled = true
        imageName = "my-app"

        // Gradle Java Toolchain: auto-downloads Liberica NIK 25
        // if it's not already installed on the machine.
        // In CI, the JDK is set up by graalvm/setup-graalvm@v1 instead.
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT

        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )

        // Optional: customize Oracle Reachability Metadata Repository
        metadataRepository {
            enabled = true              // default
            version = "0.10.6"          // default
            excludedModules.add("com.example:my-lib")
        }

        // Optional: point to your own app-specific metadata
        nativeImageConfigBaseDir.set(
            layout.projectDirectory.dir("src/main/graalvm-config"),
        )
    }
}
```

!!! success "No more platform-switching boilerplate"
    In previous versions, you had to set `nativeImageConfigBaseDir` with a `when` block selecting between `resources-macos`, `resources-windows`, and `resources-linux` directories. **This is no longer needed.** Nucleus now ships all generic and platform-specific reflection metadata automatically. The `nativeImageConfigBaseDir` is only needed if you have app-specific entries that the tracing agent or the centralized metadata don't cover.

### DSL Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `isEnabled` | `Boolean` | `false` | Enable GraalVM native compilation |
| `javaLanguageVersion` | `Int` | `25` | Gradle toolchain language version — triggers auto-download of the matching JDK if not installed locally |
| `jvmVendor` | `JvmVendorSpec` | — | Gradle toolchain vendor filter — set to `BELLSOFT` to auto-provision Liberica NIK |
| `imageName` | `String` | project name | Output executable name |
| `march` | `String` | `"native"` | CPU architecture target (`native` for current CPU, `compatibility` for broad compatibility) |
| `buildArgs` | `ListProperty<String>` | empty | Extra arguments passed to `native-image` |
| `nativeImageConfigBaseDir` | `DirectoryProperty` | — | Directory containing app-specific `reachability-metadata.json` (optional — generic/platform metadata is now built-in) |
| `metadataRepository` | `MetadataRepositorySettings` | enabled | Oracle GraalVM Reachability Metadata Repository settings (see below) |

#### `metadataRepository` DSL Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | `Boolean` | `true` | Whether to auto-resolve metadata from the Oracle repository for classpath dependencies |
| `version` | `String` | `"0.10.6"` | Version of the metadata repository artifact |
| `excludedModules` | `SetProperty<String>` | empty | Module coordinates (`group:artifact`) to exclude from repository resolution |
| `moduleToConfigVersion` | `MapProperty<String, String>` | empty | Override the metadata version for specific modules (key: `group:artifact`, value: version directory) |

### Recommended build arguments

| Argument | Purpose |
|----------|---------|
| `-H:+AddAllCharsets` | Include all character sets (required for text I/O) |
| `-Djava.awt.headless=false` | Enable GUI support (mandatory for desktop apps) |
| `-Os` | Optimize for binary size |
| `-H:-IncludeMethodData` | Reduce binary size by excluding method metadata |

## Centralized Reflection Metadata

Starting with v1.6.0, Nucleus **centralizes all generic and platform-specific reflection metadata** so you no longer need to copy thousands of entries from the example app. The metadata is organized in three levels:

### Level 1 — Generic metadata (shipped in `graalvm-runtime` JAR)

The `graalvm-runtime` module ships a `reachability-metadata.json` inside its JAR that covers all cross-platform reflection entries: Compose Desktop, AWT/Swing, Skiko, security providers, font managers, and more (~300+ types). This metadata is **automatically picked up** by native-image from the classpath — no configuration needed.

### Level 2 — Oracle Reachability Metadata Repository

Nucleus automatically downloads the [Oracle GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata) and resolves metadata for all dependencies on your runtime classpath. This covers popular libraries like ktor, kotlinx.serialization, SLF4J, Logback, and many others. The resolved metadata directories are passed to `native-image` via `-H:ConfigurationFileDirectories=`.

This is enabled by default. To customize:

```kotlin
graalvm {
    metadataRepository {
        enabled = true                    // disable with false
        version = "0.10.6"               // override repository version
        excludedModules.add("group:artifact")  // skip specific dependencies
        moduleToConfigVersion.put(        // pin a specific metadata version
            "io.ktor:ktor-client-core",
            "3.0.0",
        )
    }
}
```

### Level 3 — Platform-specific metadata (shipped in the plugin)

The Nucleus Gradle plugin ships pre-built platform-specific metadata for macOS, Windows, and Linux. These cover platform-specific AWT implementations (`sun.awt.windows.*`, `sun.lwawt.macosx.*`, `sun.awt.X11.*`), Java2D pipelines, font managers, and security providers. The plugin writes the correct platform metadata to the build directory at compile time — **no per-platform `when` block needed in your build script**.

### What you still need

With these three levels, **most applications will work out of the box** without any manual reflection configuration. You only need the tracing agent or manual entries for:

- **Your own app-specific reflection** — classes in your own code that use `Class.forName()`, etc.
- **Uncommon libraries** not covered by the Oracle repository
- **Edge cases** where the centralized metadata is incomplete for your specific code paths

## Reflection Configuration (App-Specific)

For most projects, the centralized metadata covers all framework-level reflection. If your app uses reflection directly or depends on libraries not covered by the Oracle repository, you can add app-specific entries.

### Run the tracing agent

Nucleus provides a Gradle task that runs your application with the GraalVM tracing agent. The agent records all reflection, JNI, resource, and proxy accesses and **merges** the results into your existing configuration (your manual entries are never overwritten):

```bash
./gradlew runWithNativeAgent
```

The goal is to **trigger every code path that uses reflection**. During the tracing run:

- **Navigate to every screen** of your application
- **Toggle dark/light theme** — if your icons differ between themes, both variants must be loaded
- **Open every dialog**, menu, tooltip, and dropdown
- **Trigger all lazy-loaded content** (expand lists, scroll to bottom, etc.)
- **Exercise all features** that load resources dynamically

!!! success "SVG icons, fonts, and Compose resources are included automatically"
    If you depend on `nucleus.graalvm-runtime`, all `.svg`, `.ttf`, `.otf` resources, all `composeResources/`, all `nucleus/native/` JNI libraries, and all `META-INF/services/` descriptors are included in the native binary automatically. You do **not** need the tracing agent to discover these — they just work. See [Automatic Resource Inclusion](#automatic-resource-inclusion).

!!! warning "Reflection and resource bundles still require the agent"
    While resources are covered automatically, **reflection metadata** and **resource bundles** (locale-specific `.properties` files) for your own code still require the tracing agent.

!!! tip "Prefer Kotlin-generated icons over resource files"
    For maximum control over binary size, convert your icons to Kotlin `ImageVector` definitions (using tools like [Composables](https://composables.com/svgtocompose) or the Material Icons library). Kotlin-generated icons are compiled directly into the binary and require no reflection or resource resolution.

The agent output is automatically merged into your `nativeImageConfigBaseDir`. Entries already covered by library metadata (Level 1/2/3) are automatically deduplicated — the agent won't bloat your config with entries that Nucleus already provides.

### Review and fix the configuration

The agent captures most reflection calls, but it **cannot capture code paths that weren't exercised** during the tracing run. You may need to manually add or adjust entries in `reachability-metadata.json`.

For example, the ktor networking library requires manually adding:

```json
{
    "type": "io.ktor.network.selector.InterestSuspensionsMap",
    "allDeclaredFields": true
}
```

The agent might have generated an entry for this class, but without `allDeclaredFields: true` — causing a runtime crash when ktor tries to access fields reflectively.

!!! tip "Debugging missing reflection"
    Run your native binary from the terminal. Reflection failures produce clear error messages like `Class not found` or `No such field`. Add the missing entries and recompile.

### Repeat on each platform

While most reflection is now centralized, **your own app-specific entries** may still differ per platform. If you have custom entries, run the tracing agent on each platform separately. The centralized metadata handles all framework-level platform differences automatically.

## Application Bootstrap

### `graalvm-runtime` module

The `graalvm-runtime` module provides everything needed to bootstrap a Compose Desktop application in a GraalVM native image. Add it to your dependencies:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.graalvm-runtime:<version>")
}
```

Then call `GraalVmInitializer.initialize()` as the **first line** of your `main()` function, before any AWT or Compose usage:

```kotlin
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer

fun main() {
    GraalVmInitializer.initialize()

    application {
        Window(onCloseRequest = ::exitApplication, title = "MyApp") {
            App()
        }
    }
}
```

The initializer handles all of the following automatically:

| Concern | What it does |
|---------|--------------|
| **Metal L&F** | Sets `swing.defaultlaf` to avoid unsupported platform modules |
| **`java.home`** | Points to the executable directory so Skiko finds jawt |
| **`java.library.path`** | Sets `execDir` + `execDir/bin` so fontmanager/freetype/awt are discoverable |
| **Charset init** | Forces early `Charset.defaultCharset()` to prevent `InternalError: platform encoding not initialized` |
| **Fontmanager preload** | Calls `System.loadLibrary("fontmanager")` early to avoid crashes in `Font.createFont()` |
| **Linux HiDPI** | Detects and applies the native scale factor via [`linux-hidpi`](runtime/linux-hidpi.md) (works in both JVM and native image) |

The native-image-specific steps only run when `org.graalvm.nativeimage.imagecode` is set. The Linux HiDPI detection runs unconditionally (it's a no-op on non-Linux platforms).

You can also check `GraalVmInitializer.isNativeImage` at any point to branch on native-image vs JVM execution.

### Font substitutions

The module also ships GraalVM `@TargetClass` substitutions (Java source files) that fix font-related crashes in native image on Windows and Linux:

- **`FontCreateFontSubstitution`** — Buffers `Font.createFont(int, InputStream)` to a temp file on Windows, working around streams that lack mark/reset support in native image.
- **`Win32FontManagerSubstitution`** — Replaces `Win32FontManager.getFontPath()` with a pure-Java implementation, fixing `InternalError: platform encoding not initialized`.
- **`FcFontManagerSubstitution`** — Fixes `FcFontManager.getFontPath()` on Linux native image.

These substitutions are automatically picked up by the native-image compiler — no configuration needed.

### Automatic Resource Inclusion

One of the most common pitfalls with GraalVM native-image is **missing resources at runtime**. Icons, fonts, and service descriptors must be explicitly registered — otherwise `Class.getResource()` returns `null` and your UI renders blank icons.

The `graalvm-runtime` module solves this automatically. It ships a `native-image.properties` file that registers broad resource patterns at compile time:

| Pattern | What it covers |
|---------|----------------|
| `.*\.(svg\|ttf\|otf)` | All SVG icons and font files on the classpath — Jewel, IntelliJ Platform icons, Compose resources, your own icons |
| `composeResources/.*` | All Compose Multiplatform resources (images, strings, fonts loaded via `Res.*`) |
| `nucleus/native/.*` | All Nucleus JNI native libraries (`.dll`, `.dylib`, `.so`) |
| `META-INF/services/.*` | All `ServiceLoader` descriptors (ktor, coil, SLF4J, etc.) |

This means:

- **All SVG icons work out of the box** — no need to run the tracing agent just to discover icons. Jewel's `PathIconKey`, `AllIconsKeys`, dark/light variants, `@2x` retina variants — everything is included automatically.
- **All fonts are embedded** — Inter, JetBrains Mono, or any custom `.ttf`/`.otf` in your dependencies.
- **All Compose Multiplatform resources are included** — images, strings, and other resources loaded via the `Res` API.
- **Service loaders resolve correctly** — ktor engines, coil fetchers, SLF4J providers, etc.

!!! note "Binary size trade-off"
    The glob pattern `.*\.(svg|ttf|otf)` includes **all** SVGs and fonts from **all** JARs on the classpath. If you depend on the IntelliJ Platform icons library, this may add several megabytes of icons you don't actually use. For most applications, the convenience far outweighs the size increase. If binary size is critical, you can override with more targeted patterns in your own `resource-config.json`.

!!! tip "What still needs the tracing agent?"
    The automatic resource patterns cover icons, fonts, Compose resources, native libraries, and service loaders. You **still** need the tracing agent (`runWithNativeAgent`) for:

    - **Reflection metadata** — classes in your own code accessed via `Class.forName()`, `getDeclaredField()`, etc.
    - **JNI metadata** — native methods in your own code accessed from C/C++
    - **Resource bundles** — locale-specific `.properties` files (AWT, Swing, Jewel i18n bundles)
    - **Non-standard resources** — `.sha256` checksums, `.class` files, `.properties` files, ICU data

### Decorated Window

The [`decorated-window-jni`](runtime/decorated-window.md) module was specifically designed to work with GraalVM Native Image (no JBR dependency). Use it instead of `decorated-window-jbr` for native-image builds.

## Gradle Tasks

| Task | Description |
|------|-------------|
| `runWithNativeAgent` | Run the app with the GraalVM tracing agent to collect reflection metadata |
| `resolveReachabilityMetadata` | Resolve Oracle Reachability Metadata Repository for classpath dependencies |
| `packageGraalvmNative` | Compile and package the application as a native binary |
| `runGraalvmNative` | Build and run the native image directly |
| `packageGraalvmDeb` | Package the native image as a `.deb` installer (Linux) |
| `packageGraalvmDmg` | Package the native image as a `.dmg` installer (macOS) |
| `packageGraalvmNsis` | Package the native image as an NSIS `.exe` installer (Windows) |

```bash
# Collect reflection metadata (run on each platform)
./gradlew runWithNativeAgent

# Build the raw native image
./gradlew packageGraalvmNative

# Build and run the native image
./gradlew runGraalvmNative

# Build platform-specific installers (requires Node.js for electron-builder)
./gradlew packageGraalvmDeb    # Linux
./gradlew packageGraalvmDmg    # macOS
./gradlew packageGraalvmNsis   # Windows

# NOTE: The `homepage` property is required in nativeDistributions for DEB packaging.
# electron-builder will fail without it. See Configuration > Package Metadata.
```

Use `-PnativeMarch=compatibility` for binaries that should run on older CPUs:

```bash
./gradlew packageGraalvmNative -PnativeMarch=compatibility
```

### Output location

The raw native binary and its companion shared libraries are generated in:

```
<project>/build/compose/tmp/<project>/graalvm/output/
```

| Platform | Output |
|----------|--------|
| **macOS** | `output/MyApp.app/` (full `.app` bundle with `Info.plist`, icons, signed dylibs) |
| **Windows** | `output/my-app.exe` + companion DLLs (`awt.dll`, `fontmanager.dll`, etc.) |
| **Linux** | `output/my-app` + companion `.so` files (`libawt.so`, `libfontmanager.so`, etc.) |

The `packageGraalvm<Format>` tasks produce installers in:

```
<project>/build/compose/binaries/<buildType>/graalvm-<format>/
```

## CI/CD

Native image compilation must happen **on each target platform**. Use `setup-nucleus` with `graalvm: 'true'`:

```yaml
name: Build GraalVM Native Image

on:
  push:
    tags: ["v*"]

jobs:
  build-natives:
    uses: ./.github/workflows/build-natives.yaml

  graalvm:
    needs: build-natives
    name: GraalVM - ${{ matrix.name }}
    runs-on: ${{ matrix.os }}
    timeout-minutes: 60
    strategy:
      fail-fast: false
      matrix:
        include:
          - name: Linux x64
            os: ubuntu-latest
          - name: macOS ARM64
            os: macos-latest
          - name: Windows x64
            os: windows-latest

    steps:
      - uses: actions/checkout@v4

      # Download pre-built JNI native libraries here...

      - name: Setup Nucleus (GraalVM)
        uses: kdroidFilter/Nucleus/.github/actions/setup-nucleus@main
        with:
          graalvm: 'true'
          setup-gradle: 'true'
          setup-node: 'true'  # Required for packageGraalvm<Format> tasks

      - name: Build GraalVM native packages
        shell: bash
        run: |
          if [ "$RUNNER_OS" = "Linux" ]; then
            ./gradlew :myapp:packageGraalvmDeb \
              -PnativeMarch=compatibility --no-daemon
          elif [ "$RUNNER_OS" = "macOS" ]; then
            ./gradlew :myapp:packageGraalvmDmg \
              -PnativeMarch=compatibility --no-daemon
          elif [ "$RUNNER_OS" = "Windows" ]; then
            ./gradlew :myapp:packageGraalvmNsis \
              -PnativeMarch=compatibility --no-daemon
          fi

      - uses: actions/upload-artifact@v4
        with:
          name: graalvm-${{ runner.os }}
          path: myapp/build/compose/binaries/**/graalvm-*/**
```

See [CI/CD](ci-cd.md#graalvm-native-image-release) for the full release workflow with publishing to GitHub Releases.

## No Release Build Type

Unlike standard JVM builds, GraalVM native-image builds **do not have a release variant**. There is no `packageReleaseGraalvmNative` or `runReleaseGraalvmNative` task. This is intentional:

- **ProGuard is redundant** — GraalVM native-image already performs closed-world dead code elimination at compile time. Running ProGuard beforehand provides no additional size benefit.
- **ProGuard is harmful** — ProGuard can rename or remove classes that are referenced in `reachability-metadata.json`, causing runtime crashes. Maintaining both ProGuard keep rules and reflection metadata is error-prone.

All GraalVM tasks use the default (non-ProGuard) build type. Use `-Os` in `buildArgs` for size optimization.

## Migration from v1.5.x

If you were using GraalVM native-image before v1.6.0, you can simplify your configuration:

### 1. Remove the platform-switching `nativeImageConfigBaseDir`

**Before (v1.5.x):**

```kotlin
graalvm {
    nativeImageConfigBaseDir.set(
        layout.projectDirectory.dir(
            when {
                org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                    "src/main/resources-macos/META-INF/native-image"
                org.gradle.internal.os.OperatingSystem.current().isWindows ->
                    "src/main/resources-windows/META-INF/native-image"
                org.gradle.internal.os.OperatingSystem.current().isLinux ->
                    "src/main/resources-linux/META-INF/native-image"
                else -> throw GradleException("Unsupported OS")
            },
        ),
    )
}
```

**After (v1.6.0):** Remove the entire `nativeImageConfigBaseDir` block. Nucleus ships all generic and platform-specific metadata automatically. Only set `nativeImageConfigBaseDir` if you have app-specific entries.

### 2. Clean up your `reachability-metadata.json` files

The per-platform `reachability-metadata.json` files in `src/main/resources-{macos,windows,linux}/` can be dramatically reduced. Remove all generic entries (Compose, AWT, Swing, security providers, etc.) — Nucleus now ships them. Keep only entries specific to your own application code.

If your metadata files only contained framework entries, you can delete them entirely and remove the `resources-{macos,windows,linux}` source sets.

### 3. Delete obsolete config files

If your per-platform metadata directories contained old-format files like `predefined-classes-config.json`, `proxy-config.json`, `resource-config.json`, or `serialization-config.json`, delete them. All configuration is now consolidated in `reachability-metadata.json`.

## Best Practices

### Avoid reflection-heavy libraries

Every library that uses reflection needs configuration. Prefer libraries that work without reflection (compile-time code generation, direct method calls). The Oracle Reachability Metadata Repository covers many popular libraries, but not all.

### Test on all platforms early

Don't wait until the end to test native-image on all three platforms. Each platform has its own set of reflection requirements and quirks. Test early and often.

### Use the Jewel sample as reference

The [`jewel-sample`](https://github.com/kdroidFilter/Nucleus/tree/main/jewel-sample) in the Nucleus repository demonstrates a more complex native-image setup with the Jewel UI library. It is an excellent reference for advanced use cases.

## Future: Automatic Reflection Resolution Plugin

!!! tip "Looking for sponsors"

The biggest remaining pain point of GraalVM Native Image is **app-specific reflection configuration**. While Nucleus now handles all framework-level metadata (Compose, AWT, Swing, security, fonts) and the Oracle repository covers many popular libraries, your own code and uncovered dependencies still require the tracing agent.

**It is technically feasible to build a Gradle plugin that would automatically resolve nearly all reflection requirements** for a Compose Desktop application. Such a plugin would:

- Analyze the classpath at build time and generate reflection/JNI/resource metadata automatically
- Cover Compose, AWT/Swing, Skiko, and common libraries (ktor, kotlinx.serialization, coil, etc.)
- Report libraries for which it could not resolve reflection, so the developer knows exactly what needs manual attention
- Eliminate the tracing agent step entirely for most applications

**If you or your company would like to sponsor this work, please reach out!** Contact me via [GitHub Issues](https://github.com/kdroidFilter/Nucleus/issues) or [GitHub Discussions](https://github.com/kdroidFilter/Nucleus/discussions).

## Further Reading

- [GraalVM Native Image documentation](https://www.graalvm.org/latest/reference-manual/native-image/)
- [BellSoft Liberica NIK](https://bell-sw.com/liberica-native-image-kit/)
- [Oracle GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Nucleus example app](https://github.com/kdroidFilter/Nucleus/tree/main/example) — minimal Compose Desktop + native-image setup
- [Nucleus Jewel sample](https://github.com/kdroidFilter/Nucleus/tree/main/jewel-sample) — advanced setup with reflection-heavy dependencies
