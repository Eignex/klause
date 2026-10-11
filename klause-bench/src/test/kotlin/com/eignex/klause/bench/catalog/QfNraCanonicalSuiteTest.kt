package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QfNraCanonicalSuiteTest {
    @Test
    fun `canonical set selects its catalog identities without solver availability`() {
        val suite = Catalog.suite("smtlib-qfnra-source-canonical-v1")
        val selected = ProblemSets.load(listOf("qfnra-source-canonical-v1"))

        assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
    }

    @Test
    fun `canonical identities are separate from frozen original selections`() {
        val selected = ProblemSets.load(listOf("qfnra-source-canonical-v1"))
        val originals = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1", "qfnra-extensions-v1"))

        assertTrue(selected.toSet().intersect(originals.toSet()).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["degeneracy/degree-drop", "degeneracy/equal-degree-common-root"])
    fun `canonical source preserves original bytes except negative constant syntax`(name: String) {
        val root = CorpusFetcher.workspaceRoot()
        val manifest = File(root, "klause-bench/smoke-corpus/qfnra-source-canonical-v1/manifest.json")
        val row = Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("cases").jsonArray
            .single { it.jsonObject.getValue("name").jsonPrimitive.content == name }.jsonObject
        val original = row.getValue("derivation").jsonObject
        val originalBytes = File(root, original.getValue("path").jsonPrimitive.content).readBytes()
        val canonicalBytes = File(root, row.getValue("path").jsonPrimitive.content).readBytes()

        for ((metadata, bytes) in listOf(original to originalBytes, row to canonicalBytes)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            assertEquals(metadata.getValue("sha256").jsonPrimitive.content, hash)
        }
        assertEquals(originalBytes.decodeToString().replace(" -1", " (- 1)"), canonicalBytes.decodeToString())
    }
}
