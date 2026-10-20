# Changelog

All notable changes to ST Mobile are recorded here. Each release heading uses the form
`## [vX.Y.Z-beta] - YYYY-MM-DD`, which the release workflow reads to build the GitHub Release notes.

## [v0.3.1-beta] - 2026-10-20

### Diagnostics and reliability
- **Generation state recovery**: WebView generation tokens are purged unconditionally when transitioning to the background or when the Node HTTP response finishes over IPC, preventing background idle timeouts from getting stalled on 'Generating response...'.
- **Generation watchdog**: Added a 10-minute safety watchdog that force-recovers idle countdowns if an upstream inference backend hangs indefinitely.
- **Release build lint fix**: Disabled the `ManifestResource` lint check in the release build configuration so localized manifest strings pass `lintVitalRelease`.

### Power and background behaviour
- **Notification timer deduplication**: Removed redundant 15-second text timer updates from the notification body, relying exclusively on the native Android countdown chronometer in the header whilst displaying 'Idle' in the status line.

## [v0.3.0-beta] - 2026-10-18

### UI and theming
- **App-wide theming**: System, Light and Dark themes now apply consistently across the Dashboard, Updates, Backups, install progress and dialogs. The Dashboard no longer stays dark when Light is selected.
- **Brand theme tokens**: Starting, stopping, running and error states use semantic colours that follow the active theme.
- **Unified settings controls**: Node Engine settings now use the same slider, switch and picker rows as the rest of the app.

### Performance and display
- **Smoother keyboard transitions**: The WebView snaps to the final keyboard height instead of resizing every frame, which avoids layout stutter on high refresh rate displays while keeping the chat input visible.
- **Persistent quick toolbar position**: The floating toolbar remembers its vertical position across launches and rotation, with a reset option in settings.
- **Hardware-aware limits**: The V8 heap ceiling adapts to the device's total memory (up to half of RAM, between 512 MB and 4096 MB). The libuv threadpool ceiling follows the number of available CPU cores.
- **Node environment**: Choose between `production` (default) and `development`.

### Power and background behaviour
- **Token-based wake lock retention**: Text generation, vector processing and the idle timeout hold separate tokens, so one finishing no longer releases the lock for another.
- **Live background countdown**: The status notification displays the native Android countdown chronometer in the header to show time remaining before the server stops, with status 'Idle' in the body text.
- **Completion alerts**: Optional pop-up notifications for text and vector completion, with a configurable auto-dismiss timeout. Only the status notification stays permanent.
- **Battery optimisation guidance**: A settings card detects restricted background usage and guides you to unrestricted mode.
- **Stop button**: The WebView quick toolbar can now stop the server directly.

### Settings and search
- **Settings search**: Search across App, SillyTavern, Node Engine and WebView settings, with in-place controls for results.
- **New SillyTavern mobile settings**: Smooth streaming and streaming frame rate, auto-scroll, chat width, delete confirmation, click to edit, swipe counts, input restoration, avatar resizing and style, and timestamps. SillyTavern's own defaults are left unchanged.
- **Reorganised settings**: Clear subsections, a dedicated WebView tab, and duplicate diagnostics and backup entries removed.

### Diagnostics and reliability
- **Persistent app log**: Rotating log file (5 MB) mirrored to Logcat, with a shareable diagnostics snapshot.
- **Crash reporting**: Uncaught exceptions are recorded with device, memory and server state before handing over to the system handler.
- **Hardening**: Safer process shutdown, backup restore recovery after interruption, stale IPC connection clean-up, and generation state recovery preventing stalled background idle timeouts.

### Localisation
- Full parity across English, German, Spanish, Japanese, Russian and Chinese.

## [v0.2.0-beta]

Navigation overhaul, universal backup migration and onboarding restore.

## [v0.1.0-beta]

First release: one-tap SillyTavern for Android.
