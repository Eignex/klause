package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearSubSumAggregationTest {
    @Test
    fun `the scheduled pass should honor an already cancelled phase without charging work`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 2) },
            listOf(sumDef(), Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 3)),
        )
        val budget = PresolveBudget(1000)
        val context = PresolveContext(cancellation = budget.orSpent(Cancellation { true }))

        val delta = PresolvePass.AGGREGATE_SUB_SUMS.applySource(problem, context)

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `definition term visits should stop within one poll interval of the phase allowance`() {
        val width = 128
        val problem = Problem(
            0,
            width,
            Array(width) { IntDomain(0, 2) },
            listOf(Linear(IntArray(width) { 2 }, IntArray(width) { it }, LinearOp.EQ, 0)),
        )
        val budget = PresolveBudget(16)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertTrue(budget.spent() in 16L..79L)
    }

    @Test
    fun `dense unsuccessful candidates should consume only linear work and leave cheap reductions available`() {
        val count = 256
        val factors = List(count) { i ->
            Linear(intArrayOf(1, i + 2, i + 3), intArrayOf(i, count, count + 1), LinearOp.EQ, 0)
        } + listOf(
            Linear(intArrayOf(1, -1, -1), intArrayOf(count + 2, count + 3, count + 4), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1, 1), intArrayOf(count + 3, count + 4, count + 5), LinearOp.LE, 3),
        )
        val problem = Problem(0, count + 6, Array(count + 6) { IntDomain(0, 2) }, factors)
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(factors.lastIndex), delta.droppedIndices.toList())
        assertEquals(setOf(count + 2, count + 5), (delta.addedFactors.single() as Linear).vars.toSet())
        assertTrue(budget.spent() in 1L..(40L * factors.size))
    }

    @Test
    fun `cancellation during candidate scans should retain completed equivalent rewrites`() {
        val targets = List(64) {
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 3)
        }
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, listOf(sumDef()) + targets)
        val fullBudget = PresolveBudget(Long.MAX_VALUE)
        val complete = LinearSubSumAggregation.aggregateSubSums(problem, fullBudget.orSpent(Cancellation.Never))
        val budget = PresolveBudget(fullBudget.spent() - 64)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertEquals(targets.size, complete.addedFactors.size)
        assertTrue(delta.addedFactors.size in 1 until targets.size)
        assertTrue(budget.spent() in budget.allowance..(budget.allowance + 63))
        assertTrue(0 !in delta.droppedIndices)
        val remaining = problem.factors.filterIndexed { i, _ -> i !in delta.droppedIndices } + delta.addedFactors
        assertEquals(
            feasible(problem.factors, LongArray(4), LongArray(4) { 2 }),
            feasible(remaining.toTypedArray(), LongArray(4), LongArray(4) { 2 }),
        )
    }

    @Test
    fun `a declared sub-sum should fold into a declared target row`() {
        val equality = sumDef()
        val inequality = Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 10)
        val definition = object : Factor by equality, Propagator by equality.propagatorProjection() {}
        val target = object : Factor by inequality, Propagator by inequality.propagatorProjection() {}

        val out = run(definition, target)

        val reduced = theInequality(out)
        assertEquals(setOf(0, 3), reduced.vars.toSet())
        assertEquals(10L, reduced.integerConstants?.bound)
    }

    private fun sumDef() = Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)

    private fun linears(p: Problem) = p.factors.filterIsInstance<Linear>()

    private fun theInequality(p: Problem): Linear = linears(p).single { it.op == LinearOp.LE }

    private fun run(vararg factors: Factor): Problem {
        val p = Problem(0, 4, Array(4) { IntDomain(0, 8) }, factors.toList())
        return p.bake().withPassDelta(Presolve.aggregateSubSums(p), BakeConfig.NONE)
    }

    @Test
    fun `a partial sub-sum should be left untouched`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 5)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `an unevenly scaled sub-sum should be left untouched`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(2, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 9)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `a zero-coefficient partner should not count as a sub-sum partner`() {
        // Multiplier matching must not take `c % 0` on the zero term.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(
                Linear(intArrayOf(1, 0, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 5),
            ),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `a used definition should stay valid through later dependencies and repeated passes`() {
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
    fun `unrepresentable substitutions should retain their original constraints`() {
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
