package com.eignex.klause.schema

import com.eignex.klause.ast.SearchAnnotation
import com.eignex.klause.ast.ValSearchStrategy
import com.eignex.klause.ast.VarSearchStrategy
import com.eignex.klause.ast.allDifferent
import com.eignex.klause.compile.compile
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.backtrack.SmallestDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SearchAnnotationTest {

    private class Annotated : VariableSchema() {
        val q0 by intVar(0, 3)
        val q1 by intVar(0, 3)
        val q2 by intVar(0, 3)
        val q3 by intVar(0, 3)
        val rows by constraint { allDifferent(q0, q1, q2, q3) }
        init {
            search(
                variableStrategy = VarSearchStrategy.SmallestDomain,
                valueStrategy = ValSearchStrategy.Min,
                phaseSaving = true,
                lubyRestartBase = 50L,
                maxDecisions = 1_000L,
            )
        }
    }

    private class Unannotated : VariableSchema() {
        val q0 by intVar(0, 3)
        val q1 by intVar(0, 3)
    }

    @Test
    fun `schema with no search annotation leaves defaultBacktrackParams null`() {
        val compiled = Unannotated().compile()
        assertNull(compiled.defaultBacktrackParams)
        // backtrackParams() falls back to a fresh default.
        val p = compiled.backtrackParams()
        assertEquals(Long.MAX_VALUE, p.maxDecisions)
    }

    @Test
    fun `search annotation maps to BacktrackParams`() {
        val compiled = Annotated().compile()
        val p = compiled.defaultBacktrackParams
        assertNotNull(p, "defaultBacktrackParams should be populated when schema declares search")
        assertSame(SmallestDomain, p.variableHeuristic)
        assertSame(IndomainMin, p.valueHeuristic)
        assertEquals(true, p.phaseSaving)
        assertEquals(50L, p.lubyRestartBase)
        assertEquals(1_000L, p.maxDecisions)
    }

    @Test
    fun `entries pass through serializable SearchAnnotation`() {
        val schema = Annotated()
        val anns = schema.entries.values.filterIsInstance<SearchAnnotation>()
        assertEquals(1, anns.size)
        val ann = anns.single()
        assertEquals(VarSearchStrategy.SmallestDomain, ann.variableStrategy)
        assertEquals(ValSearchStrategy.Min, ann.valueStrategy)
    }

    @Test
    fun `repeated search calls keep the most recent`() {
        class MultiSearch : VariableSchema() {
            val x by intVar(0, 5)
            init {
                search(variableStrategy = VarSearchStrategy.InputOrder)
                search(variableStrategy = VarSearchStrategy.SmallestDomain,
                       valueStrategy = ValSearchStrategy.Max)
            }
        }
        val compiled = MultiSearch().compile()
        val p = compiled.defaultBacktrackParams
        assertNotNull(p)
        assertSame(SmallestDomain, p.variableHeuristic)
    }

    @Test
    fun `annotated schema solves end-to-end`() {
        val schema = Annotated()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val result = solver.solve(compiled.backtrackParams())
        assertTrue(
            result is com.eignex.klause.solver.SolveResult.Sat,
            "expected SAT under the schema-declared strategy, got $result",
        )
        // Verify the result is feasible — the constraint network still owns correctness.
        val sample = result.assignment
        val values = listOf(schema.q0, schema.q1, schema.q2, schema.q3)
            .map { compiled.decode(it, sample) }
        assertEquals(values.size, values.toSet().size, "expected an all-different assignment")
    }
}
