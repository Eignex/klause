package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.global.internals.computeBoundsAllDifferent
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.values
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllDifferentPropagatorTest {

    // ── Bounds-consistent event subscription ─────────────────────────────────

    /** When [src] is fixed, carve its value out of [dst] — used to punch interior holes into the
     *  bounds-AllDifferent's variables mid-search. Plain occurrence-list wakeup (no event
     *  subscription), so it always fires and the holes really do appear. */
    private class ExcludeOnFix(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val variables: VarList = MixedVars(spanInts = intArrayOf(src, dst), boolVars = IntArray(0))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            return if (d.min == d.max) state.excludeIntValue(dst, d.min) else true
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFix(intMap[src], intMap[dst])

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    @Test
    fun `bounds-consistent alldifferent subscribes to exactly the bound events`() {
        val ad = AllDifferent(intArrayOf(5, 7), domainMin = 0, domainSize = 10, boundsConsistent = true)
        val watches = ad.asPropagator().initialIntEventWatches
        assertTrue(watches != null, "bounds-consistent alldifferent must opt into typed events")
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                5 to IntEvent.LB_RAISED,
                5 to IntEvent.UB_LOWERED,
                7 to IntEvent.LB_RAISED,
                7 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
            "bounds consistency reads only min/max, so it must not subscribe to interior or fixed events",
        )
    }

    @Test
    fun `full-gac alldifferent keeps occurrence-list wakeup`() {
        // Default (full GAC) needs every value removal, so it must not opt into the bound-only path.
        assertNull(
            AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4).asPropagator().initialIntEventWatches,
        )
    }

    @Test
    fun `bounds-alldiff is dropped from occurrence wakeup on its vars`() {
        val ad = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 4, boundsConsistent = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 3) },
            factors = listOf(ad),
        )
        for (v in 0..2) {
            assertContains(problem.intOccurrences[v].toList(), 0, "factor still mentions var $v")
            assertFalse(
                problem.nonIntEventWatcherIntOccurrences[v].contains(0),
                "subscribed bounds-alldiff must be off the occurrence-wakeup list for var $v",
            )
        }
    }

    @Test
    fun `bounds-alldiff with interior holes punched mid-search enumerates exactly brute force`() {
        // x0,x1,x2 pairwise distinct (::bounds) over 0..3, plus x3 over 0..3 whose fixing carves its
        // value out of x0 and x1 — punching interior holes the bounds-alldiff is not woken for. The
        // enumerated set must still equal brute force, proving the skipped wakes lose no soundness.
        val hi = 3
        val adVars = intArrayOf(0, 1, 2)
        for (seed in 1L..6L) {
            val factors = listOf<Factor>(
                AllDifferent(adVars, domainMin = 0, domainSize = hi + 1, boundsConsistent = true),
                ExcludeOnFix(src = 3, dst = 0),
                ExcludeOnFix(src = 3, dst = 1),
            )
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = Array(4) { IntDomain(0, hi.toLong()) },
                factors = factors,
            )
            val brute = HashSet<List<Int>>()
            val base = hi + 1
            for (m in 0 until base * base * base * base) {
                val a = m % base
                val b = (m / base) % base
                val c = (m / (base * base)) % base
                val d = m / (base * base * base)
                if (a != b && a != c && b != c && a != d && b != d) brute.add(listOf(a, b, c, d))
            }
            val found = BacktrackSolver(problem.bake())
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100_000).map { s -> s.ints.map { it.toInt() } }.toHashSet()
            assertEquals(
                brute,
                found,
                "seed $seed: bounds-alldiff + interior-hole co-constraint must match brute force",
            )
        }
    }

    // ── Except sets ──────────────────────────────────────────────────────────

    private fun allDiffExcept(ranges: List<Pair<Int, Int>>, except: IntArray): Problem {
        val lo = ranges.minOf { it.first }
        val hi = ranges.maxOf { it.second }
        val k = ranges.size
        return Problem(
            numBoolVars = 0,
            numIntVars = k,
            intDomains = Array(k) { IntDomain(ranges[it].first.toLong(), ranges[it].second.toLong()) },
            factors = arrayOf<Factor>(
                AllDifferent(
                    IntArray(k) { it },
                    domainMin = lo.toLong(),
                    domainSize = hi - lo + 1,
                    exceptSet = LongArray(except.size) { except[it].toLong() },
                ),
            ),
        )
    }

    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set for except`() {
        // (ranges, except). var ids = 0..k-1.
        val instances = listOf(
            Pair(listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3), intArrayOf(0)),
            Pair(listOf(0 to 2, 1 to 2, 0 to 1, 0 to 2, 0 to 2), intArrayOf(0)),
            Pair(listOf(1 to 3, 1 to 3, 1 to 3), intArrayOf(2)), // except a non-zero value
            Pair(listOf(0 to 2, 0 to 2, 0 to 2, 0 to 2), intArrayOf(0, 1)), // two excepted values
        )
        for ((idx, inst) in instances.withIndex()) {
            val (ranges, except) = inst
            val exceptSet = except.toHashSet()
            val k = ranges.size
            val brute = HashSet<List<Int>>()
            fun rec(i: Int, acc: IntArray) {
                if (i == k) {
                    val nonExcept = acc.filter { it !in exceptSet }
                    if (nonExcept.distinct().size == nonExcept.size) brute.add(acc.toList())
                    return
                }
                for (v in ranges[i].first..ranges[i].second) {
                    acc[i] = v
                    rec(i + 1, acc)
                }
            }
            rec(0, IntArray(k))
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(allDiffExcept(ranges, except).bake()).enumerate(params)
                .take(100_000).map { s -> s.ints.map { it.toInt() } }.toHashSet()
            assertEquals(brute, found, "instance #$idx (except=${except.toList()}): solver set must equal brute")
        }
    }

    @Test
    fun `non-except values must be pairwise distinct`() {
        // 3 vars over {1,2} with except={5}: {1,2} can't host 3 distinct ⟹ UNSAT.
        val problem = allDiffExcept(listOf(1 to 2, 1 to 2, 1 to 2), intArrayOf(5))
        assertTrue(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)) is SolveResult.Unsat)
    }

    // ── Hall-interval filtering ──────────────────────────────────────────────

    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
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
                intDomains = Array(k) { IntDomain(ranges[it].first.toLong(), ranges[it].second.toLong()) },
                factors = arrayOf<Factor>(
                    AllDifferent(IntArray(k) { it }, domainMin = lo.toLong(), domainSize = hi - lo + 1),
                ),
            )
            // CDCL config so conflict analysis + clause learning (hence the Hall-set
            // explanations) actually run; no restarts to keep enumeration completeness simple.
            val params = BacktrackParams(
                randomSeed = 1,
                variableSelector = Vsids(),
                maxLearnedClauses = 1_000,
            )
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
                .map { s -> s.ints.map { it.toInt() } }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `hall interval prunes other vars bounds via propagation`() {
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
        val baked = problem.propagate()
        assertTrue(
            baked is PropagationResult.Unsat,
            "expected Unsat from Hall pigeonhole; got $baked",
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
        assertEquals(4, d1.values.size, "v1 should have 4 values after punching out 3; got $d1")
        assertTrue(3 !in d1, "v1 should no longer contain 3")
        assertTrue(2 in d1 && 4 in d1, "v1 should still contain 2 and 4")
    }

    @Test
    fun `hall interval with spanning intruder punches every interior value`() {
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
        for (h in 3..5) assertTrue(h.toLong() !in d3, "value $h should be a hole, got $d3")
        for (k in intArrayOf(1, 2, 6, 7)) {
            assertTrue(k.toLong() in d3, "value $k should remain; got $d3")
        }
    }

    @Test
    fun `slack staircase domains are GAC - no over-prune from free-value reachability`() {
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
                intDomains = Array(n) { IntDomain(ranges[it].first.toLong(), ranges[it].second.toLong()) },
                factors = arrayOf<Factor>(
                    AllDifferent(IntArray(n) { it }, domainMin = lo.toLong(), domainSize = hi - lo + 1),
                ),
            )
            FactorPropagationOracle.assertGac(problem, "slack-staircase-n$n")
        }
    }

    // ── Bounds filtering vs brute force ──────────────────────────────────────

    /** True iff vars can be assigned pairwise-distinct values, each within [lo[i], hi[i]]. */
    private fun hasMatching(lo: IntArray, hi: IntArray): Boolean {
        val n = lo.size
        val matchValToVar = HashMap<Int, Int>()
        fun augment(i: Int, seen: HashSet<Int>): Boolean {
            for (v in lo[i]..hi[i]) {
                if (v in seen) continue
                seen.add(v)
                val occupant = matchValToVar[v]
                if (occupant == null || augment(occupant, seen)) {
                    matchValToVar[v] = i
                    return true
                }
            }
            return false
        }
        for (i in 0 until n) if (!augment(i, HashSet())) return false
        return true
    }

    /** True iff there's an SDR with x_i pinned to v. */
    private fun supports(lo: IntArray, hi: IntArray, i: Int, v: Int): Boolean {
        if (v < lo[i] || v > hi[i]) return false
        val lo2 = lo.copyOf()
        val hi2 = hi.copyOf()
        lo2[i] = v
        hi2[i] = v
        return hasMatching(lo2, hi2)
    }

    private fun bruteBounds(lo: IntArray, hi: IntArray): Triple<IntArray, IntArray, Boolean> {
        val n = lo.size
        val feasible = hasMatching(lo, hi)
        if (!feasible) return Triple(lo.copyOf(), hi.copyOf(), false)
        val bLo = IntArray(n)
        val bHi = IntArray(n)
        for (i in 0 until n) {
            var mn = hi[i] + 1
            var mx = lo[i] - 1
            for (v in lo[i]..hi[i]) {
                if (supports(lo, hi, i, v)) {
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
            }
            bLo[i] = mn
            bHi[i] = mx
        }
        return Triple(bLo, bHi, true)
    }

    @Test
    fun `bounds filtering matches brute force on random small instances`() {
        val rng = Random(20260614)
        var feasibleCases = 0
        var prunedCases = 0
        repeat(4000) {
            val n = 2 + rng.nextInt(5) // 2..6 vars
            val span = rng.nextInt(7) // values within 0..~8
            val lo = IntArray(n)
            val hi = IntArray(n)
            for (i in 0 until n) {
                val a = rng.nextInt(span + 1)
                val b = a + rng.nextInt(3) // width 0..2
                lo[i] = a
                hi[i] = b
            }
            val (bLo, bHi, bFeas) = bruteBounds(lo, hi)
            val nLo = IntArray(n)
            val nHi = IntArray(n)
            val loL = LongArray(n) { j -> lo[j].toLong() }
            val hiL = LongArray(n) { j -> hi[j].toLong() }
            val nLoL = LongArray(n)
            val nHiL = LongArray(n)
            val feas = computeBoundsAllDifferent(loL, hiL, nLoL, nHiL)
            for (i in 0 until n) {
                nLo[i] = nLoL[i].toInt()
                nHi[i] = nHiL[i].toInt()
            }
            assertEquals(bFeas, feas, "feasibility mismatch for lo=${lo.toList()} hi=${hi.toList()}")
            if (!bFeas) return@repeat
            feasibleCases++
            for (i in 0 until n) {
                // Soundness: the algorithm must never prune past the true bounds-consistent bound.
                assertTrue(
                    nLo[i] <= bLo[i],
                    "var $i over-raised min: algo ${nLo[i]} > brute ${bLo[i]} (lo=${lo.toList()} hi=${hi.toList()})",
                )
                assertTrue(
                    nHi[i] >= bHi[i],
                    "var $i over-lowered max: algo ${nHi[i]} < brute ${bHi[i]} (lo=${lo.toList()} hi=${hi.toList()})",
                )
                // Completeness: it must achieve full bounds consistency (reach the true bounds).
                assertEquals(bLo[i], nLo[i], "var $i min not tight (lo=${lo.toList()} hi=${hi.toList()})")
                assertEquals(bHi[i], nHi[i], "var $i max not tight (lo=${lo.toList()} hi=${hi.toList()})")
                if (nLo[i] != lo[i] || nHi[i] != hi[i]) prunedCases++
            }
        }
        // Sanity that the corpus actually exercised both feasible filtering and pruning.
        assertTrue(feasibleCases > 100, "too few feasible cases: $feasibleCases")
        assertTrue(prunedCases > 50, "too few pruning cases: $prunedCases")
    }

    // ── GAC filtering ────────────────────────────────────────────────────────

    private fun stateWithAD(factor: AllDifferent, domains: Array<IntDomain>): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(factor),
        )
        return PropagationState(problem, Assumptions.None)
    }

    private fun stateWithGCC(factor: GlobalCardinality, domains: Array<IntDomain>): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(factor),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `AllDifferent non-contiguous Hall set prunes interior value`() {
        // x0, x1 ∈ {1, 3} (sparse). x2 ∈ {1, 2, 3}. {1, 3} is a non-contiguous Hall set
        // monopolised by x0+x1 — Régin must prune both 1 and 3 from x2, leaving {2}. Bound
        // consistency only checks contiguous intervals and misses this.
        val d01 = IntDomain(1, 3).excludeValue(2) // sparse {1, 3}
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val state = stateWithAD(factor, arrayOf(d01, d01, IntDomain(1, 3)))
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(2, d2.min)
        assertEquals(2, d2.max)
    }

    @Test
    fun `AllDifferent Hall set prunes interior of spanning var`() {
        // x0, x1 ∈ {1, 2}, x2 ∈ {1, 2, 3, 4, 5}. Hall set {1, 2} monopolised; Régin removes
        // both from x2.
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 5)
        val state = stateWithAD(
            factor,
            arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 5)),
        )
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(3, d2.min)
        assertEquals(5, d2.max)
        assertFalse(1 in d2)
        assertFalse(2 in d2)
    }

    @Test
    fun `AllDifferent infeasible when pigeonhole violated`() {
        // 3 vars, 2 values total — infeasible.
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 2)
        val state = stateWithAD(
            factor,
            arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
        )
        assertFalse(state.problem.propagators[0].propagate(state, factorId = 0))
    }

    @Test
    fun `AllDifferent singleton conflict detected`() {
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 1, domainSize = 5)
        val state = stateWithAD(factor, arrayOf(IntDomain(3, 3), IntDomain(3, 3)))
        assertFalse(state.problem.propagators[0].propagate(state, factorId = 0))
    }

    @Test
    fun `GCC lo-bound forces possible matchers to be pinned`() {
        // cover = [5], lo = [3], hi = [3]. xs has exactly 3 vars all containing 5.
        // Régin: every var must take 5.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(5),
            countLow = intArrayOf(3),
            countHigh = intArrayOf(3),
            closed = false,
        )
        val state = stateWithGCC(
            factor,
            arrayOf(IntDomain(4, 6), IntDomain(4, 6), IntDomain(4, 6)),
        )
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        for (i in 0..2) {
            val d = state.intDomains[i]
            assertEquals(5, d.min, "var $i min")
            assertEquals(5, d.max, "var $i max")
        }
    }

    @Test
    fun `GCC hi-bound saturated prunes value from remaining vars`() {
        // cover = [7], lo = [0], hi = [1]. x0 is pinned to 7 (uses up the cap). x1, x2
        // currently include 7 in their domains — Régin must punch 7 out.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(7),
            countLow = intArrayOf(0),
            countHigh = intArrayOf(1),
            closed = false,
        )
        val state = stateWithGCC(
            factor,
            arrayOf(IntDomain(7, 7), IntDomain(6, 8), IntDomain(6, 8)),
        )
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        assertFalse(7 in state.intDomains[1])
        assertFalse(7 in state.intDomains[2])
    }

    @Test
    fun `GCC closed restricts xs to cover values`() {
        // cover = {2, 3}, lo = [0, 0], hi = [3, 3], closed = true.
        // x0 ∈ [1, 4] → must drop 1 and 4.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1),
            cover = longArrayOf(2, 3),
            countLow = intArrayOf(0, 0),
            countHigh = intArrayOf(3, 3),
            closed = true,
        )
        val state = stateWithGCC(factor, arrayOf(IntDomain(1, 4), IntDomain(2, 3)))
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        val d0 = state.intDomains[0]
        assertFalse(1 in d0)
        assertFalse(4 in d0)
        assertTrue(2 in d0)
        assertTrue(3 in d0)
    }

    @Test
    fun `GCC infeasible when lower bound exceeds possible`() {
        // cover = [9], lo = [2], hi = [5]. xs has 3 vars, only 1 contains 9.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(9),
            countLow = intArrayOf(2),
            countHigh = intArrayOf(5),
            closed = false,
        )
        val state = stateWithGCC(
            factor,
            arrayOf(IntDomain(8, 10), IntDomain(0, 1), IntDomain(0, 1)),
        )
        assertFalse(state.problem.propagators[0].propagate(state, factorId = 0))
    }

    @Test
    fun `GCC tightens countVars from definite and possible matchers`() {
        // cover = [3, 7], with two count vars.
        // xs: x0=3 (singleton), x1∈{3,7}, x2∈{3,7}. Definite-3=1, possible-3=3.
        // Definite-7=0, possible-7=2.
        // countVars[0] domain [0, 3] → should tighten min to 1, max to 3.
        // countVars[1] domain [0, 3] → should tighten min to 0, max to 2.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = longArrayOf(3, 7),
            countVars = intArrayOf(3, 4),
            closed = false,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(3, 3),
                IntDomain(3, 7).excludeValue(4).excludeValue(5).excludeValue(6),
                IntDomain(3, 7).excludeValue(4).excludeValue(5).excludeValue(6),
                IntDomain(0, 3),
                IntDomain(0, 3),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(state.problem.propagators[0].propagate(state, factorId = 0))
        assertEquals(1, state.intDomains[3].min)
        assertEquals(3, state.intDomains[3].max)
        assertEquals(0, state.intDomains[4].min)
        assertEquals(2, state.intDomains[4].max)
    }

    // ── Incremental fixpoint re-fire ─────────────────────────────────────────

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
                intDomains = Array(4) { IntDomain(1, sizes[it].toLong()) },
                factors = arrayOf(alldiff()),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
                .map { s -> s.ints.map { it.toInt() } }.toHashSet()
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
            intDomains = Array(6) { IntDomain(1, sizes[it].toLong()) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4, 5), domainMin = 1, domainSize = 6)),
        )
        val params = BacktrackParams(randomSeed = 7L, variableSelector = Vsids(), maxLearnedClauses = 2_000)
        val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
            .map { s -> s.ints.map { it.toInt() } }.toHashSet()
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

    // ── Conflict reasons across sessions ─────────────────────────────────────

    private fun problemOf(factor: Factor, vararg domains: IntDomain) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf(factor),
    )

    /** Drive [state] into an AllDifferent conflict by pinning two vars to [value]. */
    private fun failPinnedPair(state: PropagationState, a: Int, b: Int, value: Long): Boolean {
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(a, value) && state.tightenIntMax(a, value)) { "pin $a failed" }
        check(state.tightenIntMin(b, value) && state.tightenIntMax(b, value)) { "pin $b failed" }
        return state.problem.propagators[0].propagate(state, 0)
    }

    @Test
    fun `interleaved sessions keep independent alldifferent conflict reasons`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = problemOf(factor, IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9))

        // Control: session A alone — pin vars 0 and 1 to the same value and capture the reason.
        val control = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(control, a = 0, b = 1, value = 3), "pinned pair must conflict")
        val controlReason = problem.propagators[0].conflictReason(control, 0)

        // Interleaved: session A fails as above, then session B (same factor object) fails on a
        // DIFFERENT pair, then A's reason is read. With factor-level scratch B's failure
        // overwrites A's and this assertion breaks.
        val a = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(a, a = 0, b = 1, value = 3))
        val b = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(b, a = 1, b = 2, value = 7))
        val interleavedReason = problem.propagators[0].conflictReason(a, 0)

        assertContentEquals(
            controlReason,
            interleavedReason,
            "session A's conflict reason must be unaffected by session B's later failure",
        )
    }

    // ── Sort, LexLess and SymmetricAllDifferent bound events ─────────────────

    private class ExcludeOnFixWithAntecedent(val src: Int, val dst: Int) :
        Factor,
        Propagator {
        override val variables: VarList = MixedVars(spanInts = intArrayOf(src, dst), boolVars = IntArray(0))

        override fun propagate(state: PropagationState, factorId: Int): Boolean {
            val d = state.intDomains[src]
            // Explain the exclusion: dst != src.min holds *because* src is fixed to that value.
            // Citing src's singleton bounds keeps the recorded reason complete, so conflict analysis
            // cannot drop the premise (a null reason silently under-explains — see ElementDeltaTest).
            return if (d.min == d.max) {
                state.excludeIntValue(dst, d.min, state.composeIntVarAtomAntecedents(intArrayOf(src)))
            } else {
                true
            }
        }

        override fun remap(boolMap: IntArray, intMap: IntArray): Factor = ExcludeOnFixWithAntecedent(
            intMap[src],
            intMap[dst],
        )

        override fun structuralKey(): StructuralKey = error("test double has no structural key")

        override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = null
        override fun asPropagator(): Propagator = this
        override fun asInvariant(): Invariant = object : Invariant {}
    }

    private fun enumerateWithVsids(problem: Problem, seed: Long): HashSet<List<Int>> =
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
            .take(100_000).map { s -> s.ints.map { it.toInt() } }.toHashSet()

    private fun assertBoundOnly(watches: IntArray?, vars: IntArray) {
        val pairs = watches!!.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        val expected = vars.toHashSet().flatMap { v ->
            listOf(v to IntEvent.LB_RAISED, v to IntEvent.UB_LOWERED)
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `sort lexless and symmetric-alldiff subscribe to only bound events`() {
        assertBoundOnly(
            Sort(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3)).asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true).asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
        assertBoundOnly(
            SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0).asPropagator().initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
    }

    @Test
    fun `sort with interior holes punched mid-search enumerates exactly brute force`() {
        // ys = sorted(xs) over xs=[0,1], ys=[2,3]; var 4 carved out of xs. Domains 0..2 (wide enough
        // for an interior hole). Brute: y0=min, y1=max of (x0,x1), and x0,x1 ≠ c.
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                Sort(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3)),
                ExcludeOnFixWithAntecedent(src = 4, dst = 0),
                ExcludeOnFixWithAntecedent(src = 4, dst = 1),
            )
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (x0 in 0..2) {
                for (x1 in 0..2) {
                    for (y0 in 0..2) {
                        for (y1 in 0..2) {
                            for (c in 0..2) {
                                if (y0 == minOf(x0, x1) && y1 == maxOf(x0, x1) && x0 != c && x1 != c) {
                                    brute.add(listOf(x0, x1, y0, y1, c))
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(brute, enumerateWithVsids(problem, seed), "sort seed=$seed must match brute force")
        }
    }

    @Test
    fun `lexless with interior holes punched mid-search enumerates exactly brute force`() {
        // (x0,x1) <lex (y0,y1) strict; var 4 carved out of xs. Domains 0..2.
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true),
                ExcludeOnFixWithAntecedent(src = 4, dst = 0),
                ExcludeOnFixWithAntecedent(src = 4, dst = 1),
            )
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (x0 in 0..2) {
                for (x1 in 0..2) {
                    for (y0 in 0..2) {
                        for (y1 in 0..2) {
                            for (c in 0..2) {
                                val lex = x0 < y0 || (x0 == y0 && x1 < y1)
                                if (lex && x0 != c && x1 != c) brute.add(listOf(x0, x1, y0, y1, c))
                            }
                        }
                    }
                }
            }
            assertEquals(brute, enumerateWithVsids(problem, seed), "lexless seed=$seed must match brute force")
        }
    }

    @Test
    fun `symmetric-alldiff with interior holes punched mid-search enumerates exactly brute force`() {
        // xs=[0,1,2] a self-inverse permutation (involution); var 3 carved out of xs[0],xs[1].
        for (seed in 1L..3L) {
            val factors = listOf<Factor>(
                SymmetricAllDifferent(xs = intArrayOf(0, 1, 2), indexOffset = 0),
                ExcludeOnFixWithAntecedent(src = 3, dst = 0),
                ExcludeOnFixWithAntecedent(src = 3, dst = 1),
            )
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, factors)
            val brute = HashSet<List<Int>>()
            for (a0 in 0..2) {
                for (a1 in 0..2) {
                    for (a2 in 0..2) {
                        for (c in 0..2) {
                            val a = intArrayOf(a0, a1, a2)
                            val perm = a0 != a1 && a0 != a2 && a1 != a2 // distinct ⇒ permutation of 0..2
                            val involution = perm && (0..2).all { a[a[it]] == it }
                            if (involution && a0 != c && a1 != c) brute.add(listOf(a0, a1, a2, c))
                        }
                    }
                }
            }
            assertEquals(
                brute,
                enumerateWithVsids(problem, seed),
                "symmetric-alldiff seed=$seed must match brute force",
            )
        }
    }
}
