# `app.stmobile.service`

This package encapsulates the Android system service layer, foreground notification lifecycle, and decoupled server state management for ST-Mobile.

## Architecture

The service package bridges Android system components (`Service`, `NotificationManager`) with coroutine-based application state. It enforces a strict **active-only** lifecycle: the Android service runs exclusively while the local Node.js server is `STARTING`, `RUNNING`, or `STOPPING`. The moment the server stops or crashes, the foreground notification is removed and the service stops itself (`stopSelf()`), ensuring zero idle background footprint.

## Files

- **`NodeForegroundService.kt`**: Minimal Android `Service` implementation handling system lifecycle contracts (`startForeground`, `stopForeground`, `stopSelf`). Conforms to Android 14+ (`API 34`) `specialUse` foreground service requirements and satisfies the 5-second `startForeground` startup window.
- **`ServerLifecycleManager.kt`**: Decoupled singleton repository managing the SillyTavern Node.js process lifecycle, state transitions (`StateFlow<NodeStatus>`), worker threads, TCP/HTTP readiness polling, process supervisor threads, and background idle timeouts.
- **`ServiceNotificationHelper.kt`**: Helper encapsulating notification channel registration, ongoing foreground notification building with action `PendingIntent`s (`[ Open ]` -> `MainActivity`, `[ Stop ]` -> `NodeForegroundService`), and API-level foreground dispatch.
