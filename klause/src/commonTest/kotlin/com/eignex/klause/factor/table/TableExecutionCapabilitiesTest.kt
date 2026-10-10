package com.eignex.klause.factor.table

import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableExecutionCapabilitiesTest {
    @Test
    fun `ground tables declare filtering complete checks graded scoring and a sound hull`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2))

        val capabilities = table.executionCapabilities()

        assertEquals(PropagationCapability.DOMAIN_FILTERING, capabilities.propagation)
        assertEquals(AssignmentCheckCapability.EXACT, capabilities.propagationCheck)
        assertEquals(AssignmentCheckCapability.EXACT, capabilities.assignmentCheck)
        assertEquals(ScoringCapability.GRADED, capabilities.scoring)
        assertEquals(RelaxationCapability.SOUND, capabilities.relaxation)
    }

    @Test
    fun `table filtering preserves every independently allowed assignment`() {
        val variants = listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2)) to
                { s: Sample -> s.ints[0] == s.ints[1] && s.ints[0] != 1L },
            Table(intArrayOf(0, 1), longArrayOf(0, 1), longArrayOf(1, Long.MAX_VALUE)) to
                { s: Sample -> s.ints[0] <= 1L && s.ints[1] >= 1L },
            Table(intArrayOf(0, 0), longArrayOf(0, 1, 2, 2)) to
                { s: Sample -> s.ints[0] == 2L },
        )

        for ((table, satisfies) in variants) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
            FactorPropagationOracle.assertSound(problem, satisfies = satisfies)
        }
    }

    @Test
    fun `complete table checks agree with independent source semantics`() {
        val variants = listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2)) to
                { x: Long, y: Long -> x == y && x != 1L },
            Table(intArrayOf(0, 1), longArrayOf(0, 1), longArrayOf(1, Long.MAX_VALUE)) to
                { x: Long, y: Long -> x <= 1L && y >= 1L },
            Table(intArrayOf(0, 0), longArrayOf(0, 1, 2, 2)) to
                { x: Long, _: Long -> x == 2L },
        )
        for ((table, satisfies) in variants) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
            val session = PropagationSession(problem)
            for (x in 0L..2L) for (y in 0L..2L) {
                session.popToLevel(0)
                val accepted = session.pinInt(0, x) !is PropagationResult.Unsat &&
                    session.pinInt(1, y) !is PropagationResult.Unsat
                assertEquals(satisfies(x, y), accepted)
            }
        }
    }

    @Test
    fun `table invariant satisfaction agrees with independent source semantics`() {
        val variants = listOf(
            Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2)) to
                { x: Long, y: Long -> x == y && x != 1L },
            Table(intArrayOf(0, 1), longArrayOf(0, 1), longArrayOf(1, Long.MAX_VALUE)) to
                { x: Long, y: Long -> x <= 1L && y >= 1L },
            Table(intArrayOf(0, 0), longArrayOf(0, 1, 2, 2)) to
                { x: Long, _: Long -> x == 2L },
        )
        for ((table, satisfies) in variants) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
            val state = LocalSearchState(problem.bake(), Random(0))
            for (x in 0L..2L) for (y in 0L..2L) {
                state.assignment.setInt(0, x)
                state.assignment.setInt(1, y)
                state.recompute()

                assertEquals(satisfies(x, y), !state.factors[0].isViolated(state, 0))
                assertEquals(satisfies(x, y), state.factorDegree[0] == 0)
            }
        }
    }

    @Test
    fun `table move scoring agrees with the committed assignment`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2))
        val state = LocalSearchState(
            Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table)).bake(), Random(0),
        )
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()
        val before = state.factorDegree[0]

        val delta = state.factors[0].deltaIfIntSet(state, 0, 0, 2)
        state.apply(Move.IntSet(0, 2))

        assertEquals(before + delta, state.factorDegree[0])
        assertTrue(state.factors[0].isViolated(state, 0))
        state.apply(Move.IntSet(1, 2))
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `ground table hull retains every independently allowed assignment`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0, 2, 2))
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val relaxation = CpToLpRelaxation(problem, objective, tableHull = true).build(PropagationSession(problem))
        val model = relaxation.model
        assertTrue(0 in relaxation.hullFactorIds)

        for (value in listOf(0L, 2L)) {
            val point = LongArray(model.n) { col ->
                if (relaxation.colVarId[col] >= 0) {
                    value - model.loShift[col]
                } else {
                    val requirements = relaxation.colReq[col]!!
                    if ((requirements.indices step 2).all { requirements[it + 1] == value }) 1L else 0L
                }
            }
            val sums = LongArray(model.m)
            for (col in point.indices) {
                assertTrue(point[col] >= 0)
                if (model.hasUpper[col]) assertTrue(point[col] <= model.upper[col])
                model.forEachInColumn(col) { row, coefficient -> sums[row] += coefficient * point[col] }
            }
            for (row in sums.indices) {
                val slack = model.rhs[row] - sums[row]
                assertTrue(slack >= 0)
                if (model.hasUpper[model.n + row]) assertTrue(slack <= model.upper[model.n + row])
            }
        }
    }

    @Test
    fun `short table intervals deliberately decline point tuple hulls`() {
        val table = Table(intArrayOf(0, 1), longArrayOf(0, 0), longArrayOf(2, 2))
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(table))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val session = PropagationSession(problem)

        val enabled = CpToLpRelaxation(problem, objective, tableHull = true).build(session)
        val disabled = CpToLpRelaxation(problem, objective, tableHull = false).build(session)

        assertEquals(RelaxationCapability.NONE, table.executionCapabilities().relaxation)
        assertEquals(disabled.model.m, enabled.model.m)
        assertEquals(disabled.model.n, enabled.model.n)
    }
}
