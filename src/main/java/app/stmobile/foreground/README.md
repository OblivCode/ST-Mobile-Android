# `app.stmobile.foreground`

This package encapsulates the Android OS Foreground Service hosting, dynamic power management, idle timeout countdowns, and ongoing notifications for ST-Mobile.

## Components

- **`ForegroundService.kt`**: Android `Service` implementation with active-only lifecycle (`specialUse`). Hosts the server session, coordinates source-identified generation events (`activeSources: Set<String>`), and manages wake locks, timeouts, and notifications.
- **`WakeLockHelper.kt`**: Dynamically acquires `PowerManager.PARTIAL_WAKE_LOCK` strictly while generation is active, with a 15-minute maximum timeout safety cap and crash cleanup.
- **`BackgroundTimeoutManager.kt`**: Scheduled idle countdown timer that triggers server shutdown when the app remains backgrounded past the configured limit, with pause/resume support during active generation.
- **`ServiceNotificationHelper.kt`**: Encapsulates notification channel creation and builds notifications with dynamic statuses and native chronometer countdowns.
