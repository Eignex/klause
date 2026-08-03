package com.eignex.klause.factor.table

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegularPropagatorTest {

    /**
     * Soundness gate for the sharpened forward-collapse conflict reason. Uses a DFA with dead
     * transitions ("no two consecutive 1s") so early-prefix infeasibility fires the collapse
     * path. Under the full CDCL backtracker (VSIDS + clause forgetting) enumeration must equal
     * the brute-force accepted set; an unsound prefix reason would drop a feasible suffix.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // alphabet {1,2}; states {1,2}; q0=1; F={1,2}. δ: (1,1)→2 (1,2)→1 (2,1)→0(dead) (2,2)→1.
        // Accepts exactly the strings with no two consecutive 1s.
        val transitions = intArrayOf(2, 1, 0, 1)
        fun delta(q: Int, s: Int): Int = if (q in 1..2 && s in 1..2) transitions[(q - 1) * 2 + (s - 1)] else 0
        fun accepts(vals: IntArray): Boolean {
            var q = 1
            for (s in vals) {
                q = delta(q, s)
                if (q == 0) return false
            }
            return q == 1 || q == 2
        }
        // Each instance: per-position symbol range over {1,2}.
        val instances = listOf(
            listOf(1 to 2, 1 to 2, 1 to 2, 1 to 2), // free 4-seq
            listOf(1 to 1, 1 to 1, 1 to 2, 1 to 2), // forces 1,1 prefix → no solution
            listOf(1 to 2, 1 to 1, 1 to 1, 1 to 2), // forces consecutive 1s mid-seq
            listOf(1 to 2, 1 to 2, 1 to 2), // free 3-seq
        )
        for ((idx, ranges) in instances.withIndex()) {
            val n = ranges.size
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (accepts(acc)) brute.add(acc.toList())
                    return
                }
                for (v in ranges[p].first..ranges[p].second) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(ranges[it].first.toLong(), ranges[it].second.toLong()) },
                factors = arrayOf<Factor>(
                    Regular(
                        seq = IntArray(n) { it },
                        numStates = 2,
                        alphabetSize = 2,
                        transitions = LongArray(transitions.size) { transitions[it].toLong() },
                        q0 = 1,
                        accepting = intArrayOf(1, 2),
                    ),
                ),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `regular accepts strings matching the DFA`() {
        // DFA: alphabet = {1, 2}; states = {1, 2}; q0 = 1, F = {2}.
        // δ(1, 1) = 1, δ(1, 2) = 2, δ(2, 1) = 1, δ(2, 2) = 2.
        // Accepts strings ending in 2. 4-length seq ∈ {1, 2}^4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    // T[(q-1)*S + (s-1)] :  (1,1)→1, (1,2)→2, (2,1)→1, (2,2)→2
                    transitions = longArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        // Every accepted string must end in 2.
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            assertEquals(2L, sample.ints[3], "regular violated: ints=${sample.ints.toList()}")
        }
    }

    @Test
    fun `regular rejects pinned-to-fail strings`() {
        // Same DFA. Pin seq = (1, 1, 1, 1) → ends in state 1 ∉ F. Unsat.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2, 3),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = longArrayOf(1, 2, 1, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `regular plus a linear bound enumerates exactly the brute-force set`() {
        // Soundness gate for the unchanged-domains fast path. Pairing Regular with a Linear over the
        // SAME seq makes the two factors re-wake each other, so Regular is fired repeatedly on one
        // PropagationState — including no-op re-fires where its domains are unchanged and the fast
        // path returns early. An unsound skip would drop a feasible string, shrinking the set.
        // DFA: no two consecutive 1s over {1,2} (q0=1, F={1,2}; δ (1,1)→2 (1,2)→1 (2,1)→0 (2,2)→1).
        val transitions = intArrayOf(2, 1, 0, 1)
        fun delta(q: Int, s: Int): Int = if (q in 1..2 && s in 1..2) transitions[(q - 1) * 2 + (s - 1)] else 0
        fun accepts(vals: IntArray): Boolean {
            var q = 1
            for (s in vals) {
                q = delta(q, s)
                if (q == 0) return false
            }
            return q == 1 || q == 2
        }
        val n = 4
        for (bound in intArrayOf(5, 6, 7)) {
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (accepts(acc) && acc.sum() <= bound) brute.add(acc.toList())
                    return
                }
                for (v in 1..2) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(1, 2) },
                factors = arrayOf<Factor>(
                    Regular(
                        seq = IntArray(n) { it },
                        numStates = 2,
                        alphabetSize = 2,
                        transitions = LongArray(transitions.size) { transitions[it].toLong() },
                        q0 = 1,
                        accepting = intArrayOf(1, 2),
                    ),
                    Linear(coeffs = IntArray(n) { 1 }, vars = IntArray(n) { it }, op = LinearOp.LE, bound = bound),
                ),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem.bake()).enumerate(params).take(100_000)
                .map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "Regular+Linear (bound=$bound): solution set must equal brute force")
        }
    }

    /**
     * Heavy soundness gate for the reversible, delta-driven incremental propagator: random DFAs over
     * longer sequences, enumerated under full CDCL (which branches, prunes and backtracks deeply), must
     * match brute force exactly. A stale reachability bit (a missed forward/backward layer recompute,
     * a botched trail rollback, or an over-wide prune) would drop or admit an assignment.
     */
    @Test
    fun `randomized DFAs enumerate exactly the brute-force set across deep backtracking`() {
        val rng = Random(0x12345)
        repeat(60) { trial ->
            val numStates = rng.nextInt(2, 5)
            val alphabet = rng.nextInt(2, 4)
            val n = rng.nextInt(3, 7)
            val q0 = rng.nextInt(1, numStates + 1)
            // Random transition table (0 = dead), biased so some transitions live.
            val transitions = IntArray(
                numStates * alphabet,
            ) { if (rng.nextInt(3) == 0) 0 else rng.nextInt(1, numStates + 1) }
            val accepting = (1..numStates).filter { rng.nextBoolean() }.toIntArray()
            if (accepting.isEmpty()) return@repeat // no accepting state → trivially unsat, skip
            // Per-position domain: a random non-empty subset of the alphabet.
            val ranges = Array(n) {
                val lo = rng.nextInt(1, alphabet + 1)
                val hi = rng.nextInt(lo, alphabet + 1)
                lo to hi
            }
            fun delta(q: Int, s: Int) = if (q in 1..numStates &&
                s in 1..alphabet
            ) {
                transitions[(q - 1) * alphabet + (s - 1)]
            } else {
                0
            }
            fun accepts(vals: IntArray): Boolean {
                var q = q0
                for (s in vals) {
                    q = delta(q, s)
                    if (q == 0) return false
                }
                return q in accepting
            }
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (accepts(acc)) brute.add(acc.toList())
                    return
                }
                for (v in ranges[p].first..ranges[p].second) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)
            val domains = Array(n) { IntDomain(ranges[it].first.toLong(), ranges[it].second.toLong()) }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = domains,
                factors = arrayOf<Factor>(
                    Regular(
                        IntArray(n) { it },
                        numStates,
                        alphabet,
                        LongArray(transitions.size) { transitions[it].toLong() },
                        q0,
                        accepting,
                    ),
                ),
            )
            val params = BacktrackParams(
                randomSeed = (trial + 1).toLong(),
                variableSelector = Vsids(),
                maxLearnedClauses = 500,
            )
            val found = BacktrackSolver(
                problem.bake(),
            ).enumerate(params).take(100_000).map { it.ints.map { v -> v.toInt() } }.toHashSet()
            assertEquals(brute, found, "trial #$trial (Q=$numStates |Σ|=$alphabet n=$n q0=$q0): must equal brute force")
        }
    }
}
