package com.forgemind.termuxbridge

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgeWorkingDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun emptyAndHomePathsUseTheSuppliedTermuxHome() {
        for (path in listOf(null,""," ","~","."))
            assertEquals(temporary.root.canonicalFile,BridgeWorkingDirectory.resolve(path,temporary.root))
    }
    @Test fun relativeAndTildePathsAreResolvedFromHome() {
        val expected=File(temporary.root,"project").canonicalFile
        assertEquals(expected,BridgeWorkingDirectory.resolve("~/project",temporary.root))
        assertEquals(expected,BridgeWorkingDirectory.resolve("project",temporary.root))
    }
    @Test fun explicitDirectoryDoesNotInheritHome() {
        val child=temporary.newFolder("cwd")
        assertEquals(child.canonicalFile,BridgeWorkingDirectory.resolve(child.absolutePath,File(temporary.root,"other")))
    }
}
