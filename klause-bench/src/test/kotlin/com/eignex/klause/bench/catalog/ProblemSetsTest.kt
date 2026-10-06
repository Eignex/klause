package com.eignex.klause.bench.catalog

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProblemSetsTest {
    @Test
    fun `a set reads its problems and its includes in order, each once`() {
        val root = Files.createTempDirectory("sets").toFile()
        try {
            root.resolve("a.txt").writeText("# a\nhakank/x/x\nsatlib/uf20-01  # one\n")
            root.resolve("b.txt").writeText("@a\nhakank/x/x\nmiplib2017/air04\n")

            assertEquals(
                listOf(SetEntry("hakank", "x/x"), SetEntry("satlib", "uf20-01"), SetEntry("miplib2017", "air04")),
                ProblemSets.load(listOf("b"), root),
            )
            assertEquals(listOf("a", "b"), ProblemSets.names(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a set that includes itself is refused`() {
        val root = Files.createTempDirectory("sets").toFile()
        try {
            root.resolve("a.txt").writeText("@b\n")
            root.resolve("b.txt").writeText("@a\n")

            assertFailsWith<IllegalArgumentException> { ProblemSets.load(listOf("a"), root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
