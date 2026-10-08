# `app.stmobile.ui`

This package houses the Jetpack Compose user interface, navigation framework, settings tabs, dialogs, and the native WebView container.

## Architecture

The UI is built with Jetpack Compose Material 3 in a dark cyberpunk palette. The navigation structure utilizes a decoupled `NavigationController` supporting tab-switching semantics across bottom dock destinations (`Dashboard`, `Updates`, `Backups`, `Settings`). When the server is active, `StWebView` remains warm and mounted underneath foreground screens to allow instant switching between management views and the chat client without reloading.

### Navigation Tab-Switching Semantics

The navigation system implements non-linear backstack behavior for bottom dock destinations:
- **No Linear Accumulation**: Switching between secondary dock tabs (`UPDATES`, `BACKUPS`, `SETTINGS`) replaces the active secondary tab rather than pushing repeated history entries onto the backstack (e.g. Dashboard -> Updates -> Backups -> Settings does not require multiple Back presses to unwind).
- **Direct Unwind**: When on any secondary dock tab, pressing the system Back key unwinds directly to `DASHBOARD` (or to `WEBVIEW` if the user entered from the WebView quick toolbar).
- **Dock Root Selection**: Tapping the `DASHBOARD` dock item unwinds the stack back to the root level.

## Files

- **`MainScreen.kt`**: Top-level Scaffold orchestrating the bottom navigation dock and cross-fading active screens (hosted by `MainActivity` in `app.stmobile`).
- **`Navigation.kt`**: State machine controlling screen backstacks, dock tab selection, and auto-launch routing.
- **`DashboardScreen.kt`**: Primary server control panel showing port, PID, status indicators, and one-tap server start/stop actions.
- **`UpdatesScreen.kt`**: Dedicated payload management view for checking OTA updates, inspecting version metadata, and triggering bundle reinstalls.
- **`BackupsScreen.kt`**: Centralized user data backup hub offering one-tap export, import, and encryption options.
- **`SettingsScreen.kt`**: Tabbed settings container hosting App, SillyTavern, and Node Engine preferences.
- **`AppSettingsTab.kt`**: Controls native shell preferences (auto-start, auto-launch, idle timeouts, language sync).
- **`SillyTavernSettingsTab.kt`**: Controls web client ergonomics (input styling, enter key behavior, font scaling, UI language).
- **`NodeEngineSettingsTab.kt`**: Configures low-level V8 flags (maximum heap old space size).
- **`BackupDialogs.kt`**: Modal dialogs for backup password entry, strategy selection (`Clean` vs. `Merge`), and export naming.
- **`StWebView.kt`**: Hardware-accelerated Android `WebView` wrapper featuring edge-docked quick toolbars, file chooser interception, and locale synchronizers. State holders for loaded and pending URLs are remembered outside Compose state to prevent recomposition loops.
- **`InstallProgressCard.kt`**: Shared progress indicator card displaying download/extraction percentage, bytes/files processed, transfer speeds, active file names, and an optional cancellation action. Used consistently by `DashboardScreen` and `UpdatesScreen`.
