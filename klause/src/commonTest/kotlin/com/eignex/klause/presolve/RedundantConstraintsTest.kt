package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constraint subsumption (#447). Removing a redundant constraint must preserve the feasible set
 * *exactly* — every test enumerates the whole assignment space and compares the count before and
 * after, and asserts the expected drop (or no-op).
 */
class RedundantConstraintsTest {

    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun feasibleCount(problem: Problem): Int {
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.intDomains[it].min }
        var count = 0
        while (true) {
            if (isFeasible(problem, ints.copyOf())) count++
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.intDomains[i].max) break
                ints[i] = problem.intDomains[i].min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun checkPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val delta = Presolve.removeRedundantConstraints(problem)
        val out = problem.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(feasibleCount(problem), feasibleCount(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertTrue(delta.isEmpty, "$name: expected no change")
        }
        return out
    }

    private fun dom(n: Int, hi: Int) = Array(n) { IntDomain(0, hi.toLong()) }
    private fun le(b: Int, vararg vc: Int) =
        Linear(IntArray(vc.size / 2) { vc[2 * it + 1] }, IntArray(vc.size / 2) { vc[2 * it] }, LinearOp.LE, b)

    @Test
    fun `dominated less-equal constraint is dropped`() {
        // x + y <= 3 implies x + y <= 5.
        val problem = Problem(
            0,
            2,
            dom(2, 5),
            listOf(le(5, 0, 1, 1, 1), le(3, 0, 1, 1, 1)),
        )
        val out = checkPreserved("dominated-le", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
        assertEquals(3, (out.factors[0] as Linear).bound, "the tighter bound is kept")
    }

    @Test
    fun `increasing chain subsumes a redundant explicit comparator`() {
        // strictly_increasing(x0, x1) exposes the exact row x0 - x1 <= -1, which dominates the
        // redundant explicit x0 - x1 <= 0; the chain factor is never dropped.
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(Increasing(intArrayOf(0, 1), strict = true), le(0, 0, 1, 1, -1)),
        )
        val out = checkPreserved("increasing-subsumes-le", problem, expectDrop = true)
        assertTrue(out.factors.single() is Increasing, "the increasing chain survives, the comparator drops")
    }

    @Test
    fun `dominated greater-equal constraint is dropped`() {
        // x >= 4 implies x >= 2; the tighter (>=4) survives.
        val problem = Problem(
            0,
            1,
            dom(1, 5),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 4),
            ),
        )
        val out = checkPreserved("dominated-ge", problem, expectDrop = true)
        assertEquals(4, (out.factors.single() as Linear).bound)
    }

    @Test
    fun `equality dominates the matching inequalities`() {
        // x = 3 implies both x <= 5 and x >= 1 — both inequalities drop, the equality stays.
        val problem = Problem(
            0,
            1,
            dom(1, 5),
            listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
        )
        val out = checkPreserved("eq-dominates", problem, expectDrop = true)
        assertEquals(LinearOp.EQ, (out.factors.single() as Linear).op)
    }

    @Test
    fun `exact-duplicate constraints are removed`() {
        // Two identical rows and two identical AllDifferents → one of each survives (structuralKey).
        val problem = Problem(
            0,
            3,
            dom(3, 2),
            listOf(
                le(2, 0, 1, 1, 1),
                le(2, 0, 1, 1, 1),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3),
            ),
        )
        val out = checkPreserved("exact-dup", problem, expectDrop = true)
        assertEquals(2, out.factors.size)
    }

    @Test
    fun `independent constraints are left untouched`() {
        // Different variables / opposite directions ⇒ not comparable ⇒ no-op (same problem instance).
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(le(3, 1, 0), le(3, 1, 1)),
        )
        checkPreserved("independent", problem, expectDrop = false)
    }

    @Test
    fun `negated-equivalent inequalities are deduplicated`() {
        // x + y <= 3 and -x - y >= -3 are the same half-space; one survives.
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(
                le(3, 0, 1, 1, 1),
                Linear(intArrayOf(-1, -1), intArrayOf(0, 1), LinearOp.GE, -3),
            ),
        )
        val factorsBefore = problem.factors
        val out = checkPreserved("negated-equiv", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
        assertTrue(out.factors.single() in factorsBefore, "a surviving original is kept verbatim")
    }

    @Test
    fun `proportional rows match standalone via GCD normalization`() {
        // x+y<=2 and 2x+2y<=4 are the same constraint. GCD-reducing inside the pass buckets them even
        // without strengthen running first, so the duplicate drops (#466).
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 1, 1, 1), le(4, 0, 2, 1, 2)))
        val out = checkPreserved("proportional-dup", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `a proportional looser row is dominated standalone`() {
        // 2x+2y<=6 reduces to x+y<=3, dominated by the tighter x+y<=2.
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 1, 1, 1), le(6, 0, 2, 1, 2)))
        val out = checkPreserved("proportional-loose", problem, expectDrop = true)
        assertEquals(2, (out.factors.single() as Linear).bound, "the tighter reduced bound survives")
    }

    @Test
    fun `variable-subset row is dominated`() {
        // x+y<=2 implies x+y+z<=5 because z<=3, so the larger-support row drops (#466).
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(5, 0, 1, 1, 1, 2, 1)))
        checkPreserved("subset-le", problem, expectDrop = true)
    }

    @Test
    fun `proportional variable-subset row is dominated`() {
        // x+y<=2 implies 2x+2y+z<=10: 2(x+y)<=4 and z<=3 give a sum <= 7 <= 10. Coefficients on the
        // shared vars are a positive multiple (k=2) of the dominator's.
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(10, 0, 2, 1, 2, 2, 1)))
        checkPreserved("subset-proportional", problem, expectDrop = true)
    }

    @Test
    fun `variable-subset row with a negative extra term is dominated`() {
        // x+y<=2 implies x+y-z<=2 because z>=0 (the extra term's maximal activity is 0).
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(2, 0, 1, 1, 1, 2, -1)))
        checkPreserved("subset-negative-extra", problem, expectDrop = true)
    }

    @Test
    fun `a zero coefficient carries no support and never divides by zero`() {
        // 0·x + y <= 2 has a zero coeff on x: its genuine support is {y}, a strict subset of x+y<=5's.
        // y<=2 and x<=3 give x+y<=5, so the larger row drops. Before #653 the zero coeff stayed in the
        // support map and the dominance ratio check did cb % 0, crashing on the cargo challenge instance.
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 0, 1, 1), le(5, 0, 1, 1, 1)))
        val out = checkPreserved("zero-coeff-subset", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `variable-subset row is kept when extra activity exceeds the slack`() {
        // x+y<=2 does NOT imply x+y+z<=3 (z can be 3, sum 5 > 3), so nothing drops — soundness guard.
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(3, 0, 1, 1, 1, 2, 1)))
        checkPreserved("subset-not-dominated", problem, expectDrop = false)
    }

    private fun pos(v: Int) = Lit.make(v, true)

    private fun feasibleCountBools(problem: Problem): Int {
        val b = problem.numBoolVars
        var count = 0
        for (mask in 0 until (1 shl b)) {
            var a = Assumptions.None
            for (v in 0 until b) a = a.withBool(v, (mask shr v) and 1 == 1)
            if (problem.propagate(a) !is PropagationResult.Unsat) count++
        }
        return count
    }

    private fun checkPbPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val delta = Presolve.removeRedundantConstraints(problem)
        val out = problem.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(feasibleCountBools(problem), feasibleCountBools(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertTrue(delta.isEmpty, "$name: expected no change")
        }
        return out
    }

    @Test
    fun `dominated pseudo-boolean constraint is dropped`() {
        // 2a + b <= 2 implies 2a + b <= 3 (same weight vector); the tighter survives.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 3),
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
            ),
        )
        val out = checkPbPreserved("pb-dominated", problem, expectDrop = true)
        assertEquals(2, (out.factors.single() as PseudoBoolean).bound)
    }

    @Test
    fun `pseudo-boolean equality dominates the matching inequalities`() {
        // 2a + b = 2 implies 2a + b <= 3 and 2a + b >= 1 — both inequalities drop, the equality stays.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.EQ, 2),
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 3),
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.GE, 1),
            ),
        )
        val out = checkPbPreserved("pb-eq-dominates", problem, expectDrop = true)
        assertEquals(PbOp.EQ, (out.factors.single() as PseudoBoolean).op)
    }

    @Test
    fun `independent pseudo-boolean constraints are left untouched`() {
        // Different weight vectors ⇒ not comparable ⇒ no-op.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                PseudoBoolean(intArrayOf(2, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
                PseudoBoolean(intArrayOf(1, 2), intArrayOf(pos(0), pos(1)), PbOp.LE, 2),
            ),
        )
        checkPbPreserved("pb-independent", problem, expectDrop = false)
    }

    @Test
    fun `knapsack implied by an at-most-one cardinality clique is dropped`() {
        // AMO(b0,b1,b2) caps b0+b1+b2 at 1, so b0+b1+b2 <= 2 is redundant given the clique (#527).
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(0), pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(intArrayOf(1, 1, 1), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 2),
            ),
        )
        checkPbPreserved("clique-implies-knapsack", problem, expectDrop = true)
    }

    @Test
    fun `weighted knapsack implied by a partial clique cover is dropped`() {
        // 5*b0 + 2*b1 + 2*b2 <= 7 with AMO(b1,b2): clique-aware activity = 5 + max(2,2) = 7 <= 7, so the
        // knapsack holds for every clique-respecting assignment and is redundant.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(intArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 7),
            ),
        )
        checkPbPreserved("clique-partial-cover", problem, expectDrop = true)
    }

    @Test
    fun `knapsack implied by a binary-clause clique is dropped`() {
        // The clause ¬b0 ∨ ¬b1 is exactly AMO(b0,b1), so b0+b1 <= 1 is redundant given it.
        val problem = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                PseudoBoolean(intArrayOf(1, 1), intArrayOf(pos(0), pos(1)), PbOp.LE, 1),
            ),
        )
        checkPbPreserved("clique-from-clause", problem, expectDrop = true)
    }

    @Test
    fun `knapsack not implied by the clique is kept`() {
        // 5*b0 + 2*b1 + 2*b2 <= 6 with AMO(b1,b2): clique-aware activity = 5 + 2 = 7 > 6, so the
        // knapsack genuinely forbids (b0,b1) = (1,1) and must be kept — soundness guard.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(intArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6),
            ),
        )
        checkPbPreserved("clique-not-implied", problem, expectDrop = false)
    }

    @Test
    fun `vacuous all-different over disjoint domains is dropped`() {
        // x0 in [0,1], x1 in [2,3]: the domains can never collide, so all-different always holds and
        // the constraint is vacuously redundant — dropped, feasible set unchanged (#553).
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(2, 3)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val out = checkPreserved("vacuous-alldiff", problem, expectDrop = true)
        assertEquals(0, out.factors.size, "the vacuous global is removed")
    }

    @Test
    fun `all-different over overlapping domains is kept`() {
        // x0, x1 both in [0,1] can collide, so all-different is a real constraint — not dropped.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )
        checkPreserved("real-alldiff", problem, expectDrop = false)
    }
}
