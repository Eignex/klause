package com.eignex.klause.bench.source

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorpusIndexTest {
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
}
