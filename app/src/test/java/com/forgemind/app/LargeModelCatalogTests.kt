package com.forgemind.app

import com.forgemind.app.data.model.ModelInfo
import com.forgemind.app.data.model.ProviderConfig
import com.forgemind.app.data.model.ProviderType
import com.forgemind.app.llm.ModelScanner
import com.forgemind.app.ui.components.PreparedModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeModelCatalogTests {

    @Test
    fun `parseOpenAiModelsJson deduplicates aimlapi dynamic-router and 3500 models with unique keys`() {
        val sb = StringBuilder(512_000)
        sb.append("""{"object":"list","data":[""")
        // Exact real-world duplicate crash reproduction ("aimlapi/dynamic-router")
        sb.append("""{"id":"aimlapi/dynamic-router","type":"chat","context_length":65536},""")
        sb.append("""{"id":"aimlapi/dynamic-router","display_name":"AIML Dynamic Router","type":"text","context_length":131072},""")
        sb.append("""{"id":"  aimlapi/dynamic-router  ","type":"vision","context_length":32768},""")
        sb.append("""{"id":"   ","type":"chat"},""")

        val totalRaw = 3_500
        for (i in 0 until totalRaw) {
            if (i > 0) sb.append(',')
            val id = if (i % 7 == 0) "provider-dup/model-${i / 7}" else "provider-${i % 19}/model-$i${if (i % 25 == 0) ":free" else ""}"
            val typeTag = "cat_${i % 60}"
            val longName = "Model $i " + "X".repeat(if (i % 100 == 0) 400 else 12)
            sb.append("""{"id":"$id","display_name":"$longName","type":"$typeTag","context_length":${(i % 8 + 1) * 16384}}""")
        }
        sb.append("]}")

        val parsed = ModelScanner.parseOpenAiModelsJson(sb.toString(), ProviderType.CUSTOM)
        val uniqueIds = parsed.map { it.id }.toSet()

        // Strictly zero duplicate LazyColumn keys and zero blank IDs
        assertEquals(parsed.size, uniqueIds.size)
        assertTrue(parsed.none { it.id.isBlank() })
        assertTrue(parsed.size > 2_500)

        val router = parsed.first { it.id == "aimlapi/dynamic-router" }
        assertEquals("AIML Dynamic Router", router.name)
        assertEquals(131_072, router.contextLength)
        assertTrue(router.pricingHint.orEmpty().contains("chat"))

        // Display names are bounded so rogue payloads cannot overflow layout constraints
        assertTrue(parsed.all { it.name.length <= 180 })
    }

    @Test
    fun `PreparedModelCatalog guarantees unique keys caps categories and filters 4000 models in milliseconds`() {
        val rawWithDuplicates = buildList {
            add(ModelInfo("aimlapi/dynamic-router", "aimlapi/dynamic-router", 64_000, "chat"))
            add(ModelInfo("aimlapi/dynamic-router", "Dynamic Router", 128_000, "vision"))
            add(ModelInfo("", "Blank Should Drop"))
            for (i in 0 until 4_000) {
                add(
                    ModelInfo(
                        id = "org-${i % 30}/llm-$i${if (i % 50 == 0) ":free" else ""}",
                        name = "LLM $i",
                        contextLength = 32_768,
                        pricingHint = "tag_${i % 45}"
                    )
                )
            }
            // Add another duplicate at the tail
            add(ModelInfo("org-0/llm-0:free", "Duplicate Free", 65_536, "FREE"))
        }

        val merged = ModelScanner.mergeSavedModels(
            rawWithDuplicates,
            ProviderConfig(
                providerId = "custom_big",
                savedModels = listOf("pinned/alpha", "pinned/alpha", "aimlapi/dynamic-router", "  ")
            )
        )

        val catalog = PreparedModelCatalog.from(merged)
        val keys = catalog.items.map { it.model.id }
        assertEquals("Every LazyColumn key must be 100% unique", keys.size, keys.toSet().size)
        assertFalse(keys.any { it.isBlank() })
        assertTrue("Category chips must be capped to prevent horizontal Constraints overflow", catalog.categories.size <= 20)
        assertTrue(catalog.hasAnyFree)

        val favSet = setOf("org-5/llm-1005", "aimlapi/dynamic-router")
        val t0 = System.nanoTime()
        val allWithFavs = catalog.filter(query = "", favoriteIdsForProvider = favSet)
        val queryHits = catalog.filter(query = "dynamic-router", favoriteIdsForProvider = favSet)
        val freeHits = catalog.filter(query = "", onlyFree = true)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertEquals(catalog.size, allWithFavs.size)
        assertTrue("Favorites must sort to the top", allWithFavs.first().model.id in favSet)
        assertEquals(1, queryHits.size)
        assertEquals("aimlapi/dynamic-router", queryHits.first().model.id)
        assertTrue(freeHits.isNotEmpty() && freeHits.all { it.isFree })
        assertTrue("Filtering 4,000 models took ${elapsedMs}ms (expected < 250ms)", elapsedMs < 250)
    }
}
