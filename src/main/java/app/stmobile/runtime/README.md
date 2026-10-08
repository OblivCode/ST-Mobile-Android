# `app.stmobile.runtime`

This package handles low-level process spawning, local port binding, network readiness polling, and idle timer enforcement for the embedded Node.js environment.

## Architecture

The runtime package isolates POSIX process controls and socket operations from Android UI and service components. It executes the native launcher binary (`libstnode.so`) from the read-only application native library directory, applies command-line arguments and environment variables, probes local network ports, and verifies HTTP server responsiveness before marking the runtime ready.

## Files

- **`NodeController.kt`**: Low-level process supervisor that constructs execution arguments (Node flags, V8 heap allocations, path layouts), sets `LD_LIBRARY_PATH`, launches the child process via `ProcessBuilder`, and manages process termination (`destroyForcibly()`).
- **`PortResolver.kt`**: Manages loopback TCP port allocation, testing whether preferred ports (default 8000) are occupied and probing ephemeral ports (range 8000–8999) with fallback logic.
- **`ServerReadinessPoller.kt`**: Periodic polling engine that tests server HTTP availability (`127.0.0.1:<port>`) using non-blocking connection probes until the server accepts connections, the timeout expires, or the child process terminates prematurely.
- **`BackgroundTimeoutManager.kt`**: Scheduled countdown executor that triggers server shutdown when the application remains in the background past the user-configured idle timeout.
