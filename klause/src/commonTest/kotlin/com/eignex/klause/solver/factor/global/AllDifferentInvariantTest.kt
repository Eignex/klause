package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move.Compound
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AllDifferentInvariantTest {

    @Test
    fun `four vars permutation over four values`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 7)).take(20).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 4, "duplicates in ${s.ints.toList()}")
        }
    }

    @Test
    fun `structured moves include feasibility-preserving 3-cycles`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        fun seeded(seed: Long): LocalSearchState {
            val state = LocalSearchState(problem, Random(seed))
            for (i in 0 until 5) state.assignment.setInt(i, i)
            state.recompute()
            return state
        }
        var sawRotation = false
        for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L, 29L)) {
            val state = seeded(seed)
            assertTrue(state.cost == 0L, "the identity permutation must be feasible")
            val sink = MoveSink()
            state.factors[0].proposeStructuredMoves(state, 0, sink)
            for (m in sink.list) {
                if (m is Compound && m.parts.size == 3) sawRotation = true
                val check = seeded(0)
                check.apply(m)
                assertTrue(check.cost == 0L, "structured move $m broke all-different")
            }
        }
        assertTrue(sawRotation, "3-cycle rotations must be emitted alongside 2-swaps")
    }

    @Test
    fun `three vars room for one duplicate requires unique values`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 5_000, randomSeed = 13)).take(15).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue(s.ints.toSet().size == 3, "duplicates in ${s.ints.toList()}")
        }
    }

    @Test
    fun `repair proposes multiple unused targets when domain has many spare values`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 5)
        state.assignment.setInt(2, 0)
        for (i in 0 until problem.numFactors) state.factors[i].initialize(state, i)
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, factorId = 0, sink = sink)
        // MAX_REPAIR_TARGETS caps proposals at 4.
        val intSets = sink.list.filterIsInstance<IntSet>()
        assertTrue(intSets.size in 2..4, "expected 2-4 candidates (cap 4), got ${intSets.size}: $intSets")
        val occupantSet = intSets.map { it.varId }.toSet()
        assertTrue(
            occupantSet.size == 1 && (occupantSet.contains(0) || occupantSet.contains(1)),
            "candidates should pin one occupant, got $occupantSet",
        )
        val targetSet = intSets.map { it.newValue }.toSet()
        assertTrue(targetSet.size == intSets.size, "duplicate targets: $intSets")
        for (t in targetSet) {
            assertTrue(t != 5 && t != 0, "target $t collides with existing assignment")
        }
    }

    @Test
    fun `repair emits value-swap candidates when domain is fully saturated`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 0)
        state.recompute()
        val sink = MoveSink()
        state.factors[0].proposeRepairMoves(state, factorId = 0, sink = sink)
        val compounds = sink.list.filterIsInstance<Compound>()
        assertTrue(compounds.isNotEmpty(), "expected swap candidates with saturated domain; got ${sink.list}")
        for (c in compounds) {
            assertTrue(c.parts.size == 2, "swap should be 2-part, got ${c.parts.size}")
            val a = c.parts[0] as IntSet
            val b = c.parts[1] as IntSet
            assertTrue(a.varId != b.varId, "swap should target distinct vars")
            assertTrue(
                a.newValue == state.assignment.intValue(b.varId),
                "swap part 0 takes part 1's old value",
            )
            assertTrue(
                b.newValue == state.assignment.intValue(a.varId),
                "swap part 1 takes part 0's old value",
            )
        }
    }

    @Test
    fun `mismatched domain bounds fail at initialize`() {
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )

        assertFails {
            LocalSearchSolver(
                problem,
                restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 50),
            ).enumerate(LocalSearchParams(maxFlips = 100, randomSeed = 1)).take(1).toList()
        }
    }
}
