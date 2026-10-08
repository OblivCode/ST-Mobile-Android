# `app.stmobile.payload`

This package manages the lifecycle of the SillyTavern web application payload: bundle extraction, format detection, SHA-256 integrity verification, OTA update checks, and atomic directory promotion.

## Architecture

The payload layer decouples the application code of SillyTavern from persistent user data (`data/`) and configuration (`config/config.yaml`). SillyTavern is treated as a disposable, replaceable runtime root (`st/`). Pluggable payload sources stream uncompressed TAR bundles or GZIP archives from local assets, remote GitHub releases, or sideloaded ZIP archives via the Storage Access Framework (SAF).

### Atomic Promotion & Rollback Copy Fallback

Payload promotions swap the staged tree into `paths.stDir` while preserving previous state in `st_old`:
- **Atomic Promotion**: The current runtime tree is moved to `st_old`, and the staging directory is promoted via rename or copy to `st/`.
- **Rollback Copy Fallback**: In the event promotion fails, the `finally` block rolls back by removing incomplete state from `st/` and restoring `st_old`. If renaming `st_old` back to `st/` fails (for instance across filesystems or locked mount points), it falls back to non-dereferencing recursive copying (`copyRecursivelyNoFollow(old, stDir)`) followed by non-dereferencing deletion (`safeDeleteRecursively(old)`). This ensures symlinks pointing to persistent user data (`paths.stDataLink` -> `data/`) and configuration (`paths.stConfigLink` -> `config.yaml`) are recreated as symbolic links rather than being dereferenced into physical file trees or lost during rollback cleanup.

## Files

- **`PayloadManager.kt`**: Coordinates payload extraction into staging directories (`st_new/`), SHA-256 checksum validation, tree normalization, atomic swap promotion (`st_new` -> `st/`) with non-dereferencing rollback fallback (`copyRecursivelyNoFollow`, `safeDeleteRecursively`).
- **`PayloadSource.kt`**: Pluggable input stream abstractions for different bundle distribution methods (`AssetPayloadSource` for full APK builds, `RemotePayloadSource` for slim OTA downloads, `LocalArchivePayloadSource` for manual ZIP sideloads).
- **`UpdateChecker.kt`**: Communicates with the GitHub Releases API to detect upstream SillyTavern updates, compare semver and commit hashes, and calculate download sizes.
