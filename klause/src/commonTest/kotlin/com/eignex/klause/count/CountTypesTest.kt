package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CountTypesTest {
    @Test
    fun `counting methods should retain the source interpretation`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake())

        val counts = listOf(
            solver.exactCount().last(), solver.approximateCount(), solver.count(CountConfig(exactBudget = 1)),
        )

        for (count in counts) {
            assertSame(semantics, count.semantics)
            assertEquals(2L, count.estimate)
            assertTrue(count.exact)
        }
    }

    @Test
    fun `exact counts should describe the replaced caller range`() {
        for (upper in listOf(2L, 3L)) {
            val source = parseFlatZinc("var int: x; solve satisfy;", semanticOverrides = SemanticOverrides(
                undeclaredIntegers = BoundsOverride(BoundOverride.Value(1L), BoundOverride.Value(upper)),
            ))

            val count = BacktrackSolver(source.problem.bake()).exactCount().last()

            assertSame(source.semantics, count.semantics)
            assertTrue(source.semantics.hasOverrides)
            assertEquals(upper, count.estimate)
            assertTrue(count.exact)
        }
    }

    @Test
    fun `conditioned zero counts should retain policy after closing their session`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val model = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            semantics = semantics).bake()
        val solver = BacktrackSolver(model)

        val counts = solver.session().use { session ->
            session.push(Assumptions(bools = mapOf(0 to false)))
            listOf(session.exactCount().last(), session.approximateCount(), session.count())
        }

        for (count in counts) {
            assertSame(semantics, count.semantics)
            assertEquals(0L, count.estimate)
            assertTrue(count.exact)
        }
    }

    @Test
    fun `interrupted count bounds should retain policy after closing their cursor`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake())

        val count = solver.openExactCount(ExactCountConfig(maxChecks = 1)).use { it.first() }

        assertSame(semantics, count.semantics)
        assertEquals(0L, count.lower)
        assertEquals(2L, count.upper)
        assertFalse(count.exact)
    }
}
