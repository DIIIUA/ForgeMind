package com.forgemind.app

import android.content.ContextWrapper
import com.forgemind.app.accessibility.ForgeAccessibilityService
import com.forgemind.app.agent.AgentToolRegistry
import com.forgemind.app.agent.AppLeverTools
import com.forgemind.app.bridge.TermuxBridge
import com.forgemind.app.bridge.WorkspaceManager
import com.forgemind.app.data.db.FileSnapshotDao
import com.forgemind.app.data.db.FileSnapshotEntity
import com.forgemind.app.data.db.McpServerDao
import com.forgemind.app.data.db.McpServerEntity
import com.forgemind.app.data.model.AgentSettings
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.data.model.WorkspaceEnvType
import com.forgemind.app.llm.LlmHttpFactory
import com.forgemind.app.mcp.McpClientManager
import com.forgemind.app.web.AgentBrowserController
import com.forgemind.app.web.WebScraperEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SiteScraperAndTurboBrowserTests {

    private class EmptyMcpDao : McpServerDao {
        private val flow = MutableStateFlow<List<McpServerEntity>>(emptyList())
        override fun observeAll(): Flow<List<McpServerEntity>> = flow
        override suspend fun getAll(): List<McpServerEntity> = emptyList()
        override suspend fun getEnabled(): List<McpServerEntity> = emptyList()
        override suspend fun getById(id: String): McpServerEntity? = null
        override suspend fun upsert(server: McpServerEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
    }

    private class EmptySnapshotDao : FileSnapshotDao {
        private val flow = MutableStateFlow<List<FileSnapshotEntity>>(emptyList())
        override fun observeForSession(sessionId: String): Flow<List<FileSnapshotEntity>> = flow
        override suspend fun getById(id: String): FileSnapshotEntity? = null
        override suspend fun getRecentForSession(sessionId: String, limit: Int): List<FileSnapshotEntity> = emptyList()
        override suspend fun upsert(snapshot: FileSnapshotEntity) = Unit
        override suspend fun markRestored(id: String) = Unit
    }

    @Test
    fun `documentToRichMarkdown preserves nav menu, inline links, code blocks, lists, tables, and div text`() {
        val html = """
            <!doctype html>
            <html>
            <head>
              <title>ForgeDocs Portal</title>
              <meta name="description" content="Complete developer documentation.">
            </head>
            <body>
              <header>
                <nav>
                  <a href="/guide">Guide</a>
                  <a href="/api">API Reference</a>
                </nav>
              </header>
              <main>
                <h1>Getting Started</h1>
                <div>Direct container text with <strong>bold</strong> and <code>fm-mcp</code> inline tool.</div>
                <p>Read the <a href="/guide/install">installation manual</a> first.</p>
                <ol>
                  <li>Connect device</li>
                  <li>Run command</li>
                </ol>
                <pre><code class="language-kotlin">val ok = true</code></pre>
                <table>
                  <thead><tr><th>Mode</th><th>Latency</th></tr></thead>
                  <tbody><tr><td>Turbo</td><td>25ms</td></tr></tbody>
                </table>
              </main>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://docs.example.org/")
        val md = WebScraperEngine.documentToRichMarkdown(doc, navHeaderLabel = "Navigation")

        assertTrue(md.contains("> Complete developer documentation."))
        assertTrue(md.contains("[Guide](https://docs.example.org/guide)"))
        assertTrue(md.contains("[API Reference](https://docs.example.org/api)"))
        assertTrue(md.contains("# Getting Started"))
        assertTrue(md.contains("**bold**"))
        assertTrue(md.contains("`fm-mcp`"))
        assertTrue(md.contains("[installation manual](https://docs.example.org/guide/install)"))
        assertTrue(md.contains("1. Connect device"))
        assertTrue(md.contains("```kotlin\nval ok = true\n```"))
        assertTrue(md.contains("| Mode | Latency |"))
        assertTrue(md.contains("| Turbo | 25ms |"))
    }

    @Test
    fun `web_scrape_site crawls multi-page site with sitemap and llms_txt and saves Markdown bundle`() = runTest {
        val server = MockWebServer()
        server.start()
        val tempDir = Files.createTempDirectory("fm_site_scraper_test").toFile()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path?.substringBefore('?')) {
                        "/" -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(
                                """
                                <html><head><title>Home Hub</title></head>
                                <body>
                                  <nav><a href="/docs/quickstart?utm_source=nav#top">Quickstart</a></nav>
                                  <main>
                                    <h1>Welcome to Hub</h1>
                                    <p>Explore <a href="/docs/quickstart">Quickstart</a> or <img src="/assets/logo.png" alt="Logo"/> <a href="/assets/logo.png">PNG</a>.</p>
                                  </main>
                                </body></html>
                                """.trimIndent()
                            )
                        "/docs/quickstart" -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(
                                """
                                <html><head><title>Quickstart Guide</title></head>
                                <body><main><h1>Quickstart</h1><h2>Step One</h2><p>Fast setup instructions.</p></main></body></html>
                                """.trimIndent()
                            )
                        "/docs/hidden-from-nav" -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(
                                """
                                <html><head><title>Sitemap Secret</title></head>
                                <body><main><h1>Sitemap Page</h1><p>Discovered via XML sitemap.</p></main></body></html>
                                """.trimIndent()
                            )
                        "/sitemap.xml" -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/xml")
                            .setBody(
                                """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <urlset><url><loc>$base/docs/hidden-from-nav</loc></url></urlset>
                                """.trimIndent()
                            )
                        "/llms.txt" -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/plain; charset=utf-8")
                            .setBody("# Hub LLMs Manifest\n- Core docs ready")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val bridge = TermuxBridge(fakeCtx)
            val wm = WorkspaceManager(fakeCtx, bridge, EmptySnapshotDao())
            val mcp = McpClientManager(EmptyMcpDao(), bridge, tempDir)
            val registry = AgentToolRegistry(
                workspaceManager = wm,
                termuxBridge = bridge,
                mcpManager = mcp,
                webScraper = WebScraperEngine(fakeCtx)
            )

            val out = registry.execute(
                toolName = "web_scrape_site",
                argsJson = """{"start_url":"$base/","max_pages":10,"max_depth":2,"save_to_path":"site_dump"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_crawl",
                messageId = "m_crawl",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings(enableJsScrape = false)
            )

            assertFalse("Unexpected error: ${out.text}", out.isError)
            assertTrue(out.text.contains("Welcome to Hub"))
            assertTrue(out.text.contains("Quickstart Guide"))
            assertTrue(out.text.contains("Sitemap Secret"))
            assertTrue(out.text.contains("Hub LLMs Manifest"))

            val dumpDir = File(wm.getInternalProjectDir("default"), "site_dump")
            assertTrue(File(dumpDir, "INDEX.md").exists())
            assertTrue(File(dumpDir, "FULL_SITE.md").exists())
            assertTrue(File(dumpDir, "llms.txt").exists())
            val pageFiles = dumpDir.listFiles()?.map { it.name }.orEmpty()
            assertTrue("Expected 3 numbered page .md files, got: $pageFiles", pageFiles.count { it.matches(Regex("\\d{2}_.*\\.md")) } == 3)
        } finally {
            server.shutdown()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `turbo mode blocker, batch parser, and granular approval rules enforce speed and security`() {
        // 1) Turbo resource filter blocks trackers & heavy fonts/media while keeping normal pages/APIs
        assertTrue(AgentBrowserController.shouldBlockInTurboMode("https://www.google-analytics.com/g/collect", blockImagesAndMedia = false))
        assertTrue(AgentBrowserController.shouldBlockInTurboMode("https://cdn.example.com/fonts/inter.woff2?v=4", blockImagesAndMedia = true))
        assertFalse(AgentBrowserController.shouldBlockInTurboMode("https://cdn.example.com/fonts/inter.woff2", blockImagesAndMedia = false))
        assertFalse(AgentBrowserController.shouldBlockInTurboMode("http://127.0.0.1:8080/terminal/agent", blockImagesAndMedia = true))

        // 2) Reindex & Markdown scripts clear stale data-fm-agent-idx attributes
        val reindexJs = AgentBrowserController.buildReindexSummaryScript(40)
        assertTrue(reindexJs.contains("removeAttribute('data-fm-agent-idx')"))
        val mdJs = AgentBrowserController.buildMarkdownExtractScript(8000, "null", true)
        assertTrue(mdJs.contains("Interactive Elements"))

        // 3) Batch step parser handles multi-step fill_form + click + wait_text + screenshot
        val rawSteps = LlmHttpFactory.json.parseToJsonElement(
            """
            [
              {"action":"navigate","url":"http://127.0.0.1:8080"},
              {"action":"fill_form","fields":{"#file-path":"/docs","#key":"abc"},"submit":true},
              {"action":"wait_text","text":"FAST-OPERATOR","timeout_ms":2500},
              {"action":"screenshot","filename":"done.png","attach_to_chat":false}
            ]
            """.trimIndent()
        ).jsonArray
        val steps = AppLeverTools.parseBrowserBatchSteps(rawSteps)
        assertEquals(4, steps.size)
        assertEquals("fill_form", steps[1].action)
        assertEquals("/docs", steps[1].fields?.get("#file-path"))
        assertTrue(steps[1].submit)
        assertEquals(2500L, steps[2].timeoutMs)
        assertFalse(steps[3].attachToChat)

        // 4) Approval rules distinguish safe batch/scrape vs eval_js / disk write
        val tempDir = Files.createTempDirectory("fm_sec_rules").toFile()
        try {
            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val bridge = TermuxBridge(fakeCtx)
            val registry = AgentToolRegistry(
                workspaceManager = WorkspaceManager(fakeCtx, bridge, EmptySnapshotDao()),
                termuxBridge = bridge,
                mcpManager = McpClientManager(EmptyMcpDao(), bridge, tempDir),
                webScraper = WebScraperEngine(fakeCtx)
            )
            val safeBrowserOnly = AgentSettings(
                autoApproveAll = false,
                autoApproveSafe = true,
                autoApproveRead = true,
                autoApproveBrowser = true,
                autoApproveBrowserJs = false,
                autoApproveFileWrite = false
            )
            assertFalse(
                registry.requiresApproval(
                    "browser_agent_batch",
                    """{"steps":[{"action":"click","index":2}]}""",
                    safeBrowserOnly
                )
            )
            assertTrue(
                registry.requiresApproval(
                    "browser_agent_batch",
                    """{"steps":[{"action":"eval_js","script":"alert(1)"}]}""",
                    safeBrowserOnly
                )
            )
            assertFalse(
                registry.requiresApproval(
                    "web_scrape_site",
                    """{"start_url":"https://example.com"}""",
                    safeBrowserOnly
                )
            )
            assertTrue(
                registry.requiresApproval(
                    "web_scrape_site",
                    """{"start_url":"https://example.com","save_to_path":"dump"}""",
                    safeBrowserOnly
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `normalizeSafRelativePath deduplicates matching SAF root prefixes and rejects out-of-tree paths`() {
        val safMoviesUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"

        assertEquals("dump/py", WorkspaceManager.normalizeSafRelativePath("dump/py", safMoviesUri))
        assertEquals("dump/py", WorkspaceManager.normalizeSafRelativePath("./dump/py/", safMoviesUri))
        assertEquals("dump/py", WorkspaceManager.normalizeSafRelativePath("/storage/emulated/0/Movies/dump/py", safMoviesUri))
        assertEquals("dump/py", WorkspaceManager.normalizeSafRelativePath("/sdcard/Movies/dump/py", safMoviesUri))
        assertEquals("dump/py", WorkspaceManager.normalizeSafRelativePath("/storage/self/primary/Movies/dump/py", safMoviesUri))
        assertEquals("", WorkspaceManager.normalizeSafRelativePath("/storage/emulated/0/Movies", safMoviesUri))

        val outOfTreeErr = runCatching {
            WorkspaceManager.normalizeSafRelativePath("/storage/emulated/0/Download/other", safMoviesUri)
        }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException for out-of-tree SAF path", outOfTreeErr is IllegalArgumentException)

        val dataRootErr = runCatching {
            WorkspaceManager.normalizeSafRelativePath("/data/local/tmp/out", safMoviesUri)
        }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException for /data path", dataRootErr is IllegalArgumentException)

        val traversalErr = runCatching {
            WorkspaceManager.normalizeSafRelativePath("docs/../../secret", safMoviesUri)
        }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException for '..' traversal", traversalErr is IllegalArgumentException)
    }

    @Test
    fun `shared turbo toggle defaults to false and multi-tab background pool manages parallel tab sessions`() = runTest {
        val tempDir = Files.createTempDirectory("fm_multitab_turbo").toFile()
        try {
            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val browserCtrl = AgentBrowserController(fakeCtx)
            assertFalse("Shared Turbo mode must default to OFF", browserCtrl.turboModeEnabled.value)
            assertEquals(1, browserCtrl.agentTabs.value.size)
            assertEquals("tab_1", browserCtrl.activeAgentTabId.value)

            val bridge = TermuxBridge(fakeCtx)
            val registry = AgentToolRegistry(
                workspaceManager = WorkspaceManager(fakeCtx, bridge, EmptySnapshotDao()),
                termuxBridge = bridge,
                mcpManager = McpClientManager(EmptyMcpDao(), bridge, tempDir),
                webScraper = WebScraperEngine(fakeCtx),
                browserController = browserCtrl
            )

            // 1) AI turns Turbo ON -> shared state updates immediately
            val turboOnOut = registry.execute(
                toolName = "browser_agent_mode",
                argsJson = """{"action":"turbo_on"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m1",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(turboOnOut.isError)
            assertTrue(browserCtrl.turboModeEnabled.value)

            // 2) Open custom background windows ('docs' and 'pricing') without losing 'tab_1'
            val newDocsOut = registry.execute(
                toolName = "browser_agent_tabs",
                argsJson = """{"action":"new","tab_id":"docs"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m2",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(newDocsOut.isError)
            val newPricingOut = registry.execute(
                toolName = "browser_agent_tabs",
                argsJson = """{"action":"new","tab_id":"pricing"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m3",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(newPricingOut.isError)
            assertEquals(3, browserCtrl.agentTabs.value.size)
            assertEquals("pricing", browserCtrl.activeAgentTabId.value)

            // 3) Switch back to 'docs' and close 'pricing'
            val switchOut = registry.execute(
                toolName = "browser_agent_tabs",
                argsJson = """{"action":"switch","tab_id":"docs"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m4",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(switchOut.isError)
            assertEquals("docs", browserCtrl.activeAgentTabId.value)

            val closeOut = registry.execute(
                toolName = "browser_agent_tabs",
                argsJson = """{"action":"close","tab_id":"pricing"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m5",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(closeOut.isError)
            assertEquals(2, browserCtrl.agentTabs.value.size)

            // 4) Batch step parser preserves per-step tab_id and new_tab
            val multiTabStepsJson = LlmHttpFactory.json.parseToJsonElement(
                """
                [
                  {"action":"navigate","url":"https://docs.python.org","tab_id":"docs"},
                  {"action":"navigate","url":"https://kotlinlang.org","tab_id":"kotlin","new_tab":true},
                  {"action":"switch_tab","tab_id":"docs"}
                ]
                """.trimIndent()
            ).jsonArray
            val parsed = AppLeverTools.parseBrowserBatchSteps(multiTabStepsJson)
            assertEquals(3, parsed.size)
            assertEquals("docs", parsed[0].tabId)
            assertFalse(parsed[0].newTab)
            assertEquals("kotlin", parsed[1].tabId)
            assertTrue(parsed[1].newTab)
            assertEquals("switch_tab", parsed[2].action)
            assertEquals("docs", parsed[2].tabId)

            // 5) AI turns Turbo OFF -> shared state returns to false
            registry.execute(
                toolName = "browser_agent_mode",
                argsJson = """{"action":"turbo_off"}""",
                defaultEnv = WorkspaceEnvType.INTERNAL,
                internalProject = "default",
                sessionId = "s_mt",
                messageId = "m6",
                agentSettings = AgentSettings(autoApproveAll = true),
                searchSettings = SearchSettings()
            )
            assertFalse(browserCtrl.turboModeEnabled.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `small per-tab budgets cap interactive elements too and silent internal ops do not pollute actionLog`() = runTest {
        // 1) Inspect script honors sub-500 budgets (e.g. 250) and caps elements[] alongside text
        val inspect250 = AgentBrowserController.buildInspectScript(250)
        assertTrue("Expected budget = 250 in inspect script", inspect250.contains("var budget = 250;"))
        assertTrue("Expected stale index cleanup", inspect250.contains("removeAttribute('data-fm-agent-idx')"))
        assertTrue("Expected total output check against budget", inspect250.contains("if (fullJson.length <= budget) return fullJson;"))
        assertTrue("Expected element list truncation loop", inspect250.contains("res.elements = kept;"))
        assertTrue("Expected shown K/total truncation marker", inspect250.contains("'shown ' + kept.length + '/' + totalCount"))

        // 2) Markdown extract script honors sub-500 budgets (e.g. 200) and caps Interactive Elements block
        val md200 = AgentBrowserController.buildMarkdownExtractScript(
            maxChars = 200,
            selectorJsonLiteral = "null",
            includeInteractive = true
        )
        assertTrue("Expected budget = 200 in markdown script", md200.contains("var budget = 200;"))
        assertTrue("Expected combined output check against budget", md200.contains("if (fullCombined.length <= budget) return fullCombined;"))
        assertTrue("Expected element-by-element budget cap", md200.contains("if (usedChars + lineCost > rem) break;"))
        assertTrue("Expected keptEl/totalEl header", md200.contains("keptEl.length + '/' + totalEl"))

        // Floor clamp at 120 prevents malformed zero-length budgets
        val mdFloor = AgentBrowserController.buildMarkdownExtractScript(50, "null", true)
        assertTrue(mdFloor.contains("var budget = 120;"))

        // 3) Silent internal operations (recordAction = false) do not add phantom entries to actionLog,
        //    and listTabsSummary documents cross-restart persistence.
        val tempDir = Files.createTempDirectory("fm_budget_logs").toFile()
        try {
            val fakeCtx = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempDir
            }
            val browserCtrl = AgentBrowserController(fakeCtx)
            assertTrue(browserCtrl.actionLog.value.isEmpty())
            assertTrue(browserCtrl.listTabsSummary().contains("persisted across restarts"))

            browserCtrl.createOrSelectTab(
                requestedTabId = "silent_bg",
                initialUrl = "",
                makeActive = false,
                recordAction = false
            )
            assertTrue(
                "Internal tab allocation with recordAction=false must not pollute logs",
                browserCtrl.actionLog.value.isEmpty()
            )

            browserCtrl.createOrSelectTab(
                requestedTabId = "explicit_tab",
                initialUrl = "",
                makeActive = true,
                recordAction = true
            )
            assertEquals(1, browserCtrl.actionLog.value.size)
            assertTrue(browserCtrl.actionLog.value.single().contains("Opened tab [explicit_tab]"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `computeRebindServiceStrings preserves neighbor accessibility services and respects user disablement`() {
        val pkg = "com.forgemind.app"
        val cls = "com.forgemind.app.accessibility.ForgeAccessibilityService"

        // 1) Coexisting with Tasker (full component name)
        val rawWithTasker =
            "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService:$pkg/$cls"
        val pair1 = ForgeAccessibilityService.computeRebindServiceStrings(rawWithTasker, pkg, cls)
        requireNotNull(pair1)
        assertEquals(
            "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService",
            pair1.first
        )
        assertEquals(
            "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService:$pkg/$cls",
            pair1.second
        )

        // 2) Short component form (com.forgemind.app/.accessibility.ForgeAccessibilityService) as sole service
        val rawShortSole = "$pkg/.accessibility.ForgeAccessibilityService"
        val pair2 = ForgeAccessibilityService.computeRebindServiceStrings(rawShortSole, pkg, cls)
        requireNotNull(pair2)
        assertEquals("", pair2.first)
        assertEquals("$pkg/$cls", pair2.second)

        // 3) User explicitly disabled our service -> must return null (never force-enable without consent)
        val rawOnlyTasker = "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService"
        assertEquals(null, ForgeAccessibilityService.computeRebindServiceStrings(rawOnlyTasker, pkg, cls))
        assertEquals(null, ForgeAccessibilityService.computeRebindServiceStrings("", pkg, cls))
    }
}
