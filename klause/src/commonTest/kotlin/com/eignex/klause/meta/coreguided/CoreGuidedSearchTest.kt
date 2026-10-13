package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.formats.opb.toProblem
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreGuidedSearchTest {
    @Test
    fun `weighted overlapping cores preserve the original objective`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-6, -1, -1), constant = 8)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(3, optimal.sample.numBoolVars)
    }

    @Test
    fun `threshold cores charge multiple unavoidable violations`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-1, -1, -1), constant = 3)

        val result = CoreGuidedSolver(problem).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `source assumptions remain hard across weight splitting`() {
        val problem = Problem(2, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(-5, -3), constant = 8)
        val params = BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to false)))

        val result = CoreGuidedSolver(problem).minimize(objective, params)

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertFalse(optimal.sample.boolValue(0))
        assertTrue(optimal.sample.boolValue(1))
        assertEquals(5L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `weighted WCNF preserves hard clauses and fixed cost`() {
        val source = Dimacs.parseWcnf("p wcnf 2 4 20\n20 -1 -2 0\n5 1 0\n3 2 0\n2 0\n").toProblem()
        val objective = source.objective.toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(5L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `normalized OPB preserves complemented objective constants`() {
        val source = Opb.parse("min: +5 ~x1 +3 ~x2 ;\n+1 x1 +1 x2 <= 1 ;\n").toProblem()
        val objective = requireNotNull(source.objective).toLinearObjective()

        val result = CoreGuidedSolver(source.problem.bake()).minimize(objective, BacktrackParams())

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertFalse(optimal.sample.boolValue(1))
        assertEquals(3L, objective.evaluateLong(optimal.sample))
    }

    @Test
    fun `wide objective equality retains exact weights`() {
        val problem = Problem(
            2, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(1, true))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals("18446744073709551614", objective.evaluateExact(optimal.sample).num.toString())
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `cancelled slice publishes no incomplete proof and can resume`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(longArrayOf(Long.MIN_VALUE), constant = 4)
        val search = CoreGuidedSearch(problem, objective, BacktrackParams())

        val paused = search.runSlice(Cancellation { true }, Long.MAX_VALUE, 1) { error("cancelled publication") }
        val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

        assertNull(paused)
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertTrue(optimal.sample.boolValue(0))
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `cancellation after a core bound retains the checked incumbent and residual weights`() {
        val problem = Problem(
            3, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
        )
        ).bake()
        val objective = LinearObjective(longArrayOf(-6, -1, -1), constant = 8)
        var cancelled = false
        val incumbents = mutableListOf<Long>()
        val search = CoreGuidedSearch(
            problem, objective,
            BacktrackParams(
            objectiveLowerBoundSink = { if (it == 1.0) cancelled = true },
        )
        )

        val paused = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {
            incumbents += objective.evaluateLong(it.sample)
        }
        val retainedBound = search.lowerBound
        cancelled = false
        val result = search.runSlice(Cancellation { cancelled }, Long.MAX_VALUE, -1) {
            incumbents += objective.evaluateLong(it.sample)
        }

        assertNull(paused)
        assertEquals(BigFraction.ONE, retainedBound)
        assertEquals(listOf(2L), incumbents)
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(2L, objective.evaluateLong(optimal.sample))
        assertEquals(objective.evaluateExact(optimal.sample), search.lowerBound)
    }

    @Test
    fun `hard infeasibility cannot be relaxed as an objective core`() {
        val problem = Problem(
            1, 0, emptyArray(),
            arrayOf(
            Clause(intArrayOf(Lit.make(0, true))),
            Clause(intArrayOf(Lit.make(0, false))),
        )
        ).bake()

        val result = CoreGuidedSolver(problem).minimize(LinearObjective(longArrayOf(5)), BacktrackParams())

        assertIs<MinimizeResult.Infeasible>(result)
    }

    @Test
    fun `closed pending search rejects another slice`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val search = CoreGuidedSearch(problem, LinearObjective(longArrayOf(1)), BacktrackParams())

        search.close()

        assertFailsWith<IllegalStateException> { search.runSlice(Cancellation.Never, 1, 1) {} }
    }
}
