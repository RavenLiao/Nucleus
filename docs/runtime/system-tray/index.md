# System Tray

!!! note "Separate repository"
    System Tray is maintained in its own repository with its own release cycle: [**kdroidFilter/ComposeNativeTray**](https://github.com/kdroidFilter/ComposeNativeTray). The artifact is `io.github.kdroidfilter:composenativetray`.

**ComposeNativeTray** is not just a tray icon library — it is a **complete system tray framework** for Compose Desktop. It is the simplest and most powerful way to create system tray applications today, on any platform.

Where the standard Java `SystemTray` gives you a pixelated icon and a crude AWT menu that looks like Windows 95, ComposeNativeTray gives you **native menus**, **reactive state**, **HiDPI icons**, **dark mode awareness**, and a **pure Compose DSL** — on macOS, Windows, and Linux.

## Why not just use AWT SystemTray?

| | AWT SystemTray | ComposeNativeTray |
|---|---|---|
| Menu appearance | AWT popup (looks broken on modern OS) | Native platform menu |
| Icon quality | Pixelated, no HiDPI | Crisp on every display |
| Dark mode | No support | Automatic theme adaptation |
| Menu updates | Rebuild the entire menu manually | Compose recomposition — just change state |
| Icon types | `BufferedImage` only | `ImageVector`, `Painter`, `DrawableResource`, any `@Composable` |
| Submenus | Clunky | Native nested submenus with icons |
| Checkable items | Not available | Native checkable items |
| Primary action (left-click) | Not available | Per-platform behavior |
| Tray position detection | Not available | Built-in, for window positioning |
| Popup window | Not available | `TrayApp` — Compose window anchored to tray |

## Quick example

```kotlin
var isDarkMode by remember { mutableStateOf(false) }

Tray(
    icon = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
    tooltip = "My App",
    primaryAction = { showWindow() },
) {
    Item(label = "Show Window") { showWindow() }
    Divider()
    CheckableItem(label = "Dark Mode", checked = isDarkMode) {
        isDarkMode = !isDarkMode
    }
    SubMenu(label = "Options") {
        Item(label = "Settings") { openSettings() }
        Item(label = "About") { openAbout() }
    }
    Divider()
    Item(label = "Quit") { exitProcess(0) }
}
```

The menu is fully reactive — change `isDarkMode` and the icon, label, and checkmark all update instantly. No manual menu rebuild.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composenativetray:<version>")
}
```

## Next steps

- [Tray API](tray-api.md) — `Tray()` composable, icon types, primary actions, platform-specific icons
- [Menu DSL](menu-dsl.md) — Items, checkable items, submenus, dividers, icons in menus
- [TrayApp](tray-app.md) — Popup window anchored to the tray icon
- [Utilities](utilities.md) — Tray position detection, dark mode detection
