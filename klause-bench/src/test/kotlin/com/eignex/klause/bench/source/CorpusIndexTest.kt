package com.eignex.klause.bench.source

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorpusIndexTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 12])
    fun `input indexes retain original bytes across compressed encodings`(level: Int) {
        val root = Files.createTempDirectory("inputindex").toFile()
        try {
            val bytes = "original source bytes\n".encodeToByteArray()
            root.resolve("model.cnf").writeText("stale plain sibling")
            ZstdOutputStreamNoFinalizer(root.resolve("model.cnf.zst").outputStream(), level)
                .setChecksum(level == 12)
                .use { it.write(bytes) }

            val index = CommittedCorpusIndexes.fromSources("fixture", root)

            assertEquals(
                listOf(CorpusIndex.Entry(
                    "model.cnf", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    bytes.size.toLong(),
                )),
                index.files,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an index lists what the pack holds and survives its wire form`() {
        val root = Files.createTempDirectory("corpusindex").toFile()
        try {
            File(root, "nested").mkdirs()
            File(root, "nested/model.opb.zst").writeText("model")
            File(root, "README").writeText("readme")
            File(root, ".plain").mkdirs()
            File(root, ".plain/staged.opb").writeText("staged")

            val index = CorpusIndex.of("fixture", root)

            assertEquals(listOf("README", "nested/model.opb.zst"), index.files.map { it.path })
            assertEquals(5L, index.entry("nested/model.opb.zst")?.bytes)
            assertEquals(index, CorpusIndex.decode(CorpusIndex.encode(index)))
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../escape", "/escape", "a//b", "a/./b", "a\\b"])
    fun `an index cannot name a path outside its collection`(path: String) {
        assertFailsWith<IllegalArgumentException> {
            CorpusIndex("fixture", listOf(CorpusIndex.Entry(path, "a".repeat(64), 1)))
        }
    }

    @Test
    fun `a listing view has every indexed path and no content`() {
        val root = Files.createTempDirectory("corpusindex").toFile()
        try {
            val index = CorpusIndex(
                "fixture",
                listOf(
                    CorpusIndex.Entry("deep/a.cnf.zst", "a".repeat(64), 9),
                    CorpusIndex.Entry("b.cnf", "b".repeat(64), 3),
                ),
            )
            val view = CorpusIndexes(root).view(index)

            assertEquals(view.root, CorpusIndexes(root).view(index).root)
            assertEquals(index.files[0], view.entry(File(view.root, "deep/a.cnf.zst")))
            assertEquals(
                listOf("b.cnf", "deep/a.cnf.zst"),
                view.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(view.root).invariantSeparatorsPath }
                    .sorted().toList(),
            )
            assertEquals(0L, File(view.root, "b.cnf").length())
            assertEquals(null, CorpusIndexes(root).local("fixture"))
            CorpusIndexes(root).save(index)
            assertEquals(index, CorpusIndexes(root).local("fixture"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent planners share a complete listing view`() {
        val root = Files.createTempDirectory("corpusindex").toFile()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val index = CorpusIndex(
                "fixture",
                List(32) { CorpusIndex.Entry("nested/$it.cnf", "a".repeat(64), 9) },
            )
            val ready = CyclicBarrier(4)
            val views = List(4) {
                workers.submit<IndexedCollection> {
                    ready.await(1, TimeUnit.SECONDS)
                    CorpusIndexes(root).view(index)
                }
            }.map { it.get(1, TimeUnit.SECONDS) }

            assertEquals(1, views.map { it.root }.distinct().size)
            for (view in views) {
                assertEquals(
                    index.files.map { it.path }.sorted(),
                    view.root.walkTopDown().filter { it.isFile }
                        .map { it.relativeTo(view.root).invariantSeparatorsPath }.sorted().toList(),
                )
                assertEquals(0L, view.root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(1, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file cannot replace a listing directory`() {
        val root = Files.createTempDirectory("corpusindex").toFile()
        try {
            val index = CorpusIndex("fixture", listOf(CorpusIndex.Entry("a.cnf", "a".repeat(64), 9)))
            val view = CorpusIndexes(root).view(index)
            view.root.deleteRecursively()
            view.root.writeText("occupied")

            assertFailsWith<FileSystemException> { CorpusIndexes(root).view(index) }
            assertEquals("occupied", view.root.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
