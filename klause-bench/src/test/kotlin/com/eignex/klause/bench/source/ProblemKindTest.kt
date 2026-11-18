package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.catalog.corpus
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProblemKindTest {
    companion object {
        @JvmStatic
        fun miniZincSources(): List<Arguments> = listOf(
            Arguments.of("solve :: int_search([x], first_fail, indomain_min, complete)\nminimize objective;", true),
            Arguments.of("% solve minimize objective;\nsolve satisfy;", false),
            Arguments.of("constraint solve_count = maximize_calls;\nsolve satisfy;", false),
        )
    }

    @Test
    fun `weighted maxsat should be COP`() {
        val ref = ProblemRef(
            "conditional-cost",
            Format.WCNF,
            corpus("conditional-symmetry/conditional-cost.wcnf"),
            Category.OPTIMIZATION,
            Expected.Unknown,
        )

        assertTrue(ProblemKind.isCop(ref))
    }

    @ParameterizedTest
    @MethodSource("miniZincSources")
    fun `MiniZinc classification should read the solve directive`(text: String, cop: Boolean, @TempDir root: Path) {
        val source = root.resolve("source.mzn").toFile().apply { writeText(text) }
        val ref = ProblemRef("source", Format.MINIZINC, ProblemSource.Prepared(source),
            Category.CSP, Expected.Unknown)

        assertEquals(cop, ProblemKind.isCop(ref))
    }
}
