package com.forgemind.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.forgemind.app.data.model.SearchEngineType
import com.forgemind.app.data.model.SearchSettings
import com.forgemind.app.llm.LlmHttpFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)

data class ScrapedPageMarkdown(
    val index: Int,
    val url: String,
    val title: String,
    val depth: Int,
    val headings: List<String>,
    val markdown: String,
    val discoveredLinks: List<String>,
    val slugFileName: String
)

data class SiteScrapeResult(
    val startUrl: String,
    val host: String,
    val elapsedMs: Long,
    val pages: List<ScrapedPageMarkdown>,
    val llmsTxtContent: String?,
    val indexMarkdown: String,
    val combinedMarkdown: String
)

class WebScraperEngine(
    private val context: Context
) {
    private val client = LlmHttpFactory.baseClientBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val fastProbeClient = client.newBuilder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun search(
        query: String,
        count: Int = 8,
        settings: SearchSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim()
            if (q.isEmpty()) error("Search query is empty")

            val start = System.currentTimeMillis()
            val results = when (settings.primaryEngine) {
                SearchEngineType.TAVILY -> {
                    if (settings.tavilyApiKey.isNotBlank()) {
                        searchTavily(q, count, settings.tavilyApiKey)
                    } else searchDuckDuckGo(q, count)
                }
                SearchEngineType.BRAVE -> {
                    if (settings.braveApiKey.isNotBlank()) {
                        searchBrave(q, count, settings.braveApiKey)
                    } else searchDuckDuckGo(q, count)
                }
                SearchEngineType.GOOGLE_CSE -> {
                    if (settings.googleCseKey.isNotBlank() && settings.googleCseCx.isNotBlank()) {
                        searchGoogleCse(q, count, settings.googleCseKey, settings.googleCseCx)
                    } else searchDuckDuckGo(q, count)
                }
                SearchEngineType.SEARXNG -> {
                    if (settings.searxngUrl.isNotBlank()) {
                        searchSearxng(q, count, settings.searxngUrl)
                    } else searchDuckDuckGo(q, count)
                }
                SearchEngineType.DUCKDUCKGO -> searchDuckDuckGo(q, count)
            }.ifEmpty {
                if (settings.primaryEngine != SearchEngineType.DUCKDUCKGO) {
                    searchDuckDuckGo(q, count)
                } else emptyList()
            }

            val elapsed = System.currentTimeMillis() - start
            if (results.isEmpty()) {
                return@runCatching "No search results found for \"$q\" (${elapsed}ms)."
            }
            buildString {
                appendLine("Web search results for \"$q\" (${elapsed}ms):")
                appendLine("<external_content untrusted=\"true\" source=\"web_search\">")
                results.forEachIndexed { i, r ->
                    appendLine("${i + 1}. ${r.title}")
                    appendLine("   URL: ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
                    appendLine()
                }
                appendLine("Use web_scrape or web_scrape_site to extract a single page or an entire website as Markdown.")
                append("</external_content>")
            }
        }
    }

    suspend fun scrape(
        url: String,
        mode: String = "markdown",
        cssSelector: String? = null,
        renderJs: Boolean = false,
        headersJson: String = "{}",
        maxChars: Int = 24_000,
        settings: SearchSettings = SearchSettings()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = ensureHttpUrl(url)
            val fetched = if (renderJs && settings.enableJsScrape) {
                FetchedResource(cleanUrl, "text/html", renderPageHtmlWithWebView(cleanUrl))
            } else {
                fetchRawResource(cleanUrl, headersJson)
            }

            var htmlOrText = fetched.body
            if (!renderJs && settings.enableJsScrape && fetched.isHtml && looksLikeEmptySpa(htmlOrText)) {
                runCatching { renderPageHtmlWithWebView(cleanUrl) }.getOrNull()?.let { jsHtml ->
                    if (jsHtml.length > htmlOrText.length / 2) htmlOrText = jsHtml
                }
            }

            if (!fetched.isHtml && mode.lowercase() == "markdown") {
                return@runCatching buildString {
                    appendLine("URL: ${fetched.finalUrl}")
                    appendLine("<external_content untrusted=\"true\" source=\"web_scrape\">")
                    appendLine(htmlOrText.take(maxChars).ifBlank { "(Empty text output)" })
                    append("</external_content>")
                }
            }

            val doc = Jsoup.parse(htmlOrText, fetched.finalUrl)
            val title = doc.title().trim()
            val bodyOut = when (mode.lowercase()) {
                "html" -> {
                    val target = if (!cssSelector.isNullOrBlank()) doc.select(cssSelector).outerHtml() else doc.body().html()
                    target.take(maxChars)
                }
                "selector" -> {
                    if (cssSelector.isNullOrBlank()) error("Specify css_selector for mode='selector'")
                    val matched = doc.select(cssSelector)
                    if (matched.isEmpty()) "No elements matched selector '$cssSelector'."
                    else matched.mapIndexed { idx, el ->
                        val href = el.attr("abs:href").takeIf { it.isNotBlank() }?.let { " -> $it" }.orEmpty()
                        "[${idx + 1}] ${el.text().trim()}$href"
                    }.joinToString("\n").take(maxChars)
                }
                "links" -> {
                    doc.select("a[href]").mapNotNull { a ->
                        val abs = a.attr("abs:href").trim()
                        val txt = a.text().trim()
                        if (abs.startsWith("http")) "- [${txt.ifBlank { abs }}]($abs)" else null
                    }.distinct().joinToString("\n").take(maxChars)
                }
                "tables" -> extractTablesAsMarkdown(doc).take(maxChars)
                "text" -> {
                    stripNoise(doc, removeNav = true)
                    val target = if (!cssSelector.isNullOrBlank()) doc.select(cssSelector).text() else doc.body().text()
                    target.replace(Regex("\\s{3,}"), "\n\n").take(maxChars)
                }
                else -> {
                    documentToRichMarkdown(
                        doc = doc,
                        cssSelector = cssSelector,
                        includeNav = cssSelector.isNullOrBlank(),
                        navHeaderLabel = "Page Navigation"
                    ).take(maxChars)
                }
            }

            buildString {
                appendLine("URL: ${fetched.finalUrl}")
                if (title.isNotBlank()) appendLine("Title: $title")
                appendLine("<external_content untrusted=\"true\" source=\"web_scrape\">")
                appendLine(bodyOut.ifBlank { "(Empty text output)" })
                append("</external_content>")
            }
        }
    }

    suspend fun scrapeSite(
        startUrl: String,
        maxPages: Int = 12,
        maxDepth: Int = 2,
        concurrency: Int = 6,
        samePathPrefix: Boolean = false,
        urlFilter: String? = null,
        includeSitemap: Boolean = true,
        renderJsFirstPage: Boolean = false,
        headersJson: String = "{}",
        maxCharsPerPage: Int = 16_000,
        settings: SearchSettings = SearchSettings()
    ): Result<SiteScrapeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val cleanStart = normalizeCrawlUrl(ensureHttpUrl(startUrl), null)
                ?: error("Invalid start URL: $startUrl")
            val parsedStart = cleanStart.toHttpUrlOrNull() ?: error("Cannot parse URL: $cleanStart")
            val targetHost = parsedStart.host.lowercase()
            val basePrefix = computePathPrefix(parsedStart.encodedPath)
            val safeMaxPages = maxPages.coerceIn(1, 50)
            val safeMaxDepth = maxDepth.coerceIn(0, 5)
            val safeConcurrency = concurrency.coerceIn(1, 8)
            val safePerPageChars = maxCharsPerPage.coerceIn(1_500, 50_000)
            val filterRegex = urlFilter?.trim()?.takeIf { it.isNotEmpty() }?.let { pat ->
                runCatching { Regex(pat, RegexOption.IGNORE_CASE) }.getOrNull()
            }

            fun isAllowedTarget(candidate: String): Boolean {
                val u = candidate.toHttpUrlOrNull() ?: return false
                if (!u.host.equals(targetHost, ignoreCase = true)) return false
                if (samePathPrefix && !u.encodedPath.startsWith(basePrefix)) return false
                if (!urlFilter.isNullOrBlank()) {
                    val matches = filterRegex?.containsMatchIn(candidate)
                        ?: candidate.contains(urlFilter.trim(), ignoreCase = true)
                    if (!matches && candidate != cleanStart) return false
                }
                return true
            }

            val origin = "${parsedStart.scheme}://${parsedStart.host}" +
                if ((parsedStart.scheme == "http" && parsedStart.port != 80) ||
                    (parsedStart.scheme == "https" && parsedStart.port != 443)
                ) ":${parsedStart.port}" else ""

            var llmsTxt: String? = null
            val sitemapSeeds = mutableListOf<String>()
            if (includeSitemap && safeMaxPages > 1) {
                coroutineScope {
                    val sitemapDeferred = async { fetchSitemapUrls("$origin/sitemap.xml", headersJson, limit = safeMaxPages * 3) }
                    val llmsDeferred = async { fetchOptionalText("$origin/llms.txt", headersJson, maxChars = 12_000) }
                    sitemapSeeds += sitemapDeferred.await().filter { isAllowedTarget(it) }
                    llmsTxt = llmsDeferred.await()
                }
            }

            val visited = Collections.synchronizedSet(linkedSetOf<String>())
            visited += cleanStart

            data class RawCrawledPage(
                val url: String,
                val finalUrl: String,
                val depth: Int,
                val title: String,
                val headings: List<String>,
                val markdown: String,
                val outLinks: List<String>
            )

            val collected = mutableListOf<RawCrawledPage>()
            val sem = Semaphore(safeConcurrency)
            var frontier = listOf(cleanStart)

            for (depth in 0..safeMaxDepth) {
                if (frontier.isEmpty() || collected.size >= safeMaxPages) break
                val remainingBudget = safeMaxPages - collected.size
                val batch = frontier.take(remainingBudget)

                val batchResults = coroutineScope {
                    batch.map { targetUrl ->
                        async {
                            sem.withPermit {
                                runCatching {
                                    val useJs = depth == 0 && renderJsFirstPage && settings.enableJsScrape
                                    val resource = if (useJs) {
                                        FetchedResource(targetUrl, "text/html", renderPageHtmlWithWebView(targetUrl))
                                    } else {
                                        fetchRawResource(targetUrl, headersJson)
                                    }
                                    var body = resource.body
                                    if (depth == 0 && !useJs && settings.enableJsScrape && resource.isHtml && looksLikeEmptySpa(body)) {
                                        runCatching { renderPageHtmlWithWebView(targetUrl) }.getOrNull()?.let { jsHtml ->
                                            if (jsHtml.length > body.length / 2) body = jsHtml
                                        }
                                    }
                                    if (!resource.isHtml) {
                                        val title = targetUrl.substringAfterLast('/').ifBlank { targetHost }
                                        RawCrawledPage(
                                            url = targetUrl,
                                            finalUrl = resource.finalUrl,
                                            depth = depth,
                                            title = title,
                                            headings = emptyList(),
                                            markdown = body.take(safePerPageChars).trim(),
                                            outLinks = emptyList()
                                        )
                                    } else {
                                        val doc = Jsoup.parse(body, resource.finalUrl)
                                        val pageTitle = doc.title().trim().ifBlank {
                                            doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { targetUrl }
                                        }
                                        val headings = doc.select("h1, h2, h3")
                                            .mapNotNull { h ->
                                                val txt = h.text().trim()
                                                if (txt.isBlank()) null else "${h.tagName().uppercase()}: $txt"
                                            }
                                            .distinct()
                                            .take(18)

                                        val outLinks = doc.select("a[href]")
                                            .mapNotNull { a -> normalizeCrawlUrl(a.attr("href"), resource.finalUrl) }
                                            .filter { isAllowedTarget(it) }
                                            .distinct()

                                        val md = documentToRichMarkdown(
                                            doc = doc,
                                            cssSelector = null,
                                            includeNav = depth == 0,
                                            navHeaderLabel = "Site Navigation"
                                        ).take(safePerPageChars)

                                        RawCrawledPage(
                                            url = targetUrl,
                                            finalUrl = resource.finalUrl,
                                            depth = depth,
                                            title = pageTitle,
                                            headings = headings,
                                            markdown = md,
                                            outLinks = outLinks
                                        )
                                    }
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                collected += batchResults
                if (collected.size >= safeMaxPages || depth == safeMaxDepth) break

                val nextCandidates = LinkedHashSet<String>()
                for (p in batchResults) {
                    for (lnk in p.outLinks) {
                        if (visited.add(lnk)) nextCandidates += lnk
                    }
                }
                if (depth == 0 && sitemapSeeds.isNotEmpty()) {
                    for (seed in sitemapSeeds) {
                        if (visited.add(seed)) nextCandidates += seed
                    }
                }
                frontier = nextCandidates.toList()
            }

            if (collected.isEmpty()) {
                error("Failed to scrape any pages from $cleanStart")
            }

            val usedSlugs = HashSet<String>()
            val pages = collected.mapIndexed { i, raw ->
                val num = (i + 1).toString().padStart(2, '0')
                val baseSlug = buildPageSlug(raw.finalUrl, i == 0)
                var fileName = "${num}_${baseSlug}.md"
                var dup = 2
                while (!usedSlugs.add(fileName)) {
                    fileName = "${num}_${baseSlug}_$dup.md"
                    dup++
                }
                ScrapedPageMarkdown(
                    index = i + 1,
                    url = raw.finalUrl,
                    title = raw.title,
                    depth = raw.depth,
                    headings = raw.headings,
                    markdown = raw.markdown.ifBlank { "(Empty content)" },
                    discoveredLinks = raw.outLinks,
                    slugFileName = fileName
                )
            }

            val elapsed = System.currentTimeMillis() - t0
            val indexMd = buildString {
                appendLine("# Site Blueprint (Markdown): $targetHost")
                appendLine()
                appendLine("- **Start URL:** $cleanStart")
                appendLine("- **Pages Scraped:** ${pages.size} (${elapsed} ms)")
                if (!llmsTxt.isNullOrBlank()) {
                    appendLine("- **`/llms.txt` Found:** yes (${llmsTxt!!.length} chars)")
                }
                appendLine()
                appendLine("## Table of Contents & Page Hierarchy")
                pages.forEach { p ->
                    appendLine("${p.index}. **[${p.title}](${p.url})** — file `${p.slugFileName}` *(depth ${p.depth})*")
                    p.headings.take(6).forEach { h ->
                        appendLine("   - $h")
                    }
                }
                if (!llmsTxt.isNullOrBlank()) {
                    appendLine()
                    appendLine("## Site `/llms.txt` Summary")
                    appendLine(llmsTxt!!.trim())
                }
            }.trim()

            val combinedMd = buildString {
                appendLine(indexMd)
                pages.forEach { p ->
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine("## [${p.index}/${pages.size}] ${p.title}")
                    appendLine("Source: ${p.url} | File: `${p.slugFileName}`")
                    appendLine()
                    appendLine(p.markdown)
                }
            }.trim()

            SiteScrapeResult(
                startUrl = cleanStart,
                host = targetHost,
                elapsedMs = elapsed,
                pages = pages,
                llmsTxtContent = llmsTxt,
                indexMarkdown = indexMd,
                combinedMarkdown = combinedMd
            )
        }
    }

    private data class FetchedResource(
        val finalUrl: String,
        val contentType: String,
        val body: String
    ) {
        val isHtml: Boolean
            get() {
                val ct = contentType.lowercase()
                return ct.isBlank() || ct.contains("html") || ct.contains("xml")
            }
    }

    private fun ensureHttpUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) error("URL is empty")
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
    }

    private fun fetchRawResource(url: String, headersJson: String): FetchedResource {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/markdown,text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { LlmHttpFactory.applyCustomHeaders(this, headersJson) }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} loading $url")
            val ct = resp.header("Content-Type").orEmpty()
            val ctLower = ct.lowercase()
            if (ctLower.startsWith("image/") || ctLower.startsWith("audio/") ||
                ctLower.startsWith("video/") || ctLower.contains("application/octet-stream") ||
                ctLower.contains("application/pdf") || ctLower.contains("application/zip")
            ) {
                error("Skipped binary resource ($ct): $url")
            }
            val finalUrl = resp.request.url.toString()
            return FetchedResource(finalUrl, ct, resp.body?.string().orEmpty())
        }
    }

    private fun fetchOptionalText(url: String, headersJson: String, maxChars: Int): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; ForgeMindBot/1.0)")
            .apply { LlmHttpFactory.applyCustomHeaders(this, headersJson) }
            .build()
        fastProbeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            if (!ct.contains("text/plain") && !ct.contains("markdown")) return null
            val txt = resp.body?.string().orEmpty().trim()
            if (txt.isBlank() || txt.startsWith("<!DOCTYPE", ignoreCase = true) || txt.startsWith("<html", ignoreCase = true)) {
                null
            } else txt.take(maxChars)
        }
    }.getOrNull()

    private fun fetchSitemapUrls(sitemapUrl: String, headersJson: String, limit: Int): List<String> = runCatching {
        val req = Request.Builder()
            .url(sitemapUrl)
            .header("User-Agent", "Mozilla/5.0 (compatible; ForgeMindBot/1.0)")
            .apply { LlmHttpFactory.applyCustomHeaders(this, headersJson) }
            .build()
        fastProbeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val xml = resp.body?.string().orEmpty()
            if (!xml.contains("<loc>", ignoreCase = true)) return emptyList()
            Regex("""<loc>\s*([^<\s]+)\s*</loc>""", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .mapNotNull { m -> normalizeCrawlUrl(m.groupValues[1].trim(), sitemapUrl) }
                .distinct()
                .take(limit)
                .toList()
        }
    }.getOrDefault(emptyList())

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderPageHtmlWithWebView(url: String): String = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val wv = WebView(context.applicationContext)
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
                userAgentString = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    done.complete(Unit)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            wv.loadUrl(url)
            withTimeoutOrNull(12_000) { done.await() }
            delay(450)

            val htmlDeferred = CompletableDeferred<String>()
            wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                htmlDeferred.complete(unescapeEvalJsString(raw))
            }
            withTimeoutOrNull(4_000) { htmlDeferred.await() } ?: ""
        } finally {
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
    }

    private fun unescapeEvalJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            LlmHttpFactory.json.parseToJsonElement(raw).jsonPrimitive.content
        }.getOrElse {
            raw.trim('"')
        }
    }

    private fun searchDuckDuckGo(query: String, count: Int): List<WebSearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<WebSearchResultItem>()
        for (res in doc.select("div.result")) {
            val link = res.selectFirst("a.result__a") ?: continue
            val rawHref = link.attr("href")
            val actualUrl = decodeDuckUrl(rawHref)
            val title = link.text().trim()
            val snippet = res.selectFirst("a.result__snippet, div.result__snippet")?.text()?.trim().orEmpty()
            if (title.isNotBlank() && actualUrl.startsWith("http")) {
                out += WebSearchResultItem(title, actualUrl, snippet)
            }
            if (out.size >= count) break
        }
        return out
    }

    private fun decodeDuckUrl(href: String): String {
        val idx = href.indexOf("uddg=")
        if (idx >= 0) {
            val encoded = href.substring(idx + 5).substringBefore('&')
            return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    private fun searchTavily(query: String, count: Int, apiKey: String): List<WebSearchResultItem> {
        val body = buildJsonObject {
            put("api_key", JsonPrimitive(apiKey.trim()))
            put("query", JsonPrimitive(query))
            put("max_results", JsonPrimitive(count))
        }
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Tavily HTTP ${resp.code}")
            val root = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            return root["results"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                WebSearchResultItem(
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    snippet = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            } ?: emptyList()
        }
    }

    private fun searchBrave(query: String, count: Int, apiKey: String): List<WebSearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$count")
            .header("X-Subscription-Token", apiKey.trim())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Brave HTTP ${resp.code}")
            val root = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            return root["web"]?.jsonObject?.get("results")?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                WebSearchResultItem(
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    snippet = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            } ?: emptyList()
        }
    }

    private fun searchGoogleCse(query: String, count: Int, key: String, cx: String): List<WebSearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val num = count.coerceIn(1, 10)
        val req = Request.Builder()
            .url("https://www.googleapis.com/customsearch/v1?key=${key.trim()}&cx=${cx.trim()}&num=$num&q=$encoded")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Google CSE HTTP ${resp.code}")
            val root = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            return root["items"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                WebSearchResultItem(
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = o["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            } ?: emptyList()
        }
    }

    private fun searchSearxng(query: String, count: Int, baseUrl: String): List<WebSearchResultItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val cleanBase = baseUrl.trim().trimEnd('/')
        val req = Request.Builder()
            .url("$cleanBase/search?q=$encoded&format=json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("SearXNG HTTP ${resp.code}")
            val root = LlmHttpFactory.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            return root["results"]?.jsonArray?.take(count)?.mapNotNull { el ->
                val o = el.jsonObject
                WebSearchResultItem(
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    snippet = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            } ?: emptyList()
        }
    }

    companion object {
        private val SKIP_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif",
            "pdf", "zip", "tar", "gz", "tgz", "7z", "rar", "apk", "exe", "dmg",
            "mp4", "webm", "mp3", "wav", "ogg", "m4a",
            "woff", "woff2", "ttf", "otf", "eot", "css", "js", "mjs", "map"
        )

        private val TRACKING_QUERY_PARAMS = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "yclid", "mc_cid", "mc_eid", "ref"
        )

        fun looksLikeEmptySpa(html: String): Boolean {
            val lower = html.lowercase()
            return (lower.contains("<div id=\"root\"></div>") ||
                lower.contains("<div id=\"app\"></div>") ||
                lower.contains("<div id=\"__next\"></div>") ||
                lower.contains("enable javascript to run")) && Jsoup.parse(html).body().text().trim().length < 220
        }

        fun normalizeCrawlUrl(rawHref: String, baseUrl: String?): String? {
            val trimmed = rawHref.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("#") ||
                trimmed.startsWith("javascript:", ignoreCase = true) ||
                trimmed.startsWith("mailto:", ignoreCase = true) ||
                trimmed.startsWith("tel:", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true)
            ) return null

            val resolved = runCatching {
                if (baseUrl != null) {
                    baseUrl.toHttpUrlOrNull()?.resolve(trimmed)
                        ?: URI(baseUrl).resolve(trimmed).toString().toHttpUrlOrNull()
                } else {
                    trimmed.toHttpUrlOrNull()
                }
            }.getOrNull() ?: return null

            if (resolved.scheme != "http" && resolved.scheme != "https") return null
            val lastSeg = resolved.pathSegments.lastOrNull().orEmpty()
            val ext = lastSeg.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty() && ext in SKIP_EXTENSIONS) return null

            val b = resolved.newBuilder().fragment(null)
            for (name in resolved.queryParameterNames) {
                if (name.lowercase() in TRACKING_QUERY_PARAMS || name.lowercase().startsWith("utm_")) {
                    b.removeAllQueryParameters(name)
                }
            }
            var clean = b.build().toString()
            if (clean.endsWith("/") && resolved.encodedPath != "/") {
                clean = clean.trimEnd('/')
            }
            return clean
        }

        internal fun computePathPrefix(encodedPath: String): String {
            if (encodedPath.isBlank() || encodedPath == "/") return "/"
            if (encodedPath.endsWith("/")) return encodedPath
            val lastSlash = encodedPath.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else encodedPath.substring(0, lastSlash + 1)
        }

        internal fun buildPageSlug(url: String, isRoot: Boolean): String {
            val parsed = url.toHttpUrlOrNull()
            val rawPath = parsed?.encodedPath?.trim('/')?.ifBlank { null }
            if (isRoot && rawPath == null) return "index"
            val slug = (rawPath ?: "page")
                .replace('/', '_')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .replace(Regex("_{2,}"), "_")
                .trim('_')
                .removeSuffix(".html")
                .removeSuffix(".htm")
                .take(48)
            return slug.ifBlank { if (isRoot) "index" else "page" }
        }

        fun documentToRichMarkdown(
            doc: Document,
            cssSelector: String? = null,
            includeNav: Boolean = true,
            navHeaderLabel: String = "Navigation"
        ): String {
            val workDoc = doc.clone()
            val metaDesc = workDoc.selectFirst("meta[name=description], meta[property='og:description']")
                ?.attr("content")?.trim().orEmpty()

            val navLinks = if (includeNav && cssSelector.isNullOrBlank()) {
                workDoc.select("nav a[href], header nav a[href], aside nav a[href]")
                    .mapNotNull { a ->
                        val href = a.attr("abs:href").trim().ifBlank { a.attr("href").trim() }
                        val label = a.text().replace(Regex("\\s+"), " ").trim()
                        if (label.isNotBlank() && href.isNotBlank() &&
                            !href.startsWith("#") && !href.startsWith("javascript:", ignoreCase = true)
                        ) {
                            "- [$label]($href)"
                        } else null
                    }
                    .distinct()
                    .take(30)
            } else emptyList()

            stripNoise(workDoc, removeNav = cssSelector.isNullOrBlank())

            val contentMd = if (!cssSelector.isNullOrBlank()) {
                val sel = workDoc.select(cssSelector)
                if (sel.isEmpty()) return "Selector '$cssSelector' not found on ${doc.location()}"
                sel.joinToString("\n\n") { elementToMarkdown(it) }
            } else {
                val main = workDoc.selectFirst("main, article, [role=main], .markdown-body, .content, #content")
                    ?: workDoc.body()
                elementToMarkdown(main)
            }

            return buildString {
                if (metaDesc.isNotBlank() && cssSelector.isNullOrBlank()) {
                    appendLine("> $metaDesc")
                    appendLine()
                }
                if (navLinks.isNotEmpty()) {
                    appendLine("### $navHeaderLabel")
                    navLinks.forEach { appendLine(it) }
                    appendLine()
                }
                append(contentMd.replace(Regex("\n{3,}"), "\n\n").trim())
            }.trim()
        }

        private fun stripNoise(doc: Document, removeNav: Boolean) {
            val baseSel = "script, style, noscript, iframe, svg, canvas, template, .advertisement, .ads, [aria-hidden=true]"
            doc.select(if (removeNav) "$baseSel, nav, footer" else baseSel).remove()
        }

        private fun extractTablesAsMarkdown(doc: Document): String {
            val tables = doc.select("table")
            if (tables.isEmpty()) return "No tables found."
            return tables.mapIndexed { idx, table ->
                val md = renderTableMarkdown(table)
                if (md.isBlank()) "" else "### Table ${idx + 1}\n$md"
            }.filter { it.isNotBlank() }.joinToString("\n\n")
        }

        private fun renderTableMarkdown(table: Element): String {
            val rows = table.select("tr").map { tr ->
                tr.select("th, td").map { cell ->
                    inlineNodesToMarkdown(cell).replace("\n", " ").replace("|", "\\|").trim()
                }
            }.filter { it.isNotEmpty() }
            if (rows.isEmpty()) return ""
            val colCount = rows.maxOf { it.size }
            fun padRow(r: List<String>): List<String> =
                if (r.size >= colCount) r else r + List(colCount - r.size) { "" }
            return buildString {
                val header = padRow(rows.first())
                appendLine("| " + header.joinToString(" | ") + " |")
                appendLine("| " + header.joinToString(" | ") { "---" } + " |")
                rows.drop(1).forEach { r ->
                    appendLine("| " + padRow(r).joinToString(" | ") + " |")
                }
            }.trimEnd()
        }

        private fun detectCodeLanguage(preOrCode: Element): String {
            val cls = (preOrCode.className() + " " + (preOrCode.selectFirst("code")?.className().orEmpty())).lowercase()
            val m = Regex("""(?:language-|lang-|hljs-)([a-z0-9_+-]+)""").find(cls)
            return m?.groupValues?.get(1).orEmpty()
        }

        internal fun inlineNodeToMarkdown(node: Node): String {
            return when (node) {
                is TextNode -> node.wholeText.replace(Regex("[\\t\\r\\n ]+"), " ")
                is Element -> {
                    when (val tag = node.tagName().lowercase()) {
                        "br" -> "\n"
                        "code" -> {
                            val c = node.text().trim()
                            if (c.isNotEmpty()) "`${c.replace("`", "\\`")}`" else ""
                        }
                        "strong", "b" -> {
                            val inner = inlineNodesToMarkdown(node).trim()
                            if (inner.isNotEmpty()) "**$inner**" else ""
                        }
                        "em", "i" -> {
                            val inner = inlineNodesToMarkdown(node).trim()
                            if (inner.isNotEmpty()) "*$inner*" else ""
                        }
                        "a" -> {
                            val href = node.attr("abs:href").trim().ifBlank { node.attr("href").trim() }
                            val label = inlineNodesToMarkdown(node).trim().ifBlank { href }
                            if (href.isNotBlank() && !href.startsWith("javascript:", ignoreCase = true)) {
                                "[$label]($href)"
                            } else {
                                label
                            }
                        }
                        "img" -> {
                            val src = node.attr("abs:src").trim().ifBlank { node.attr("src").trim() }
                            val alt = node.attr("alt").trim()
                            if (src.isNotBlank() && !src.startsWith("data:", ignoreCase = true)) {
                                "![$alt]($src)"
                            } else ""
                        }
                        "script", "style", "svg", "noscript" -> ""
                        else -> {
                            if (tag in BLOCK_TAGS) {
                                " ${node.text().trim()} "
                            } else {
                                inlineNodesToMarkdown(node)
                            }
                        }
                    }
                }
                else -> ""
            }
        }

        internal fun inlineNodesToMarkdown(root: Node): String {
            val sb = StringBuilder()
            for (child in root.childNodes()) {
                sb.append(inlineNodeToMarkdown(child))
            }
            return sb.toString().replace(Regex(" {2,}"), " ")
        }

        private val BLOCK_TAGS = setOf(
            "div", "section", "article", "main", "header", "footer", "aside",
            "p", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "pre", "blockquote", "table", "hr", "details", "dl", "figure"
        )

        fun elementToMarkdown(el: Element, listDepth: Int = 0): String {
            val sb = StringBuilder()
            val inlineBuf = StringBuilder()

            fun flushInlineBuf() {
                val t = inlineBuf.toString().trim()
                if (t.isNotEmpty()) {
                    sb.append(t).append("\n\n")
                }
                inlineBuf.setLength(0)
            }

            for (node in el.childNodes()) {
                when (node) {
                    is TextNode -> {
                        val txt = node.wholeText.replace(Regex("[\\t\\r\\n ]+"), " ")
                        if (txt.isNotBlank() || inlineBuf.isNotEmpty()) {
                            inlineBuf.append(txt)
                        }
                    }
                    is Element -> {
                        val tag = node.tagName().lowercase()
                        if (tag !in BLOCK_TAGS) {
                            inlineBuf.append(inlineNodeToMarkdown(node))
                            continue
                        }
                        flushInlineBuf()
                        when (tag) {
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                val level = tag.drop(1).toIntOrNull()?.coerceIn(1, 6) ?: 2
                                val headingText = inlineNodesToMarkdown(node).trim()
                                if (headingText.isNotEmpty()) {
                                    sb.append('\n').append("#".repeat(level)).append(' ').append(headingText).append("\n\n")
                                }
                            }
                            "p" -> {
                                val t = inlineNodesToMarkdown(node).trim()
                                if (t.isNotEmpty()) sb.append(t).append("\n\n")
                            }
                            "pre" -> {
                                val lang = detectCodeLanguage(node)
                                val codeEl = node.selectFirst("code") ?: node
                                val rawCode = codeEl.wholeText().trimEnd()
                                if (rawCode.isNotBlank()) {
                                    sb.append("\n```").append(lang).append('\n').append(rawCode).append("\n```\n\n")
                                }
                            }
                            "ul", "ol" -> {
                                val ordered = tag == "ol"
                                val indent = "  ".repeat(listDepth)
                                var idx = 1
                                for (li in node.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
                                    val nestedLists = li.children().filter {
                                        val lt = it.tagName().lowercase()
                                        lt == "ul" || lt == "ol"
                                    }
                                    val liClone = li.clone()
                                    liClone.select("> ul, > ol").remove()
                                    val itemText = inlineNodesToMarkdown(liClone).trim()
                                    val bullet = if (ordered) "${idx++}. " else "- "
                                    if (itemText.isNotEmpty()) {
                                        sb.append(indent).append(bullet).append(itemText).append('\n')
                                    }
                                    for (nested in nestedLists) {
                                        sb.append(elementToMarkdown(nested, listDepth + 1).trimEnd()).append('\n')
                                    }
                                }
                                if (listDepth == 0) sb.append('\n')
                            }
                            "blockquote" -> {
                                val inner = elementToMarkdown(node, listDepth).trim()
                                if (inner.isNotEmpty()) {
                                    inner.lines().forEach { line ->
                                        sb.append("> ").append(line).append('\n')
                                    }
                                    sb.append('\n')
                                }
                            }
                            "table" -> {
                                val tbl = renderTableMarkdown(node)
                                if (tbl.isNotEmpty()) sb.append('\n').append(tbl).append("\n\n")
                            }
                            "hr" -> sb.append("\n---\n\n")
                            "details" -> {
                                val sum = node.selectFirst("summary")?.let { inlineNodesToMarkdown(it).trim() }
                                if (!sum.isNullOrBlank()) sb.append("**").append(sum).append("**\n\n")
                                val clone = node.clone()
                                clone.select("> summary").remove()
                                sb.append(elementToMarkdown(clone, listDepth))
                            }
                            else -> {
                                sb.append(elementToMarkdown(node, listDepth))
                            }
                        }
                    }
                }
            }
            flushInlineBuf()
            if (sb.isBlank()) {
                return inlineNodesToMarkdown(el).trim()
            }
            return sb.toString()
        }
    }
}
