package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Table
import com.eignex.klause.factor.table.internals.TableGroupCache
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.intdomain.SurvivorsDomain
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TablePropagatorTest {

    @Test
    fun `enumerates only the allowed tuples`() {
        // Two vars in [0..3], allowed: (0, 1), (2, 3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = longArrayOf(0, 1, 2, 3),
                ),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.map { v -> v.toInt() } }
            .toList()
            .toSet()
        assertEquals(setOf(listOf(0, 1), listOf(2, 3)), results)
    }

    @Test
    fun `enumerates allowed tuples over values beyond Int range`() {
        // Two vars over the wide set domain {0, 5e9} (span > 2^31, small cardinality — a float-scaled
        // bucket table). Allowed: (0, 5e9), (5e9, 0). The span-sized bitset support map cannot represent
        // this span; the value-keyed path must prune soundly instead of truncating the value offset.
        val b = 5_000_000_000L
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { SurvivorsDomain(0, b, longArrayOf(0, b)) },
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, b, b, 0)),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.toList() }
            .toList()
            .toSet()
        assertEquals(setOf(listOf(0L, b), listOf(b, 0L)), results)
    }

    @Test
    fun `a wildcard column matches every value of its variable`() {
        // Rows (0, *) and (2, 3) over vars in [0..3]: the wildcard lets b be anything when a = 0,
        // so a = 0 pairs with all four b values, plus the ground row (2, 3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = longArrayOf(0, Long.MIN_VALUE, 2, 3), // per-cell lower bound
                    hi = longArrayOf(0, Long.MAX_VALUE, 2, 3), // (0,1) is the unbounded interval = '*'
                ),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.map { v -> v.toInt() } }.toList().toSet()
        assertEquals(
            setOf(listOf(0, 0), listOf(0, 1), listOf(0, 2), listOf(0, 3), listOf(2, 3)),
            results,
        )
    }

    @Test
    fun `an interval column matches every value in its range`() {
        // Rows (0, [1..2]) and (3, 3) over vars in [0..3]: the interval lets b be 1 or 2 when a = 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = longArrayOf(0, 1, 3, 3), // lower bounds
                    hi = longArrayOf(0, 2, 3, 3), // (0,1) covers [1, 2]
                ),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.map { v -> v.toInt() } }.toList().toSet()
        assertEquals(setOf(listOf(0, 1), listOf(0, 2), listOf(3, 3)), results)
    }

    @Test
    fun `an interval column prunes to only values within the range and domain`() {
        // Single row (a, [2..5]) with b ∈ [0..3]: b must land in [2..5] ∩ [0..3] = {2, 3}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 2), hi = longArrayOf(0, 5)),
            ),
        )
        val results = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { it.ints.map { v -> v.toInt() } }.toList().toSet()
        assertEquals(setOf(listOf(0, 2), listOf(0, 3)), results)
    }

    @Test
    fun `infeasible when domain excludes every row`() {
        // Two vars pinned to (1, 1). Allowed: (0,1), (2,3). Neither matches → Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(1, 1)),
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1),
                    tuples = longArrayOf(0, 1, 2, 3),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `propagation tightens domain to support set`() {
        // 3 vars ∈ [0..9]; tuples = [(1,2,3), (1,4,5), (7,8,9)].
        // After propagation: col 0 ⊆ {1, 7}; col 1 ⊆ {2, 4, 8}; col 2 ⊆ {3, 5, 9}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Table(
                    xs = intArrayOf(0, 1, 2),
                    tuples = longArrayOf(1, 2, 3, 1, 4, 5, 7, 8, 9),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val ints = sat.assignment.ints.map { it.toInt() }
        assertTrue(
            ints in setOf(listOf(1, 2, 3), listOf(1, 4, 5), listOf(7, 8, 9)),
            "got $ints — not a known tuple",
        )
    }

    @Test
    fun `backtrack enumeration equals the in-domain tuple set`() {
        // Soundness gate for the reversible, delta-driven STR2 sweep: enumerating fires propagate
        // repeatedly on one PropagationState — the delta fast path skips no-op re-fires, a column
        // shrink re-wakes the sweep — across push/pop that restore the reversible live-set size
        // (numValid). An unsound skip (returning satisfied when a value should have been pruned)
        // would let a forbidden tuple through, so the enumerated set must equal the brute-force set
        // of allowed rows that lie within the domains.
        data class Inst(val arity: Int, val lo: Int, val hi: Int, val tuples: List<List<Int>>)
        val instances = listOf(
            Inst(2, 0, 3, listOf(listOf(0, 1), listOf(2, 3), listOf(1, 1))),
            Inst(3, 0, 4, listOf(listOf(1, 2, 3), listOf(1, 4, 1), listOf(3, 3, 3), listOf(0, 0, 0))),
            Inst(3, 0, 2, listOf(listOf(0, 1, 2), listOf(2, 1, 0), listOf(1, 1, 1), listOf(0, 0, 0), listOf(2, 2, 2))),
        )
        for ((idx, inst) in instances.withIndex()) {
            val varsOf = IntArray(inst.arity) { it }
            val flat = LongArray(inst.tuples.size * inst.arity)
            inst.tuples.forEachIndexed { r, t ->
                for (c in 0 until inst.arity) flat[r * inst.arity + c] = t[c].toLong()
            }
            val brute = inst.tuples.filter { t -> t.all { it in inst.lo..inst.hi } }.map { it.toList() }.toHashSet()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = inst.arity,
                intDomains = Array(inst.arity) { IntDomain(inst.lo.toLong(), inst.hi.toLong()) },
                factors = arrayOf<Factor>(Table(xs = varsOf, tuples = flat)),
            )
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "table instance #$idx: enumerated solutions must equal in-domain tuples")
        }
    }

    @Test
    fun `randomized tables enumerate exactly the in-domain tuple set across deep backtracking`() {
        // Stresses the reversible STR2 sparse set + delta-driven re-sweep: random tables (varied
        // arity, value span and row count) enumerated under CDCL, which branches/prunes/backtracks
        // deeply — the live-set swap-removals must roll back exactly via numValid, and the delta must
        // re-wake the sweep on every column shrink. The enumerated set must equal the in-domain rows.
        val rng = Random(0x7AB1E)
        repeat(80) { trial ->
            val arity = rng.nextInt(2, 5)
            val lo = 0
            val hi = rng.nextInt(1, 4)
            val numRows = rng.nextInt(1, 9)
            val rows = ArrayList<List<Int>>(numRows)
            repeat(numRows) { rows.add(List(arity) { rng.nextInt(lo, hi + 1) }) }
            val flat = LongArray(rows.size * arity)
            rows.forEachIndexed { r, t -> for (c in 0 until arity) flat[r * arity + c] = t[c].toLong() }
            // Per-column random subdomain (still within [lo, hi]) to force pruning + cascades.
            val cdom = Array(arity) {
                val a = rng.nextInt(lo, hi + 1)
                val b = rng.nextInt(a, hi + 1)
                a to b
            }
            val brute = rows.filter { t -> t.indices.all { c -> t[c] in cdom[c].first..cdom[c].second } }
                .map { it.toList() }.toHashSet()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = arity,
                intDomains = Array(arity) { IntDomain(cdom[it].first.toLong(), cdom[it].second.toLong()) },
                factors = arrayOf<Factor>(Table(xs = IntArray(arity) { it }, tuples = flat)),
            )
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = (trial + 1).toLong()))
                .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "trial #$trial (arity=$arity hi=$hi rows=$numRows): must equal in-domain tuples")
        }
    }

    @Test
    fun `table factors sharing a group cache enumerate exactly the allowed tuples`() {
        // Several binary tables over disjoint variable pairs bind one shared TableGroupCache — the `<group>`
        // shape where every row instantiates the same relation. The first row over full domains records the
        // "prunes no domain" verdict; the others reuse it, skipping their own sweep. Enumeration under the
        // CDCL backtracker must still equal brute force, and the mix of full-domain (reusing) and pinned
        // (recomputing) rows exercises both paths across deep backtracking. The relation over {1..4}² allows
        // every off-diagonal pair (i != j) — dense enough that a full-domain sweep prunes nothing, so the
        // reuse verdict is both set and hit.
        val rel = ArrayList<Long>()
        for (a in 1..4) {
            for (b in 1..4) {
                if (a != b) {
                    rel.add(a.toLong())
                    rel.add(b.toLong())
                }
            }
        }
        val flat = rel.toLongArray()
        val cache = TableGroupCache()
        fun mk(v0: Int, v1: Int): Table = Table(xs = intArrayOf(v0, v1), tuples = flat).also { it.groupCache = cache }
        for ((idx, h0) in listOf(4, 1).withIndex()) {
            cache.noopMins = null
            cache.noopMaxs = null
            val brute = HashSet<List<Int>>()
            for (a in 1..h0) {
                for (b in 1..4) {
                    for (c in 1..4) {
                        for (d in 1..4) {
                            for (e in 1..4) {
                                for (f in 1..4) {
                                    if (a != b && c != d && e != f) brute.add(listOf(a, b, c, d, e, f))
                                }
                            }
                        }
                    }
                }
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 6,
                intDomains = Array(6) { if (it == 0) IntDomain(1, h0.toLong()) else IntDomain(1, 4) },
                factors = arrayOf<Factor>(mk(0, 1), mk(2, 3), mk(4, 5)),
            )
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L))
                .take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "shared-cache table #$idx (var0 hi=$h0): must equal brute force")
        }
    }
}
