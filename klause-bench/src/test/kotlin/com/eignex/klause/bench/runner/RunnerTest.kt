package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.formats.dimacs.DimacsFormatException
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunnerTest {
    @ParameterizedTest
    @ValueSource(strings = ["invalid source", "p wcnf 1 1 10\n10 1 0\n", "p wcnf 0 1 10\n7 0\n"])
    fun `weighted subprocess metadata should not parse the source`(text: String, @TempDir root: Path) {
        val source = root.resolve("source.wcnf").toFile().apply { writeText(text) }
        val ref = ProblemRef("weighted", Format.WCNF, ProblemSource.Prepared(source),
            Category.OPTIMIZATION, Expected.Unknown)

        val entry = InProcessRunner.resolve(ref)

        assertTrue(entry.optimizes)
        assertFalse(entry.maximize)
    }

    @Test
    fun `weighted in process objective should retain penalties and fixed cost`(@TempDir root: Path) {
        val source = root.resolve("source.wcnf").toFile().apply {
            writeText("p wcnf 2 3 100\n5 1 0\n7 -2 0\n11 0\n")
        }
        val ref = ProblemRef("weighted", Format.WCNF, ProblemSource.Prepared(source),
            Category.OPTIMIZATION, Expected.Unknown)
        val entry = InProcessRunner.resolve(ref)

        val objective = assertNotNull(entry.objective)

        assertEquals(11L, objective.constant)
        assertContentEquals(longArrayOf(0, 0, 5, 7), objective.boolWeights)
        assertEquals(4, entry.problem.numBoolVars)
    }

    @Test
    fun `weighted in process consumers should reject malformed input`(@TempDir root: Path) {
        val source = root.resolve("source.wcnf").toFile().apply { writeText("invalid source") }
        val ref = ProblemRef("weighted", Format.WCNF, ProblemSource.Prepared(source),
            Category.OPTIMIZATION, Expected.Unknown)
        val entry = InProcessRunner.resolve(ref)

        assertFailsWith<DimacsFormatException> { entry.objective }
    }
}
