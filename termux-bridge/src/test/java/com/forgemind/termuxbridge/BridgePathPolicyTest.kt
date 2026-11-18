package com.forgemind.termuxbridge

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgePathPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun policy(): Triple<BridgePathPolicy, File, File> {
        val home = File(temporary.root, "home").apply { mkdirs() }
        val prefix = File(temporary.root, "usr").apply { mkdirs() }
        return Triple(BridgePathPolicy(home, prefix, listOf(File(temporary.root, "bridge-private"))), home, prefix)
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Protected path was accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun homeChildrenAreWritable() { val (p,h)=policy(); assertEquals(File(h,"notes/a.txt").canonicalFile,p.mutable("~/notes/a.txt")) }
    @Test fun environmentRootsAndParentsAreProtected() {
        val (p,h,u)=policy(); listOf(h,u,temporary.root).forEach { rejected { p.mutable(it.path) } }
    }
    @Test fun allProtectedPrefixTreesAreReadOnly() {
        val (p,_,u)=policy()
        for (name in listOf("bin","lib","libexec","etc")) {
            rejected { p.mutable(File(u,name).path) }; rejected { p.mutable(File(u,"$name/child").path) }
        }
    }
    @Test fun traversalCannotEscapeIntoProtectedTree() {
        val (p,h)=policy(); rejected { p.mutable(File(h,"../usr/bin/bash").path) }
    }
    @Test fun similarlyNamedSiblingIsNotProtected() {
        val (p,_,u)=policy(); assertEquals(File(u,"binary/a").canonicalFile,p.mutable(File(u,"binary/a").path))
    }
    @Test fun privateDataCannotBeRead() {
        val (p)=policy(); rejected { p.resolve(File(temporary.root,"bridge-private/state").path) }
    }
    @Test fun prefixConfigurationRemainsReadable() {
        val (p,_,u)=policy(); assertEquals(File(u,"etc/profile").canonicalFile,p.resolve(File(u,"etc/profile").path))
    }
    @Test fun malformedPathIsRejected() { val (p)=policy(); rejected { p.resolve("abc\u0000def") } }
    @Test fun androidBindMountAliasesHaveTheSameIdentity() {
        assertEquals("/data/data/com.example/files/a", BridgePathPolicy.storageIdentity("/data/user/0/com.example/files/a"))
        assertEquals(BridgePathPolicy.storageIdentity("/data/data"),BridgePathPolicy.storageIdentity("/data/user/0"))
    }
    @Test fun otherUsersAndSimilarPathPrefixesAreNotRewritten() {
        for (path in listOf("/data/user/01/pkg","/data/user/10/pkg","/data/user_de/0/pkg","/data/data/pkg"))
            assertEquals(path,BridgePathPolicy.storageIdentity(path))
    }
}
