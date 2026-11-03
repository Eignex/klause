package com.eignex.klause.bench.lab

import com.eignex.klause.bench.source.CorpusIndex
import com.eignex.klause.bench.source.CorpusIndexes
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchStatus
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexedCatalogAdapterTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `catalog identity uses the pack index with absent or divergent local bytes`(local: Boolean) {
        val root = Files.createTempDirectory("indexed-catalog-")
        val properties = listOf("klause.bench.corpusCache", "klause.bench.corpus.shared",
            "klause.bench.declaredInputs", "klause.bench.indexOnly").associateWith(System::getProperty)
        try {
            System.setProperty("klause.bench.corpusCache", root.toString())
            System.setProperty("klause.bench.corpus.shared", "false")
            System.setProperty("klause.bench.declaredInputs", "true")
            val path = "PB24/DEC-LIN/offline/tiny.opb.zst"
            CorpusIndexes(root.toFile()).save(CorpusIndex("pb-comp-2024",
                listOf(CorpusIndex.Entry(path, "1".repeat(64), 123))))
            if (local) {
                val file = root.resolve("pb-comp-2024/$path")
                Files.createDirectories(file.parent)
                Files.writeString(file, "divergent local bytes")
            }

            val case = IndexedCatalogSuite.plan(BenchRequest(mapOf("suite" to "pb-comp", "name" to "DEC-LIN")))
                .single()
            System.setProperty("klause.bench.declaredInputs", "false")
            val out = Files.createDirectory(root.resolve("out"))
            val result = IndexedCatalogSuite.run(case, BenchContext(out))

            assertEquals(BenchStatus.SUCCESS, result.status)
            val rows = Json.parseToJsonElement(Files.readString(out.resolve("catalog.json"))).jsonArray
            assertEquals(1, rows.size)
            val row = rows.single().jsonObject
            assertEquals("pb-comp-2024/$path", row.getValue("packedPath").jsonPrimitive.content)
            assertEquals("1".repeat(64), row.getValue("packedSha256").jsonPrimitive.content)
            assertEquals("123", row.getValue("packedBytes").jsonPrimitive.content)
        } finally {
            properties.forEach { (name, value) ->
                if (value == null) System.clearProperty(name) else System.setProperty(name, value)
            }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog planning requires the SDK declared input context`() {
        val previous = System.getProperty("klause.bench.declaredInputs")
        try {
            System.setProperty("klause.bench.declaredInputs", "false")

            assertFailsWith<IllegalArgumentException> {
                IndexedCatalogSuite.plan(BenchRequest(mapOf("suite" to "pb-comp")))
            }
        } finally {
            if (previous == null) System.clearProperty("klause.bench.declaredInputs")
            else System.setProperty("klause.bench.declaredInputs", previous)
        }
    }
}
