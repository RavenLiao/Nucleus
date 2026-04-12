# Utilities

ComposeNativeTray includes several utilities that are essential for tray-based applications.

## Tray Position Detection

Position a window next to the tray icon — essential for `TrayApp`-style popup windows:

```kotlin
// Get the optimal window position anchored to the tray icon
val position = getTrayWindowPosition(width = 300, height = 400)

// Or detect which corner/edge the tray is on
val trayPosition: TrayPosition? = getTrayPosition()
// Returns: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, or BOTTOM_RIGHT
```

Works on all platforms — uses native APIs to detect the tray icon click position and compute the best window placement.

## Dark Mode Detection

Detect whether the menu bar / system tray area is in dark mode — useful for tinting icons:

```kotlin
@Composable
fun MyTray() {
    val isDark = isMenuBarInDarkMode()

    Tray(
        icon = Icons.Default.Notifications,
        tint = if (isDark) Color.White else Color.Black,
        tooltip = "My App",
    ) { /* menu */ }
}
```

Platform behavior:

| Platform | Detection method |
|----------|-----------------|
| macOS | Menu bar color (adapts to wallpaper on macOS Ventura+) |
| Windows | System theme setting |
| Linux (GNOME/XFCE/Cinnamon/MATE) | Always reports dark (panel is dark) |
| Linux (KDE) | System theme setting |

The value updates reactively — if the user changes their theme, the tray icon adapts instantly.
