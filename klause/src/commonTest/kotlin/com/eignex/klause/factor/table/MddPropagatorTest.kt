package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sanity check that the cached-domain-ref incremental path doesn't break correctness. */
class MddPropagatorTest {

    @Test
    fun `repeated propagate from identical state preserves filtering`() {
        // 2-symbol MDD that accepts {1,2}* of length 2; states: layer0 = {0}, layer1 = {0},
        // layer2 = {0}. Initial 0, accepting {0}. Two transitions per layer.
        val factor = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 1, 1),
            layerStarts = intArrayOf(0, 6, 12),
            transitions = longArrayOf(
                0, 1, 0, // layer 0: 0 --1--> 0
                0, 2, 0, // layer 0: 0 --2--> 0
                0, 1, 0, // layer 1: 0 --1--> 0
                0, 2, 0, // layer 1: 0 --2--> 0
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val r1 = problem.propagate(Assumptions.None)
        assertTrue(r1 is PropagationResult.Implied, "first fire should reach fixpoint; got $r1")
        // Pinning seq[0] = 1 narrows domain; re-propagate — must still succeed and prune nothing further.
        val r2 = problem.propagate(Assumptions(ints = mapOf(0 to 1)))
        assertTrue(r2 is PropagationResult.Implied, "second fire with pin should still propagate; got $r2")
    }

    @Test
    fun `backtrack enumeration over the MDD equals brute force`() {
        // 2-var MDD accepting exactly (1,2) and (2,1). Unlike the single-shot oracle, enumerating
        // under the CDCL backtracker fires propagate repeatedly on ONE PropagationState and pushes /
        // pops decision levels — exercising the reversible forward/backward layer bitsets and the
        // delta-driven cascade of the incremental propagator across deep backtracking.
        fun mddFactor(): Factor = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 2, 1),
            layerStarts = intArrayOf(0, 6, 12),
            transitions = longArrayOf(
                0, 1, 0, 0, 2, 1, // layer 0: s0 --1--> s0, --2--> s1
                0, 2, 0, 1, 1, 0, // layer 1: s0 --2--> term, s1 --1--> term
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        )
        fun accepts(a: Int, b: Int): Boolean = (a == 1 && b == 2) || (a == 2 && b == 1)
        // Per-instance (seq0 range, seq1 range): free, then each variable pinned to each value.
        val instances = listOf(
            Pair(1, 2) to Pair(1, 2),
            Pair(1, 1) to Pair(1, 2),
            Pair(2, 2) to Pair(1, 2),
            Pair(1, 2) to Pair(2, 2),
            Pair(1, 1) to Pair(1, 1), // (1,1) rejected → UNSAT, exercises the no-accepting path
        )
        for ((idx, ranges) in instances.withIndex()) {
            val (r0, r1) = ranges
            val brute = HashSet<List<Int>>()
            for (a in r0.first..r0.second) {
                for (b in r1.first..r1.second) if (accepts(a, b)) brute.add(listOf(a, b))
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(
                    IntDomain(r0.first.toLong(), r0.second.toLong()),
                    IntDomain(r1.first.toLong(), r1.second.toLong()),
                ),
                factors = arrayOf(mddFactor()),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "mdd instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `cost MDD enumeration equals brute force across deep backtracking`() {
        // Cost MDD (stride 4): single state, accepts any length-2 word over {1,2}; each edge's weight
        // is its symbol, so the path cost is seq0 + seq1, and the cost variable equals that sum. At a
        // complete assignment the forward lattice is a single path (minCost == maxCost), so the
        // incremental cost-bound derivation forces cost to the exact path sum — the enumeration must
        // therefore equal the true { accepting ∧ cost == seq0 + seq1 } set, exercising tightenCost and
        // the reversible cascade under push/pop.
        fun costMdd(): Factor = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 1, 1),
            layerStarts = intArrayOf(0, 8, 16),
            transitions = longArrayOf(
                0, 1, 0, 1, 0, 2, 0, 2, // layer 0: --1--> (w1), --2--> (w2)
                0, 1, 0, 1, 0, 2, 0, 2, // layer 1: --1--> (w1), --2--> (w2)
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 2,
        )
        for (costRange in listOf(0 to 5, 3 to 3, 2 to 3, 4 to 5)) {
            val brute = HashSet<List<Int>>()
            for (a in 1..2) {
                for (b in 1..2) {
                    val c = a + b
                    if (c in costRange.first..costRange.second) brute.add(listOf(a, b, c))
                }
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(
                    IntDomain(1, 2),
                    IntDomain(1, 2),
                    IntDomain(costRange.first.toLong(), costRange.second.toLong()),
                ),
                factors = arrayOf(costMdd()),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "cost MDD (cost∈$costRange): solution set must equal brute force")
        }
    }

    @Test
    fun `MDD factors sharing one transition index reuse the root snapshot and enumerate as brute force`() {
        // Two factors over disjoint variable pairs bind ONE shared transition index — exactly the `<group>`
        // shape. The first factor's full-domain root rebuild caches the structural reachability snapshot;
        // the others reuse it instead of re-scanning the diagram. Enumeration under the CDCL backtracker
        // must still equal brute force, and the mix of full-domain (snapshot-reusing) and pinned-domain
        // (recomputing) instances exercises both paths across deep backtracking.
        val numStatesPerLayer = intArrayOf(1, 2, 1)
        val layerStarts = intArrayOf(0, 6, 12)
        val transitions = longArrayOf(
            0, 1, 0, 0, 2, 1, // layer 0: s0 --1--> s0, --2--> s1
            0, 2, 0, 1, 1, 0, // layer 1: s0 --2--> term, s1 --1--> term
        )
        val shared = MddTransitionIndex.build(transitions, layerStarts, numStatesPerLayer, 3)
        fun mdd(a: Int, b: Int): Mdd = Mdd(
            seq = intArrayOf(a, b),
            numStatesPerLayer = numStatesPerLayer,
            layerStarts = layerStarts,
            transitions = transitions,
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        ).also { it.transitionIndex = shared }
        fun accepts(x: Int, y: Int): Boolean = (x == 1 && y == 2) || (x == 2 && y == 1)

        // (domain of the 4 vars): all free, then one pair pinned so its factor recomputes while the other
        // reuses the shared snapshot.
        val instances = listOf(
            intArrayOf(2, 2, 2, 2),
            intArrayOf(1, 2, 2, 2), // var0 pinned to 1 → first factor non-covering, second still reuses
        )
        for ((idx, hi) in instances.withIndex()) {
            val brute = HashSet<List<Int>>()
            for (a in 1..hi[0]) {
                for (b in 1..hi[1]) {
                    for (c in 1..hi[2]) {
                        for (d in 1..hi[3]) {
                            if (accepts(a, b) && accepts(c, d)) brute.add(listOf(a, b, c, d))
                        }
                    }
                }
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = Array(4) { IntDomain(1, hi[it].toLong()) },
                factors = arrayOf<Factor>(mdd(0, 1), mdd(2, 3)),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "shared-index MDD #$idx: solution set must equal brute force")
        }
    }
}
