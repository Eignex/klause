package com.eignex.klause.localsearch

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObjectiveProposalGeometryTest {
    @Test
    fun `hotspots and step sizes vary independently of the canonical grader`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val functional = assertNotNull(sweep.functionalObjective(canonical))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = sweep.network(2, 0)
        state.assignment.setInt(0, 4)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        state.shaping.objective = canonical
        val sink = MoveSink().apply { setInvariants(state.invariants) }

        for (leaves in listOf(false, true)) for (geometric in listOf(false, true)) {
            assertTrue(configureObjectiveProposals(state, functional, leaves, geometric))
            sink.clear()
            ObjectiveSeed().generate(state, sink)

            assertSame(canonical, state.shaping.objective)
            assertContentEquals(intArrayOf(if (leaves) 0 else 1), state.shaping.objectiveIntVars)
            assertEquals(geometric, Move.IntSet(0, 8) in sink.list)
            assertTrue(sink.list.all { objectiveProposalValuesAgree(state, functional, it) })
        }
    }

    @Test
    fun `proposal geometry declines an unmaintained cone`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.shaping.objective = canonical

        val admitted = configureObjectiveProposals(state, assertNotNull(sweep.functionalObjective(canonical)), true, true)

        assertFalse(admitted)
    }
}
