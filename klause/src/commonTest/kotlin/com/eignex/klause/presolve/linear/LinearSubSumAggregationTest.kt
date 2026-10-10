package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearSubSumAggregationTest {

    @Test
    fun `a declared sub-sum folds into a declared target row`() {
        val equality = sumDef()
        val inequality = Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 10)
        val definition = object : Factor by equality, Propagator by equality.propagatorProjection() {}
        val target = object : Factor by inequality, Propagator by inequality.propagatorProjection() {}

        val out = run(definition, target)

        val reduced = theInequality(out)
        assertEquals(setOf(0, 3), reduced.vars.toSet())
        assertEquals(10L, reduced.integerConstants?.bound)
    }

    // s = x + y, encoded as s − x − y = 0 with s the lowest-id (and pivot) variable.
    private fun sumDef() = Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)

    private fun linears(p: Problem) = p.factors.filterIsInstance<Linear>()

    private fun theInequality(p: Problem): Linear = linears(p).single { it.op == LinearOp.LE }

    private fun run(vararg factors: Factor): Problem {
        val p = Problem(0, 4, Array(4) { IntDomain(0, 8) }, factors.toList())
        return p.bake().withPassDelta(Presolve.aggregateSubSums(p), BakeConfig.NONE)
    }

    @Test
    fun `a partial sub-sum is left untouched`() {
        // x + w ≤ 5 contains only x, not the whole {x, y} sub-sum.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 5)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty, "an incomplete sub-sum offers no exact fold")
    }

    @Test
    fun `an unevenly scaled sub-sum is left untouched`() {
        // 2x + y contains x and y at different multiples of their form coefficients.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(2, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 9)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty, "no single multiplier covers the sub-sum")
    }

    @Test
    fun `a zero-coefficient partner does not divide by zero`() {
        // `s + 0·x − w = 0` keeps its zero term (coalescing preserves it). Multiplier matching must not
        // take `c % 0` on that term: a zero coefficient counts as no partner, leaving a single-partner
        // "definition" that is no sub-sum — so the pass declines cleanly rather than throwing.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(
                Linear(intArrayOf(1, 0, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 5),
            ),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty, "a zero-coefficient term is not a sub-sum partner")
    }

    @Test
    fun `a used definition stays valid through later dependencies and repeated passes`() {
        val definition = Linear(intArrayOf(1, -1, -1), intArrayOf(1, 2, 3), LinearOp.EQ, 0)
        val target = Linear(intArrayOf(2, 2, 1), intArrayOf(2, 3, 4), LinearOp.LE, 3)
        val dependency = Linear(intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)
        for (factors in listOf(listOf(definition, target, dependency), listOf(dependency, target, definition))) {
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val expected = feasible(problem.factors, LongArray(5), LongArray(5) { 2 })
            val delta = Presolve.aggregateSubSums(problem)

            var output = problem.bake().withPassDelta(delta, BakeConfig.NONE)

            assertTrue(delta.addedFactors.isNotEmpty())
            assertEquals(expected, feasible(output.factors, LongArray(5), LongArray(5) { 2 }))
            repeat(3) {
                output = output.bake().withPassDelta(Presolve.aggregateSubSums(output), BakeConfig.NONE)
                assertEquals(expected, feasible(output.factors, LongArray(5), LongArray(5) { 2 }))
            }
        }
    }

    @Test
    fun `unrepresentable substitutions retain their original constraints`() {
        val cases = listOf(
            Linear(longArrayOf(1, Long.MIN_VALUE, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0) to
                Linear(longArrayOf(Long.MIN_VALUE, -1), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(longArrayOf(-1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, Long.MIN_VALUE) to
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0) to
                Linear(longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 2) to
                Linear(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 1) to
                Linear(longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            sumDef() to Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, Long.MIN_VALUE),
            sumDef() to Linear(longArrayOf(Long.MAX_VALUE, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
        )
        for ((definition, target) in cases) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(definition, target))

            val delta = Presolve.aggregateSubSums(problem)

            assertTrue(delta.isEmpty)
        }
    }

    private fun feasible(factors: Array<Factor>, mins: LongArray, maxs: LongArray): Set<List<Long>> {
        val out = HashSet<List<Long>>()
        val assign = mins.copyOf()
        fun holds(): Boolean = factors.all { f ->
            f as Linear
            var sum = BIG_ZERO
            for (j in f.vars.indices) {
                sum += bigIntOf(checkNotNull(f.integerConstants).coeffs[j]) *
                    bigIntOf(assign[f.vars[j]])
            }
            val bound = bigIntOf(checkNotNull(f.integerConstants).bound)
            when (f.op) {
                LinearOp.LE -> sum <= bound
                LinearOp.EQ -> sum == bound
                LinearOp.NE -> sum != bound
                LinearOp.GE -> sum >= bound
            }
        }
        fun recurse(i: Int) {
            if (i == assign.size) {
                if (holds()) out.add(assign.toList())
                return
            }
            var v = mins[i]
            while (v <= maxs[i]) {
                assign[i] = v
                recurse(i + 1)
                v++
            }
        }
        recurse(0)
        return out
    }
}
