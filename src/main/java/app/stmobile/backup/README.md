# `app.stmobile.backup`

This package provides complete export and import capabilities for SillyTavern user state (`data/` directory and `config/config.yaml`), independent of the disposable runtime root.

## Architecture

The backup system generates and consumes standard ZIP archives compatible with PC SillyTavern installations. It supports AES-256 password encryption via Zip4j, Zip Slip path traversal mitigation, and dual restore strategies (`CLEAN` with rollback protection vs. `MERGE` additive copying).

### Non-Dereferencing File Operations & Symlink Safety

Recursive file operations throughout backup and restore employ non-dereferencing file tree visitors (`java.nio.file.Files.walkFileTree` with `SimpleFileVisitor` omitting `FileVisitOption.FOLLOW_LINKS`) to ensure symbolic links pointing outside the application data directory (such as external SD card mounts or shared media directories) are never traversed or dereferenced:

- **`deleteRecursivelyNoFollow(file: File)`**: Traverses directory structures without following symlinks. When a symlink is visited, only the symlink itself is unlinked via `Files.deleteIfExists(path)`. Targets located outside the traversal root are guaranteed never to be deleted.
- **`copyRecursivelyNoFollow(source: File, target: File)`**: Copies directory structures while preserving symbolic links. When a symlink is encountered (`Files.isSymbolicLink(file)`), it is recreated at the destination using `Files.createSymbolicLink(dest, Files.readSymbolicLink(file))` without copying, expanding, or traversing into the linked directory or file.
- **Clean Restore Rollback Safety**: During atomic clean restores, staged swaps and rollback stages use `deleteRecursivelyNoFollow` and `copyRecursivelyNoFollow`. If an extraction or promotion step fails midway, existing symlinks inside `dataDir` are cleanly restored without deleting or duplicating external data.

## Files

- **`BackupManager.kt`**: Single source of truth for user state persistence. Implements pre-flight archive inspection without full decompression, SAF stream staging, header normalization, non-dereferencing recursive file tree operations (`deleteRecursivelyNoFollow`, `copyRecursivelyNoFollow`), and atomic clean restore with rollback fallback.
