# `app.stmobile.models`

This package defines immutable data models, state representations, and persistent configuration wrappers for ST-Mobile.

## Architecture

Models in this package represent strongly-typed application and server state. Configuration wrappers interact directly with Android `SharedPreferences` or YAML files, providing safe serialization and type parsing.

## Files

- **`NodeStatus.kt`**: State model representing runtime status (`NodeState`: `STOPPED`, `STARTING`, `RUNNING`, `STOPPING`, `ERROR`), status message, bound TCP port, and process PID.
- **`AppConfig.kt`**: Manages shell preferences (auto-start, auto-launch, port fallback, quick toolbar, idle timeout, background resume).
- **`NodeConfig.kt`**: Manages Node.js engine preferences (V8 max heap old space size, custom arguments).
- **`StConfig.kt`**: Parses and serializes SillyTavern's root `config.yaml` configuration.
- **`StUserSettings.kt`**: Bridges preferences directly with SillyTavern's `settings.json` (instant resume, compact keyboard ergonomics, font scaling, animations).
- **`StLocales.kt`**: Manages SillyTavern UI language mapping, bundled locale pack detection, and synchronization with Android system/app locales.
