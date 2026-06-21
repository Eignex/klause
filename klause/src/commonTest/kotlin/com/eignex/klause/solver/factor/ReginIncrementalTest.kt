package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.global.internals.ReginCache
import com.eignex.klause.solver.factor.global.internals.reginFilter
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sanity checks for the [reginFilter] unchanged-domains fast path (the [ReginCache] fixpoint
 * record): a re-fire from an identical state must be a no-op, and enumerating under the
 * backtracker — which fires propagate repeatedly on one [PropagationState] with push/pop —
 * must still match brute force, proving the fast path never falsely skips after a restore.
 */
class ReginIncrementalTest {

    @Test
    fun `re-fire from the GAC fixpoint prunes nothing further`() {
        // x0, x1 in {1, 3}; x2 in {1, 2, 3}. Régin prunes 1 and 3 from x2 → {2}. A second fire on
        // the unchanged domains must hit the fast path and return without touching anything.
        val sparse = IntDomain(1, 3).excludeValue(2)
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(sparse, sparse, IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(problem.propagators[0].propagate(state, factorId = 0))
        val afterFirst = state.intDomains[2]
        assertEquals(2, afterFirst.min)
        assertEquals(2, afterFirst.max)
        // Re-fire: identical domains → fast-path hit → same refs, no further change.
        assertTrue(problem.propagators[0].propagate(state, factorId = 0))
        assertTrue(afterFirst === state.intDomains[2], "fast-path re-fire must not rewrite the domain")
    }

    @Test
    fun `backtrack enumeration over alldifferent equals brute force`() {
        // Enumerating under the CDCL backtracker fires propagate repeatedly on ONE state and
        // pushes/pops decision levels — so the fixpoint record is set at deep levels and must
        // *miss* (not falsely skip) once a pop restores wider domains. A false skip would drop
        // solutions; a stale hit would keep over-pruned domains → both shrink the found set.
        fun alldiff(): Factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 1, domainSize = 4)
        val instances = listOf(
            listOf(4, 4, 4, 4), // free: 4! = 24 permutations
            listOf(2, 4, 4, 4), // x0 in {1,2}
            listOf(1, 2, 3, 4), // staircase domains
            listOf(2, 2, 4, 4), // two vars share {1,2} (a Hall pair)
        )
        for ((idx, sizes) in instances.withIndex()) {
            val brute = HashSet<List<Int>>()
            fun rec(pos: Int, acc: MutableList<Int>) {
                if (pos == 4) {
                    if (acc.toSet().size == 4) brute.add(acc.toList())
                    return
                }
                for (v in 1..sizes[pos]) {
                    acc.add(v)
                    rec(pos + 1, acc)
                    acc.removeAt(acc.size - 1)
                }
            }
            rec(0, mutableListOf())
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = Array(4) { IntDomain(1, sizes[it]) },
                factors = arrayOf(alldiff()),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "alldifferent instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `large overlapping alldifferent enumerates exactly the brute set across backtracking`() {
        // n = 6 over a shared 1..6 universe with assorted domains: one big residual SCC that splits
        // into sub-components as decisions narrow domains and re-merges on backtrack — exercising the
        // partial sub-Tarjan (dirty-component recompute) and the matched-edge-break rebuild path,
        // both reversibly, many thousands of times under the CDCL backtracker.
        val sizes = listOf(6, 6, 5, 4, 3, 2) // x_k in 1..sizes[k]
        val brute = HashSet<List<Int>>()
        fun rec(pos: Int, acc: MutableList<Int>) {
            if (pos == 6) {
                if (acc.toSet().size == 6) brute.add(acc.toList())
                return
            }
            for (v in 1..sizes[pos]) {
                acc.add(v)
                rec(pos + 1, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        rec(0, mutableListOf())
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(1, sizes[it]) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4, 5), domainMin = 1, domainSize = 6)),
        )
        val params = BacktrackParams(randomSeed = 7L, variableSelector = Vsids(), maxLearnedClauses = 2_000)
        val found = BacktrackSolver(problem).enumerate(params).take(100_000)
            .map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "large alldifferent: backtrack solution set must equal brute force")
    }

    @Test
    fun `first fire still reaches a fixpoint without a prior record`() {
        // Guards the lastVars == null branch: no fixpoint on record yet → full filter runs.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 3)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)),
        )
        val r = problem.propagate(Assumptions.None)
        assertTrue(r is PropagationResult.Implied, "first fire should reach fixpoint; got $r")
    }
}
