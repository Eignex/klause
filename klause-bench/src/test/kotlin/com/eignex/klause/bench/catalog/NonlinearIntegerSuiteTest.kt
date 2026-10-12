package com.eignex.klause.bench.catalog

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NonlinearIntegerSuiteTest {
    @Test
    fun `named set retains exactly the versioned source identities`() {
        val suite = Catalog.suite("smtlib-nonlinear-integer-v1")

        val selected = ProblemSets.load(listOf("nonlinear-integer-v1"))

        assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
    }

    @Test
    fun `fixture bytes and membership match their manifest`() {
        val root = CorpusFetcher.workspaceRoot()
        val manifest = Json.parseToJsonElement(
            File(root, "klause-bench/smoke-corpus/nonlinear-integer-v1/manifest.json").readText(),
        ).jsonObject.getValue("cases").jsonArray

        assertEquals(NonlinearIntegerSuite.suite.problems.map { it.name }.sorted(), manifest.map {
            it.jsonObject.getValue("name").jsonPrimitive.content
        }.sorted())
        for (case in manifest) {
            val row = case.jsonObject
            val bytes = File(root, row.getValue("path").jsonPrimitive.content).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            assertEquals(row.getValue("sha256").jsonPrimitive.content, hash)
        }
    }
}
