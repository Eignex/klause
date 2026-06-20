package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Soundness gate for the weakest-bound LCG relaxation in [Linear] conflict reasons
 * (collectLinearRelaxedConflictAntecedents). Under the full CDCL backtracker (VSIDS + LBD
 * clause forgetting, so the relaxed leaf antecedents are exercised by clause learning and
 * backjumping) enumeration must equal the brute-force solution set on a battery of small
 * linear systems. The relaxation cites looser bounds at historically-correct levels; an
 * over-relaxation (slack mis-accounted) or a wrong cited level (bad backjump) would prune a
 * feasible subtree and drop a solution, shrinking the set.
 */
class LinearWeakestBoundTest {

    private class Con(val coeffs: IntArray, val op: LinearOp, val bound: Int)

    private fun satisfies(con: Con, vals: IntArray, varsOf: IntArray): Boolean {
        var s = 0
        for (i in varsOf.indices) s += con.coeffs[i] * vals[varsOf[i]]
        return when (con.op) {
            LinearOp.LE -> s <= con.bound
            LinearOp.GE -> s >= con.bound
            LinearOp.EQ -> s == con.bound
            LinearOp.NE -> s != con.bound
        }
    }

    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // Each instance: n vars over a shared [lo,hi], plus a list of linear constraints over
        // all n vars (coeffs parallel to var ids 0..n-1).
        data class Inst(val n: Int, val lo: Int, val hi: Int, val cons: List<Con>)
        val instances = listOf(
            Inst(3, 0, 3, listOf(Con(intArrayOf(2, 1, 1), LinearOp.LE, 5))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 1), LinearOp.GE, 5))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 2), LinearOp.EQ, 6))),
            Inst(3, 0, 2, listOf(Con(intArrayOf(1, -1, 1), LinearOp.LE, 1))),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, 1, 1), LinearOp.LE, 4), Con(intArrayOf(1, 1, 1), LinearOp.GE, 2))),
            Inst(
                4,
                0,
                2,
                listOf(Con(intArrayOf(2, 1, 1, 0), LinearOp.LE, 4), Con(intArrayOf(0, 1, 2, 1), LinearOp.GE, 3)),
            ),
            Inst(3, 0, 3, listOf(Con(intArrayOf(1, -1, 2), LinearOp.EQ, 3))),
            // Larger coefficients → bigger per-tighten relaxation room (rounding remainder up to
            // |c|-1); stresses the per-tighten weakest-bound relaxation specifically.
            Inst(3, 0, 4, listOf(Con(intArrayOf(3, 2, 1), LinearOp.LE, 9))),
            Inst(3, 0, 4, listOf(Con(intArrayOf(3, 2, 1), LinearOp.GE, 8))),
            Inst(
                4,
                0,
                3,
                listOf(Con(intArrayOf(2, 3, 1, 2), LinearOp.LE, 10), Con(intArrayOf(1, 1, 1, 1), LinearOp.GE, 3)),
            ),
            // Deep tighten chain that then conflicts (exercises stored per-tighten antecedents
            // being resolved through during conflict analysis).
            // odd RHS, even coeffs → tightenings + UNSAT
            Inst(4, 0, 3, listOf(Con(intArrayOf(2, 2, 2, 2), LinearOp.EQ, 9))),
            Inst(
                4,
                0,
                5,
                listOf(Con(intArrayOf(4, -2, 3, -1), LinearOp.LE, 6), Con(intArrayOf(1, 1, 1, 1), LinearOp.GE, 4)),
            ),
        )
        for ((idx, inst) in instances.withIndex()) {
            val n = inst.n
            val varsOf = IntArray(n) { it }
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (inst.cons.all { satisfies(it, acc, varsOf) }) brute.add(acc.toList())
                    return
                }
                for (v in inst.lo..inst.hi) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val factors: Array<Factor> = inst.cons
                .map { Linear(coeffs = it.coeffs, vars = varsOf, op = it.op, bound = it.bound) as Factor }
                .toTypedArray()
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(inst.lo, inst.hi) },
                factors = factors,
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(200_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `wide constraints use the shared start-bound reason and still enumerate exactly`() {
        // Arity > LINEAR_SHARED_REASON_ARITY (32) switches Linear onto the shared start-of-call
        // reason built from the contribution snapshot — now the *only* path that materialises
        // rLo/rHi. Pad the constraint with fixed singleton vars (1..1) so the arity is wide while
        // the free space stays small enough to brute-force; the propagated tightenings on the free
        // vars then exercise the wide reason builder (and the NE case exercises the recomputed
        // contribution in the NE branch) under full CDCL learning.
        val arity = 40
        val freeCount = 3
        val varsOf = IntArray(arity) { it }
        val lo = IntArray(arity) { if (it < freeCount) 0 else 1 }
        val hi = IntArray(arity) { if (it < freeCount) 3 else 1 }
        val cons = listOf(
            Con(IntArray(arity) { 1 }, LinearOp.LE, 39), // free sum <= 2 (37 from the fixed tail)
            Con(IntArray(arity) { 1 }, LinearOp.GE, 40), // free sum >= 3
            Con(IntArray(arity) { if (it < freeCount) 2 else 1 }, LinearOp.EQ, 41), // 2*free sum == 4
            Con(IntArray(arity) { 1 }, LinearOp.NE, 38), // free sum != 1
        )
        for ((idx, con) in cons.withIndex()) {
            val brute = HashSet<List<Int>>()
            val acc = IntArray(arity) { if (it < freeCount) 0 else 1 }
            fun rec(p: Int) {
                if (p == freeCount) {
                    if (satisfies(con, acc, varsOf)) brute.add(acc.toList())
                    return
                }
                for (v in lo[p]..hi[p]) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = arity,
                intDomains = Array(arity) { IntDomain(lo[it], hi[it]) },
                factors = arrayOf<Factor>(Linear(coeffs = con.coeffs, vars = varsOf, op = con.op, bound = con.bound)),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(200_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "wide instance #$idx (op=${con.op}): solution set must equal brute force")
        }
    }
}
