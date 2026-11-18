package com.forgemind.termuxbridge

internal data class BridgeTaskSnapshotState(val exitCode: Int?, val runningPane: String?) {
    companion object {
        fun sample(readExit: () -> Int?, findRunningPane: () -> String?): BridgeTaskSnapshotState {
            val before = readExit()
            if (before != null) return BridgeTaskSnapshotState(before,null)
            val pane = findRunningPane()
            // The command can finish while the tmux query is in flight. Its atomic status write
            // precedes pane exit, so reread before reporting the process as lost.
            return BridgeTaskSnapshotState(readExit(),pane)
        }
    }
}
