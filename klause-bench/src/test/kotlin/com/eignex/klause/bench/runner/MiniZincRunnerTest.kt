package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.formats.flatzinc.FlatZincParseException
import com.eignex.klause.formats.flatzinc.parseFlatZincExecution
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class MiniZincRunnerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initializeParser() {
            val problem = parseFlatZincExecution("var 0..2: x; solve satisfy;").program.problem
            BacktrackSolver(problem.bake()).openEnumerate(BacktrackParams()).use { it.next() }
        }
    }

    @ParameterizedTest
    @EnumSource(ObjectiveSense::class)
    fun `deferred objectives should retain canonical direction`(sense: ObjectiveSense, @TempDir root: Path) {
        val source = root.resolve("source.fzn").toFile().apply {
            writeText("var 0..2: x; solve ${sense.name.lowercase()} x;")
        }
        val ref = ProblemRef("compiled", Format.MINIZINC, ProblemSource.Prepared(source),
            Category.OPTIMIZATION, Expected.Unknown)
        val entry = MiniZincRunner().resolveCompiled(ref, source)

        val objective = assertNotNull(entry.objective)

        assertEquals(if (sense == ObjectiveSense.MAXIMIZE) -2L else 2L,
            objective.evaluateLong(Sample(BooleanArray(0), longArrayOf(2))))
    }

    @Test
    fun `deferred search annotations should retain the first selected value`(@TempDir root: Path) {
        val source = root.resolve("source.fzn").toFile().apply {
            writeText("var 0..2: x; solve :: int_search([x], input_order, indomain_max, complete) satisfy;")
        }
        val ref = ProblemRef("compiled", Format.MINIZINC, ProblemSource.Prepared(source),
            Category.CSP, Expected.Unknown)
        val entry = MiniZincRunner().resolveCompiled(ref, source)

        val sample = BacktrackSolver(entry.problem.bake()).openEnumerate(assertNotNull(entry.searchParams))
            .use { it.next() }

        assertEquals(2L, sample.intValue(0))
    }

    @ParameterizedTest
    @ValueSource(strings = ["satisfy", "minimize x", "maximize x"])
    fun `compiled subprocess resolution should defer model parsing`(directive: String, @TempDir root: Path) {
        val source = root.resolve("source.fzn").toFile().apply {
            writeText("constraint unsupported_builtin(); solve $directive;")
        }
        val ref = ProblemRef("compiled", Format.MINIZINC, ProblemSource.Prepared(source),
            Category.CSP, Expected.Unknown)

        val entry = MiniZincRunner().resolveCompiled(ref, source)

        assertEquals(directive != "satisfy", entry.optimizes)
        assertEquals(directive.startsWith("maximize"), entry.maximize)
        assertFailsWith<FlatZincParseException> { entry.problem }
    }
}
