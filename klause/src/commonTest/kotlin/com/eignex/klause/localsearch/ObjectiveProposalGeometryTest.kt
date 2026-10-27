package com.eignex.klause.localsearch

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ObjectiveProposalGeometryTest {
    @Test
    fun `geometric search retains its trajectory across instruction slices`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val params = LocalSearchParams(maxFlips = 4, randomSeed = 0,
            initialAssignment = Sample(booleanArrayOf(), longArrayOf(4, 4)),
            lsObjective = sweep.functionalObjective(canonical), objectiveGuidance = ObjectiveGuidance.Geometric)
        val solver = LocalSearchSolver(problem.bake(), strategy = Cbls(), definitionalSweep = sweep,
            perMoveInvariants = true, greedyRepairOnRestart = false)
        val whole = solver.minimize(canonical, params)
        val incumbents = mutableListOf<Sample>()

        solver.resumable(canonical, params).use { run ->
            val sliced = run as InstructionSlicedSearch
            repeat(5) {
                if (!run.isDone) sliced.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 1) {
                    incumbents += it.sample
                }
            }

            assertTrue(run.isDone)
            assertEquals(whole.assignment, incumbents.last())
            assertEquals(whole.stats.ls.geometricProposalCandidates, run.stats.ls.geometricProposalCandidates)
            assertEquals(1.0, run.stats.ls.geometricProposalRuns.sum)
        }
    }

    @Test
    fun `retained geometric generation reaches later leaves in bounded batches`() {
        val problem = Problem(0, 17, Array(17) { IntDomain(0, 16) }, emptyArray())
        val inputs = Array<DefinitionHint.Operand>(16) { DefinitionHint.Operand.Variable(it) }
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(16, LongArray(17) { if (it == 16) -1 else 1 },
                inputs + DefinitionHint.Operand.Variable(16), 0),
        )))
        val canonical = LinearObjective(intCoefficients = LongArray(17) { if (it == 16) 1 else 0 })
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = sweep.network(17, 0)
        state.shaping.objective = canonical
        prepareObjectiveProposals(state, sweep.functionalObjective(canonical)).forEach { }
        val sink = MoveSink().apply { setInvariants(state.invariants) }

        ObjectiveSeed().generate(state, sink)
        val first = sink.list.filterIsInstance<Move.IntSet>().map { it.varId }.toSet()
        sink.clear()
        ObjectiveSeed().generate(state, sink)
        val second = sink.list.filterIsInstance<Move.IntSet>().map { it.varId }.toSet()

        assertEquals((0..7).toSet(), first)
        assertEquals((8..15).toSet(), second)
        assertSame(canonical, state.shaping.objective)
    }

    @Test
    fun `reconfiguration discards geometric proposals for a changed objective or network`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val functional = assertNotNull(sweep.functionalObjective(canonical))
        for (replaceObjective in listOf(false, true)) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.invariants = sweep.network(2, 0)
            state.shaping.objective = canonical
            prepareObjectiveProposals(state, functional).forEach { }
            assertTrue(state.shaping.boundedProposalLeaves)

            if (replaceObjective) state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(1, 0))
            else state.invariants = null
            prepareObjectiveProposals(state, functional).forEach { }

            assertNull(state.shaping.proposalSeeds)
            assertFalse(state.shaping.boundedProposalLeaves)
        }
    }

    @Test
    fun `geometric leaf proposals preserve pins ownership and domains`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(2, longArrayOf(1, 1, -1), Array(3) { DefinitionHint.Operand.Variable(it) }, 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        for (pinned in listOf(false, true)) {
            val assumptions = if (pinned) Assumptions(ints = mapOf(0 to 4L)) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), assumptions)
            state.invariants = sweep.network(3, 0)
            state.shaping.objective = canonical
            prepareObjectiveProposals(state, sweep.functionalObjective(canonical)).forEach { }
            val sink = MoveSink(assumptions).apply {
                setInvariants(state.invariants)
                if (!pinned) setOwners(intArrayOf(0, -1, -1))
            }

            ObjectiveSeed().generate(state, sink)

            assertTrue(sink.list.isNotEmpty())
            assertTrue(sink.list.all { it is Move.IntSet && it.varId == 1 && it.newValue in state.rootDomains[1] })
        }
    }

    @Test
    fun `fresh searches qualify geometric proposals without retaining a prior objective`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 8) }, emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val solver = LocalSearchSolver(problem.bake(), definitionalSweep = sweep,
            perMoveInvariants = true, greedyRepairOnRestart = false)
        val params = LocalSearchParams(maxFlips = 2, randomSeed = 0,
            initialAssignment = Sample(booleanArrayOf(), longArrayOf(4, 4)),
            lsObjective = sweep.functionalObjective(canonical), objectiveGuidance = ObjectiveGuidance.Geometric)

        val first = solver.minimize(canonical, params)
        val replaced = solver.minimize(LinearObjective(intCoefficients = longArrayOf(1, 0)), params)

        assertEquals(1.0, first.stats.ls.geometricProposalRuns.sum)
        assertTrue(first.stats.ls.geometricProposalCandidates.sum > 0)
        assertEquals(1.0, replaced.stats.ls.geometricProposalDeclines.sum)
        assertEquals(0.0, replaced.stats.ls.geometricProposalCandidates.sum)
    }

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

        val admitted = configureObjectiveProposals(state,
            assertNotNull(sweep.functionalObjective(canonical)), true, true)

        assertFalse(admitted)
    }

    @Test
    fun `complete candidate values detect domain clamping despite maintained cones`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 8), IntDomain(0, 4)), emptyArray())
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem, listOf(
            DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
        )))
        val canonical = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = sweep.network(2, 0)
        state.shaping.objective = canonical
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        val agrees = objectiveProposalValuesAgree(state, assertNotNull(sweep.functionalObjective(canonical)),
            Move.IntSet(0, 8))

        assertFalse(agrees)
    }
}
