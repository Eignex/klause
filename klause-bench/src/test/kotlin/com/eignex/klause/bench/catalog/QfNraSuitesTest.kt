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
import kotlin.test.assertTrue

class QfNraSuitesTest {
    @Test
    fun `frozen sets select all declared identities without solver availability`() {
        for (tier in listOf("core", "holdout", "extensions")) {
            val suite = Catalog.suite("smtlib-qfnra-$tier-v1")
            val selected = ProblemSets.load(listOf("qfnra-$tier-v1"))

            assertEquals(suite.problems.map { SetEntry(suite.id, it.name) }, selected)
            assertTrue(suite.problems.all { it.format == Format.SMTLIB })
        }
    }

    @Test
    fun `fixture bytes match the complete versioned manifest`() {
        val root = CorpusFetcher.workspaceRoot()
        val file = File(root, "klause-bench/smoke-corpus/qfnra-v1/manifest.json")
        val manifest = Json.parseToJsonElement(file.readText())
        val cases = manifest.jsonObject.getValue("cases").jsonArray
        val expected = QfNraSuites.all.flatMap { suite -> suite.problems.map { "${suite.id}/${it.name}" } }

        assertEquals(expected.sorted(), cases.map {
            val row = it.jsonObject
            "${row.getValue("suite").jsonPrimitive.content}/${row.getValue("name").jsonPrimitive.content}"
        })
        for (case in cases) {
            val row = case.jsonObject
            val bytes = File(root, row.getValue("path").jsonPrimitive.content).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            assertEquals(row.getValue("sha256").jsonPrimitive.content, hash)
        }
    }

    @Test
    fun `polynomial selections exclude the explicit extension controls`() {
        val polynomial = ProblemSets.load(listOf("qfnra-core-v1", "qfnra-holdout-v1"))
        val controls = ProblemSets.load(listOf("qfnra-extensions-v1"))

        assertTrue(polynomial.toSet().intersect(controls.toSet()).isEmpty())
        assertEquals(listOf(37, 24, 5), QfNraSuites.all.map { it.problems.size })
        assertTrue(QfNraSuites.all.take(2).flatMap { it.problems }.all { "polynomial-real" in it.tags })
        assertTrue(QfNraSuites.all.last().problems.all { it.expected == Expected.Unknown })
    }
}
