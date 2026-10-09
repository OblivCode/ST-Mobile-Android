# `app.stmobile.node`

This package encapsulates the execution, supervision, and local IPC communication for the embedded SillyTavern Node.js engine.

## Components

- **`NodeController.kt`**: Unified singleton repository managing the Node child process (`libstnode.so`), command-line arguments, V8 heap limits, stdout/stderr log rotation, readiness polling, exit supervision, and `status: StateFlow<NodeStatus>`.
- **`PortResolver.kt`**: Probes loopback port availability on `127.0.0.1` and allocates ephemeral ports if the default port (8000) is occupied.
- **`ServerReadinessPoller.kt`**: Probes local HTTP connection availability until the server accepts connections, the timeout elapses, or the child process terminates.
- **`GenerationIpcServer.kt`**: Lightweight loopback TCP server listening for `GEN_START` / `GEN_END` signals from the Node.js preload hook (`generation_hook.cjs`).
