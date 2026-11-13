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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String
)

class WebScraperEngine(
    private val context: Context
) {
    private val client = LlmHttpFactory.baseClientBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
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
                // Always try DuckDuckGo fallback if primary returned nothing
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
                appendLine("Use web_scrape to open and parse any of these pages.")
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
            val cleanUrl = url.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                error("URL must start with http:// or https://")
            }

            var html = if (renderJs && settings.enableJsScrape) {
                renderPageHtmlWithWebView(cleanUrl)
            } else {
                fetchRawHtml(cleanUrl, headersJson)
            }

            // Auto-upgrade to headless WebView if page looks like an empty JS SPA shell
            if (!renderJs && settings.enableJsScrape && looksLikeEmptySpa(html)) {
                runCatching { renderPageHtmlWithWebView(cleanUrl) }.getOrNull()?.let { jsHtml ->
                    if (jsHtml.length > html.length / 2) html = jsHtml
                }
            }

            val doc = Jsoup.parse(html, cleanUrl)
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
                    stripNoise(doc)
                    val target = if (!cssSelector.isNullOrBlank()) doc.select(cssSelector).text() else doc.body().text()
                    target.replace(Regex("\\s{3,}"), "\n\n").take(maxChars)
                }
                else -> {
                    // markdown
                    stripNoise(doc)
                    val root = if (!cssSelector.isNullOrBlank()) {
                        val sel = doc.select(cssSelector)
                        if (sel.isEmpty()) return@runCatching "Selector '$cssSelector' not found on $cleanUrl"
                        sel.joinToString("\n\n") { elementToMarkdown(it) }
                    } else {
                        val main = doc.selectFirst("main, article, [role=main]") ?: doc.body()
                        elementToMarkdown(main)
                    }
                    root.replace(Regex("\n{3,}"), "\n\n").trim().take(maxChars)
                }
            }

            buildString {
                appendLine("URL: $cleanUrl")
                if (title.isNotBlank()) appendLine("Title: $title")
                appendLine("<external_content untrusted=\"true\" source=\"web_scrape\">")
                appendLine(bodyOut.ifBlank { "(Empty text output)" })
                append("</external_content>")
            }
        }
    }

    private fun fetchRawHtml(url: String, headersJson: String): String {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { LlmHttpFactory.applyCustomHeaders(this, headersJson) }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} loading $url")
            return resp.body?.string().orEmpty()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderPageHtmlWithWebView(url: String): String = withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val wv = WebView(context.applicationContext)
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    done.complete(Unit)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            wv.loadUrl(url)
            withTimeoutOrNull(14_000) { done.await() }
            delay(1_400) // Allow async SPA fetch/hydration

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
        fun looksLikeEmptySpa(html: String): Boolean {
            val lower = html.lowercase()
            return (lower.contains("<div id=\"root\"></div>") ||
                lower.contains("<div id=\"app\"></div>") ||
                lower.contains("enable javascript to run")) && Jsoup.parse(html).body().text().trim().length < 220
        }

        private fun stripNoise(doc: org.jsoup.nodes.Document) {
            doc.select("script, style, noscript, iframe, svg, nav, footer, .advertisement, .ads").remove()
        }

        private fun extractTablesAsMarkdown(doc: org.jsoup.nodes.Document): String {
            val tables = doc.select("table")
            if (tables.isEmpty()) return "No tables found."
            return tables.mapIndexed { idx, table ->
                val rows = table.select("tr").map { tr ->
                    tr.select("th, td").map { it.text().replace("|", "\\|").trim() }
                }.filter { it.isNotEmpty() }
                if (rows.isEmpty()) "" else buildString {
                    appendLine("### Table ${idx + 1}")
                    val header = rows.first()
                    appendLine("| " + header.joinToString(" | ") + " |")
                    appendLine("| " + header.joinToString(" | ") { "---" } + " |")
                    rows.drop(1).forEach { r ->
                        appendLine("| " + r.joinToString(" | ") + " |")
                    }
                }
            }.filter { it.isNotBlank() }.joinToString("\n\n")
        }

        fun elementToMarkdown(el: Element): String {
            val sb = StringBuilder()
            for (child in el.children()) {
                when (child.tagName().lowercase()) {
                    "h1" -> sb.append("\n# ").append(child.text().trim()).append("\n\n")
                    "h2" -> sb.append("\n## ").append(child.text().trim()).append("\n\n")
                    "h3", "h4" -> sb.append("\n### ").append(child.text().trim()).append("\n\n")
                    "p" -> {
                        val t = child.text().trim()
                        if (t.isNotEmpty()) sb.append(t).append("\n\n")
                    }
                    "pre" -> sb.append("\n```\n").append(child.text()).append("\n```\n\n")
                    "ul", "ol" -> {
                        child.select("> li").forEach { li ->
                            sb.append("- ").append(li.text().trim()).append('\n')
                        }
                        sb.append('\n')
                    }
                    "blockquote" -> sb.append("> ").append(child.text().trim()).append("\n\n")
                    "table" -> {
                        val rows = child.select("tr")
                        rows.forEachIndexed { idx, tr ->
                            val cells = tr.select("th, td").map { it.text().trim() }
                            if (cells.isNotEmpty()) {
                                sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
                                if (idx == 0) sb.append("| ").append(cells.joinToString(" | ") { "---" }).append(" |\n")
                            }
                        }
                        sb.append('\n')
                    }
                    else -> {
                        if (child.childrenSize() > 0) sb.append(elementToMarkdown(child))
                        else {
                            val t = child.text().trim()
                            if (t.isNotEmpty()) sb.append(t).append("\n\n")
                        }
                    }
                }
            }
            if (sb.isBlank()) {
                return el.text().trim()
            }
            return sb.toString()
        }
    }
}
