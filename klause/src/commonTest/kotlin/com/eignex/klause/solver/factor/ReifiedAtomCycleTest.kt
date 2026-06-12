package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for #132: reified equalities over shared int vars create same-level cycles in the
 * atom antecedent graph. Conflict analysis used to drop a resolved atom that recurred as a
 * genuine premise, learning an over-strong clause that pruned feasible assignments (an unsound
 * nogood that over-proved optimality). The complete enumeration must match the brute-force set.
 */
class ReifiedAtomCycleTest {

    @Test
    fun `backtrack enumeration over reified equalities matches brute force`() {
        // Three vars over {0,1,2}; for each var v and value k a channel aux (aux ↔ v == k).
        // Clauses tie channels across variables so propagation forces equalities both ways,
        // exercising the cyclic atom resolution. Enumerate full assignments and compare to brute.
        val n = 3
        val dvals = intArrayOf(0, 1, 2)
        val numBool = n * dvals.size
        fun chan(v: Int, kIdx: Int) = v * dvals.size + kIdx
        val factors = ArrayList<Factor>()
        for (v in 0 until n) {
            for (kIdx in dvals.indices) {
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = chan(v, kIdx),
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(v),
                        op = LinearOp.EQ,
                        bound = dvals[kIdx],
                    ),
                )
            }
        }
        // Linking clauses: (v0==0) → (v1==1), (v1==1) → (v2==2), and not all three equal to 0.
        factors.add(Clause(intArrayOf(Lit.make(chan(0, 0), false), Lit.make(chan(1, 1), true))))
        factors.add(Clause(intArrayOf(Lit.make(chan(1, 1), false), Lit.make(chan(2, 2), true))))
        factors.add(
            Clause(intArrayOf(Lit.make(chan(0, 0), false), Lit.make(chan(1, 0), false), Lit.make(chan(2, 0), false))),
        )

        val p = Problem(
            numBoolVars = numBool,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 2) },
            factors = factors.toTypedArray(),
        )

        val brute = HashSet<List<Int>>()
        fun ok(a: IntArray): Boolean {
            if (a[0] == 0 && a[1] != 1) return false
            if (a[1] == 1 && a[2] != 2) return false
            if (a[0] == 0 && a[1] == 0 && a[2] == 0) return false
            return true
        }
        for (x0 in 0..2) {
            for (x1 in 0..2) {
                for (x2 in 0..2) {
                    val a = intArrayOf(x0, x1, x2)
                    if (ok(a)) brute.add(a.toList())
                }
            }
        }

        val params = BacktrackParams(randomSeed = 1L, variableHeuristic = Vsids(), maxLearnedClauses = 1_000)
        val found = BacktrackSolver(p).enumerate(params).take(100_000).map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "backtrack enumeration must equal the brute-force feasible set")
    }
}
