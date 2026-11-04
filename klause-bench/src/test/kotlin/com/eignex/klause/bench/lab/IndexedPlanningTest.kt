package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ExternalCollection
import com.eignex.klause.bench.catalog.FetchMethod
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusIndex
import com.eignex.klause.bench.source.CorpusIndexes
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexedPlanningTest {
    /** Fetching this collection fails at once, so a plan that touches its bytes fails the test. */
    private val collection = ExternalCollection(
        "indexed-fixture",
        "https://fixture.invalid/indexed",
        "test",
        "indexed planning fixture",
        FetchMethod.Tar,
    )

    private val files = mapOf(
        "set/family-a/one.cnf.zst" to "packed one",
        "set/family-a/two.cnf" to "p cnf 1 1\n1 0\n",
        "set/family-b/three.cnf.zst" to "packed three",
        "set/README" to "not an instance",
    )

    private fun select() = CorpusSelection.select(
        collection,
        CorpusSelection.Layout.Flat("set", "cnf"),
        CorpusSelection.Selection(),
        Category.SAT,
        format = Format.DIMACS,
    )

    @Test
    fun `declared input planning selects and hashes from the index without corpus bytes`() = withCache { cache ->
        val materialized = File(cache, "materialized").also { File(it, collection.id).let(::write) }
        val index = CorpusIndex.of(collection.id, File(materialized, collection.id))
        val indexed = File(cache, "indexed").also { CorpusIndexes(it).save(index) }

        val expected = planned(materialized, declared = false)
        val actual = planned(indexed, declared = true)

        assertEquals(expected, actual)
        assertEquals(
            listOf("one", "two", "three").map { name ->
                val path = files.keys.single { it.substringAfterLast('/').startsWith("$name.") }
                BenchInput("${collection.id}/$path", BenchInputRoot.CORPUS, "model", sha256(files.getValue(path)))
            },
            actual.sortedBy {
                listOf(
                    "one",
                    "two",
                    "three",
                ).indexOf(it.path.substringAfterLast('/').substringBefore('.'))
            },
        )
        assertFalse(File(indexed, collection.id).exists(), "planning must not materialize the collection")
        assertTrue(
            File(
                indexed,
                ".index",
            ).walkTopDown().filter { it.isFile }.all { it.extension == "json" || it.length() == 0L },
        )
    }

    @Test
    fun `an index is used only for a lab that accepts declared inputs and only while the bytes are absent`() =
        withCache { cache ->
            CorpusIndexes(cache).save(CorpusIndex(collection.id, emptyList()))
            assertEquals(null, withProperties(cache, declared = false) { CorpusFetcher.indexed(collection) })
            assertTrue(withProperties(cache, declared = true) { CorpusFetcher.indexed(collection) } != null)
            write(File(cache, collection.id))
            assertEquals(null, withProperties(cache, declared = true) { CorpusFetcher.indexed(collection) })
        }

    private fun planned(cache: File, declared: Boolean): List<BenchInput> = withProperties(cache, declared) {
        select().flatMap { LabInputs.resolve(it, inputOnly = true) }
    }

    private fun write(collectionRoot: File) {
        for ((path, text) in files) File(collectionRoot, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun sha256(text: String) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private fun <T> withProperties(cache: File, declared: Boolean, block: () -> T): T {
        val saved = listOf("klause.bench.corpusCache", "klause.bench.declaredInputs").associateWith(System::getProperty)
        try {
            System.setProperty("klause.bench.corpusCache", cache.path)
            System.setProperty("klause.bench.declaredInputs", declared.toString())
            return block()
        } finally {
            saved.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(
                        key,
                    )
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }

    private fun withCache(block: (File) -> Unit) {
        val root = Files.createTempDirectory("indexed-planning").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
