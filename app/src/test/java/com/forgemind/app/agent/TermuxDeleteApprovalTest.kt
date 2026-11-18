package com.forgemind.app.agent
import com.forgemind.app.data.model.WorkspaceEnvType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TermuxDeleteApprovalTest {
    private fun required(name:String, json:String, env:WorkspaceEnvType = WorkspaceEnvType.INTERNAL) =
        TermuxDeleteApproval.required(name, Json.parseToJsonElement(json).jsonObject, env)
    @Test fun explicitTermuxDeleteRequiresHuman() { assertTrue(required("file_delete","""{"target_env":"termux_ssh"}""")) }
    @Test fun defaultTermuxEnvironmentCannotBypassApproval() { assertTrue(required("file_delete","{}",WorkspaceEnvType.TERMUX_SSH)) }
    @Test fun modelCannotSupplyItsOwnConfirmation() { assertTrue(required("file_delete","""{"target_env":"termux_ssh","userConfirmed":true}""")) }
    @Test fun internalDeleteKeepsExistingPolicy() { assertFalse(required("file_delete","{}",WorkspaceEnvType.INTERNAL)) }
    @Test fun crossEnvironmentMoveRequiresHuman() { assertTrue(required("transfer_file","""{"source_env":"termux_ssh","move":true}""")) }
    @Test fun copyDoesNotDeleteSource() { assertFalse(required("transfer_file","""{"source_env":"termux_ssh","move":false}""")) }
    @Test fun deletingTermuxSnapshotNeedsActualApproval() { assertTrue(TermuxDeleteApproval.snapshot(WorkspaceEnvType.TERMUX_SSH,false,false)) }
    @Test fun restoringContentDoesNotAddDeletionApproval() { assertFalse(TermuxDeleteApproval.snapshot(WorkspaceEnvType.TERMUX_SSH,true,true)) }
    @Test fun internalSnapshotKeepsExistingPolicy() { assertFalse(TermuxDeleteApproval.snapshot(WorkspaceEnvType.INTERNAL,false,false)) }
}
