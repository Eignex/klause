package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiophantineReductionTest {

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList())

    @Test
    fun `carves interior off-residue values`() {
        // 2x + 3y = 10 ⇒ 2x ≡ 10 (mod 3) ⇒ x ≡ 2 (mod 3). Bound propagation only intervals x to [2,5];
        // the residue additionally removes the interior off-class values 3 and 4.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 2), IntDomain(0, 2)),
            Linear(longArrayOf(2, 3, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 10),
        )
        val domains = assertNotNull(DiophantineReduction.reduce(p).domains)
        val x = domains[0]
        assertTrue(3L !in x && 4L !in x, "off-residue interior values must be removed")
        assertTrue(2L in x && 5L in x, "in-residue values must survive")
    }

    @Test
    fun `coprime coefficients leave the domains untouched`() {
        // x + y = 5: the other coefficient's gcd is 1, so no residue constraint on either variable.
        val p = problem(
            arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 5),
        )
        assertNull(DiophantineReduction.reduce(p).domains)
    }

    /** Random equalities over boxes based at [lo]: every box point satisfying the equality must
     *  survive the carve. */
    private fun assertCarveKeepsEverySolution(seed: Int, lo: Long) {
        val rng = Random(seed)
        repeat(400) {
            val n = rng.nextInt(2, 4)
            val coeffs = LongArray(n) { rng.nextLong(1, 7) * (if (rng.nextBoolean()) 1 else -1) }
            val hi = LongArray(n) { lo + rng.nextLong(3, 9) }
            val bound = rng.nextLong(-8, 9)
            val doms = Array(n) { i -> IntDomain(lo, hi[i]) }
            val p = problem(doms, Linear(coeffs, IntArray(n) { i -> i }, LinearOp.EQ, bound))
            val out = DiophantineReduction.reduce(p).domains ?: return@repeat
            val idx = LongArray(n)
            fun rec(k: Int) {
                if (k == n) {
                    var sum = 0L
                    for (i in 0 until n) sum += coeffs[i] * idx[i]
                    if (sum == bound) {
                        for (i in 0 until n) {
                            assertTrue(idx[i] in out[i], "UNSOUND: cut solution x$i=${idx[i]}")
                        }
                    }
                    return
                }
                for (v in lo..hi[k]) {
                    idx[k] = v
                    rec(k + 1)
                }
            }
            rec(0)
        }
    }

    @Test
    fun `carving never excludes a real solution`() {
        assertCarveKeepsEverySolution(seed = 20, lo = 0)
    }

    @Test
    fun `carving never excludes a real solution over negative domains`() {
        // The residue normalization `((x - root) % mod + mod) % mod` is only exercised below zero here.
        assertCarveKeepsEverySolution(seed = 21, lo = -6)
    }

    @Test
    fun `an unsolvable congruence is reported as a contradiction`() {
        // 2x + 4y = 3: every term is even, so no integer point exists. The residue solve fails and the
        // pass emits the two contradictory units that make the problem bake Unsat.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 3),
        )
        val reduced = p.withPassDelta(DiophantineReduction.reduce(p), BakeConfig.NONE)
        assertIs<PropagationResult.Unsat>(reduced.propagate(Assumptions.None))
    }

    @Test
    fun `a solvable congruence is not reported as a contradiction`() {
        // 2x + 4y = 6 has integer points, so the same shape must stay satisfiable after the carve.
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            Linear(longArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 6),
        )
        val reduced = p.withPassDelta(DiophantineReduction.reduce(p), BakeConfig.NONE)
        assertIs<PropagationResult.Implied>(reduced.propagate(Assumptions.None))
    }
}
