package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedundantConstraintsTest {
    private fun isFeasible(problem: Problem, ints: LongArray): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun feasibleCount(problem: Problem): Int {
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var count = 0
        while (true) {
            if (isFeasible(problem, ints.copyOf())) count++
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun checkPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val baked = problem.bake()
        val delta = Presolve.removeRedundantConstraints(baked)
        val out = baked.withPassDelta(delta, BakeConfig.NONE)
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
    fun `a declared knapsack implied by a clique should be dropped without finite domains`() {
        val source = PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 7)
        val model = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(1), pos(2)), 0, 1), object : Factor by source {}),
        )

        val delta = Presolve.removeRedundantSourceConstraints(model, Cancellation.Never)

        assertEquals(listOf(1), delta.droppedIndices.toList())
    }

    @Test
    fun `a clique cover should count every repeated literal coefficient`() {
        val model = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(0), pos(1)), 0, 1),
                PseudoBoolean(longArrayOf(2, 2, 1), intArrayOf(pos(0), pos(0), pos(1)), PbOp.LE, 3),
            ),
        )

        val delta = Presolve.removeRedundantSourceConstraints(model, Cancellation.Never)

        assertTrue(1 !in delta.droppedIndices)
    }

    @Test
    fun `negated-equivalent inequalities should be deduplicated`() {
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
        assertTrue(out.factors.single() in factorsBefore)
    }

    @Test
    fun `proportional rows should be deduplicated via GCD normalization`() {
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 1, 1, 1), le(4, 0, 2, 1, 2)))
        val out = checkPreserved("proportional-dup", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `permuted proportional rows should retain the tighter source constraint`() {
        val problems = listOf(
            Problem(
                0,
                3,
                dom(3, 3),
                listOf(
                    Linear(intArrayOf(2, -4, 6), intArrayOf(2, 0, 1), LinearOp.LE, 5),
                    Linear(intArrayOf(-6, -2, 4), intArrayOf(1, 2, 0), LinearOp.GE, -7),
                ),
            ),
            Problem(
                3,
                0,
                emptyArray(),
                listOf(
                    PseudoBoolean(longArrayOf(2, 4, 6), intArrayOf(pos(0), Lit.make(1, false), pos(2)), PbOp.LE, 5),
                    PseudoBoolean(longArrayOf(6, 2, 4), intArrayOf(pos(2), pos(0), Lit.make(1, false)), PbOp.LE, 7),
                ),
            ),
        )

        for (problem in problems) {
            val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)

            assertEquals(listOf(1), delta.droppedIndices.toList())
        }
    }

    @Test
    fun `a subset-dominated source row should be dropped`() {
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(5, 0, 1, 1, 1, 2, 1)))

        val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)

        assertEquals(listOf(1), delta.droppedIndices.toList())
    }

    @Test
    fun `a row whose extra term is open should not be dominated`() {
        val openHi = Bits(3).also { it.set(2) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(3, 3, 0),
                null,
                openHi,
            ),
            factors = arrayOf(le(2, 0, 1, 1, 1), le(5, 0, 1, 1, 1, 2, 1)),
        )

        val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)

        assertTrue(delta.droppedIndices.isEmpty())
    }

    @Test
    fun `a zero coefficient should carry no support`() {
        // A zero coefficient in the support map would make the dominance ratio check divide by zero.
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 0, 1, 1), le(5, 0, 1, 1, 1)))
        val out = checkPreserved("zero-coeff-subset", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `a variable-subset row should be kept when extra activity exceeds the slack`() {
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
        val baked = problem.bake()
        val delta = Presolve.removeRedundantConstraints(baked)
        val out = baked.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(feasibleCountBools(problem), feasibleCountBools(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertTrue(delta.isEmpty, "$name: expected no change")
        }
        return out
    }

    @Test
    fun `a weighted knapsack implied by a partial clique cover should be dropped`() {
        // Clique-aware activity is 5 + max(2, 2) = 7.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 7L),
            ),
        )
        checkPbPreserved("clique-partial-cover", problem, expectDrop = true)
    }

    @Test
    fun `a knapsack not implied by the clique should be kept`() {
        // Clique-aware activity is 5 + 2 = 7 > 6.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6L),
            ),
        )
        checkPbPreserved("clique-not-implied", problem, expectDrop = false)
    }

    @Test
    fun `an all-different over overlapping domains should be kept`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )
        checkPreserved("real-alldiff", problem, expectDrop = false)
    }
}
