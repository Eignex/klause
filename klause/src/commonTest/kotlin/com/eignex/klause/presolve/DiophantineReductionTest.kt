package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
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

    @Test
    fun `carving never excludes a real solution`() {
        val rng = Random(20)
        repeat(400) {
            val n = rng.nextInt(2, 4)
            val coeffs = LongArray(n) { rng.nextLong(1, 7) * (if (rng.nextBoolean()) 1 else -1) }
            val hi = LongArray(n) { rng.nextLong(3, 9) }
            val bound = rng.nextLong(-8, 9)
            val doms = Array(n) { i -> IntDomain(0, hi[i]) }
            val p = problem(doms, Linear(coeffs, IntArray(n) { i -> i }, LinearOp.EQ, bound))
            val out = DiophantineReduction.reduce(p).domains ?: return@repeat
            // Every box point satisfying the equality must survive the carve.
            val idx = IntArray(n)
            fun rec(k: Int) {
                if (k == n) {
                    var sum = 0L
                    for (i in 0 until n) sum += coeffs[i] * idx[i]
                    if (sum == bound) {
                        for (i in 0 until n) {
                            assertTrue(idx[i].toLong() in out[i], "UNSOUND: cut solution x$i=${idx[i]}")
                        }
                    }
                    return
                }
                for (v in 0..hi[k]) {
                    idx[k] = v.toInt()
                    rec(k + 1)
                }
            }
            rec(0)
        }
    }
}
