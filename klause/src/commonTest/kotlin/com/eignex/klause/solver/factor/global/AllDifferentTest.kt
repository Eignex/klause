package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move.Compound
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.FactorPropagationOracle
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AllDifferentTest {

    /**
     * Soundness gate for the Hall-set conflict/prune explanations. The complete CDCL
     * backtracker (VSIDS + LBD clause forgetting, so the Hall-set antecedents are exercised
     * by clause learning) must enumerate EXACTLY the brute-force solution set on a battery
     * of small, Hall-prone AllDifferent instances. An unsound explanation would prune a
     * feasible subtree → a missing solution → a smaller set; an over-broad one only slows
     * search. Range domains (per the [AllDifferent] common-domain contract) suffice to force
     * tight Hall structure (e.g. two vars confined to {0,1} force the rest off those values).
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // Each instance: per-var inclusive [min,max] ranges over a shared value space.
        val instances = listOf(
            listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3), // permutations of 0..3
            listOf(0 to 1, 0 to 1, 0 to 3, 2 to 3), // Hall {v0,v1}⊆{0,1}
            listOf(1 to 3, 2 to 3, 3 to 3, 0 to 3), // cascading singleton
            listOf(0 to 2, 1 to 2, 0 to 1, 0 to 2), // overlapping tight set
            listOf(0 to 2, 1 to 3, 2 to 4, 0 to 4, 0 to 4), // 5 vars, mixed widths
            // 6 vars over a wide shared range: large Régin value graph (total = 12) that shrinks as
            // decisions pin vars, firing reginFilter many times on one reused ReginCache — stresses
            // the reused adjacency buffers' grow + per-fire clear across push/pop.
            listOf(0 to 5, 0 to 5, 0 to 5, 0 to 5, 0 to 5, 0 to 5),
        )
        for ((idx, ranges) in instances.withIndex()) {
            val k = ranges.size
            val lo = ranges.minOf { it.first }
            val hi = ranges.maxOf { it.second }
            // Brute-force reference: every per-var value combination that is all-different.
            val brute = HashSet<List<Int>>()
            fun rec(i: Int, acc: IntArray) {
                if (i == k) {
                    if (acc.toHashSet().size == k) brute.add(acc.toList())
                    return
                }
                for (v in ranges[i].first..ranges[i].second) {
                    acc[i] = v
                    rec(i + 1, acc)
                }
            }
            rec(0, IntArray(k))

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = k,
                intDomains = Array(k) { IntDomain(ranges[it].first, ranges[it].second) },
                factors = arrayOf<Factor>(AllDifferent(IntArray(k) { it }, domainMin = lo, domainSize = hi - lo + 1)),
            )
            // CDCL config so conflict analysis + clause learning (hence the Hall-set
            // explanations) actually run; no restarts to keep enumeration completeness simple.
            val params = BacktrackParams(
                randomSeed = 1,
                variableSelector = Vsids(),
                maxLearnedClauses = 1_000,
            )
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

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
        // 4 vars over [0..2] saturates the domain so there is no unused target; the
        // swap pass kicks in. (Pigeonhole-infeasible, but we're testing move generation.)
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
    fun `hall interval prunes other vars' bounds via propagation`() {
        // Three vars on [1, 3] form a Hall set; v3's [2, 5] intrudes on the min side
        // and should be bumped to 4.
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(1, 3),
                IntDomain(2, 5),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val v3Domain = session.intDomain(3)
        assertEquals(
            4,
            v3Domain.min,
            "v3's min should be tightened to 4 (Hall set [1,3] forbids 2,3 for v3); got $v3Domain",
        )
        assertEquals(
            5,
            v3Domain.max,
            "v3's max should remain 5; got $v3Domain",
        )
    }

    @Test
    fun `hall interval detects infeasibility - pigeonhole over interval`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val baked = problem.baked
        assertTrue(
            baked is Unsat,
            "expected bake-time Unsat from Hall pigeonhole; got $baked",
        )
    }

    @Test
    fun `hall interval prunes overlapping bounds on both sides`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 8)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(3, 4),
                IntDomain(3, 4),
                IntDomain(4, 7),
                IntDomain(1, 3),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val v2 = session.intDomain(2)
        val v3 = session.intDomain(3)
        assertEquals(5, v2.min, "v2's min should be pushed past Hall set; got $v2")
        assertEquals(7, v2.max)
        assertEquals(1, v3.min)
        assertEquals(2, v3.max, "v3's max should be pulled below Hall set; got $v3")
    }

    @Test
    fun `singleton-taken value punched out of interior of other domains`() {
        // Sparse-domain pruning removes the taken value even when it lands in the
        // interior of another var's domain (here value 3 in v1's [1, 5]).
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 6)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val d1 = session.intDomain(1)
        assertEquals(1, d1.min, "v1's min should remain 1 (3 is interior)")
        assertEquals(5, d1.max, "v1's max should remain 5 (3 is interior)")
        assertEquals(4, d1.size, "v1 should have 4 values after punching out 3; got $d1")
        assertTrue(3 !in d1, "v1 should no longer contain 3")
        assertTrue(2 in d1 && 4 in d1, "v1 should still contain 2 and 4")
    }

    @Test
    fun `hall interval with spanning intruder punches every interior value`() {
        // Hall set [3, 5]; intruder v3 has [1, 7] so sparse-domain pruning must
        // punch interior values out (min/max can't move past the holes).
        val factor = AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 8)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(3, 5),
                IntDomain(3, 5),
                IntDomain(3, 5),
                IntDomain(1, 7),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val session = PropagationSession(problem)
        val d3 = session.intDomain(3)
        assertEquals(1, d3.min)
        assertEquals(7, d3.max)
        for (h in 3..5) assertTrue(h !in d3, "value $h should be a hole, got $d3")
        for (k in intArrayOf(1, 2, 6, 7)) {
            assertTrue(k in d3, "value $k should remain; got $d3")
        }
    }

    @Test
    fun `slack staircase domains are GAC — no over-prune from free-value reachability`() {
        // Regression for the false-UNSAT bug: a *slack* all_different (more values than
        // vars, e.g. the q[i]±i diagonal channeling in n-queens) where the responsible
        // alternating paths start at free VALUE nodes. The residual graph orients matched
        // edges value→var and unmatched edges var→value, so a free value is a sink there;
        // the "reachable from a free vertex" pass must walk the REVERSE graph or it reaches
        // nothing and prunes every slack edge unsoundly. Staircase [(1+i)..(n+i)] is fully
        // slack — GAC must prune NOTHING — yet the buggy pass pinned var0 to its min and
        // punched holes across the rest, which cascaded (via channeling) to a root conflict
        // on satisfiable models. assertGac brute-checks both directions (no over-prune AND
        // GAC-complete). n<=6 keeps the brute enumeration under the oracle's cap.
        for (n in 3..6) {
            val ranges = (1..n).map { i -> (1 + i) to (n + i) }
            val lo = ranges.minOf { it.first }
            val hi = ranges.maxOf { it.second }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(ranges[it].first, ranges[it].second) },
                factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = lo, domainSize = hi - lo + 1)),
            )
            FactorPropagationOracle.assertGac(problem, "slack-staircase-n$n")
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
