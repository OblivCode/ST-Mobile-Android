package app.stmobile.models

/** Runtime lifecycle state, shared between the service and the UI. */
enum class NodeState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class NodeStatus(
    val state: NodeState,
    val message: String,
    val port: Int,
    val pid: Long? = null,
) {
    val isActive: Boolean get() = state == NodeState.STARTING || state == NodeState.RUNNING
}
