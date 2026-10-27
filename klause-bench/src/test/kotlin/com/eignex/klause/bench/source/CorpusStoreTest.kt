package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CorpusStoreTest {
    @Test
    fun `fresh workspaces reuse an already fetched collection without fetching again`() {
        val root = Files.createTempDirectory("corpusstore").toFile()
        try {
            val cache = File(root, "cache")
            var fetches = 0
            for (name in listOf("first", "second")) {
                val workspace = File(root, name)
                val collection = CorpusStore(cache, workspace).ensure("fixture") { staging ->
                    fetches++
                    staging.mkdirs()
                    File(staging, "model.opb").writeText("original instance")
                }
                assertEquals(File(workspace, "fixture"), collection)
                assertEquals("original instance", File(collection, "model.opb").readText())
            }
            assertEquals(1, fetches)
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed fetches leave no published collection and a later attempt can complete`(shared: Boolean) {
        val root = Files.createTempDirectory("corpusstore").toFile()
        try {
            val cache = File(root, "cache")
            val workspace = if (shared) File(root, "workspace") else cache
            val store = CorpusStore(cache, workspace)
            assertFailsWith<IOException> {
                store.ensure("fixture") { staging ->
                    staging.mkdirs()
                    File(staging, "partial.opb").writeText("incomplete")
                    throw IOException("transfer interrupted")
                }
            }
            assertFalse(File(cache, "fixture").exists())
            assertFalse(File(workspace, "fixture").exists())

            val collection = store.ensure("fixture") { staging ->
                staging.mkdirs()
                File(staging, "model.opb").writeText("complete")
            }

            assertEquals(listOf("model.opb"), collection.list()!!.toList())
            assertEquals("complete", File(collection, "model.opb").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an interrupted unpublished collection is discarded before another fetch`() {
        val root = Files.createTempDirectory("corpusstore").toFile()
        try {
            val staging = File(root, ".fetch/fixture/collection")
            staging.mkdirs()
            File(staging, "partial.opb").writeText("interrupted")

            val collection = CorpusStore(root).ensure("fixture") { fresh ->
                fresh.mkdirs()
                File(fresh, "model.opb").writeText("complete")
            }

            assertEquals(listOf("model.opb"), collection.list()!!.toList())
            assertFalse(File(root, ".fetch/fixture").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a workspace includes only the requested collection from an existing cache`() {
        val root = Files.createTempDirectory("corpusstore").toFile()
        try {
            val cache = File(root, "cache")
            for (name in listOf("requested", "unrelated")) {
                File(cache, name).mkdirs()
                File(cache, "$name/model.opb").writeText(name)
            }
            val workspace = File(root, "workspace")

            val collection = CorpusStore(cache, workspace).ensure("requested") {
                error("cached collection must work offline")
            }

            assertEquals("requested", File(collection, "model.opb").readText())
            assertEquals(listOf("requested"), workspace.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
