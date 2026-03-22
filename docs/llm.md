# LLM Documentation

Nucleus provides machine-readable documentation files designed for Large Language Models (LLMs). These plain-text files follow the [llms.txt](https://llmstxt.org/) convention and allow AI assistants to quickly understand the project, its APIs, and configuration options.

## Available Files

| File | Description | Use Case |
|------|-------------|----------|
| [`llms.txt`](../llms.txt) | Concise overview (~145 lines) | Quick context for simple questions |
| [`llms-full.txt`](../llms-full.txt) | Complete documentation (~1900 lines) | Full reference for code generation and in-depth tasks |

## Usage

### ChatGPT, Claude, Gemini, etc.

Paste the URL directly in your prompt:

```
Read https://nucleus.kdroidfilter.com/llms-full.txt and help me configure
a Nucleus project with NSIS installer, auto-update, and macOS signing.
```

### Cursor, Windsurf, Claude Code

Add the URL as a documentation source in your AI-powered IDE, or reference it in your project instructions:

```
@doc https://nucleus.kdroidfilter.com/llms-full.txt
```

### Custom Agents / RAG Pipelines

Fetch the files programmatically:

```bash
curl -s https://nucleus.kdroidfilter.com/llms.txt       # concise
curl -s https://nucleus.kdroidfilter.com/llms-full.txt   # complete
```

## What's Included

**`llms.txt`** covers:

- Project overview and key features
- Quick start snippet
- Runtime libraries summary (all 18 libraries)
- Links to all documentation pages
- Migration guide from `org.jetbrains.compose`

**`llms-full.txt`** covers everything above plus:

- Full Gradle DSL reference (all properties and enums)
- Platform-specific configuration (macOS, Windows, Linux)
- macOS 26 Liquid Glass and SDK version patching
- Sandboxing pipeline details
- Code signing and notarization (Windows PFX, Azure Trusted Signing, macOS Developer ID)
- Auto-update runtime API with Compose integration example
- Publishing to GitHub Releases and S3
- CI/CD workflows and all composite actions
- GraalVM Native Image configuration and DSL reference
- All runtime APIs with code examples:
    - Executable type, AOT cache, single instance, deep links
    - App metadata (`NucleusApp` — `appId`, `version`, `vendor`, `description`)
    - Taskbar progress (Windows ITaskbarList3, macOS NSDockTile, Linux D-Bus)
    - Auto-update with `UpdateLevel` (major/minor/patch) and post-update detection (`consumeUpdateEvent`, `wasJustUpdated`)
    - Decorated window (JBR and JNI backends, fullscreen controls, large corner radius, `controlButtonsDirection`, `clientRegion`, `backgroundContent`)
    - Design system wrappers (Material 3, Material 2, Jewel)
    - Dark mode detector, system color, energy manager
    - Native SSL, native HTTP (java.net.http, OkHttp, Ktor)
    - Linux HiDPI, GraalVM runtime bootstrap
