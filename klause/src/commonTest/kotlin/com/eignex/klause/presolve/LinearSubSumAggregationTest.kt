package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Common linear sub-sum extraction ([Presolve.aggregateSubSums]). Shape tests check a defined sub-sum
 * folds into one variable; the enumeration test drives the whole feasible set to guard the coefficient
 * matching — the pass must be solution-set preserving.
 */
class LinearSubSumAggregationTest {

    // s = x + y, encoded as s − x − y = 0 with s the lowest-id (and pivot) variable.
    private fun sumDef() = Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)

    private fun linears(p: Problem) = p.factors.filterIsInstance<Linear>()

    private fun theInequality(p: Problem): Linear = linears(p).single { it.op == LinearOp.LE }

    private fun coeffOf(l: Linear, v: Int): Long = l.coeffs[l.vars.indexOf(v)]

    private fun run(vararg factors: Factor): Problem {
        val p = Problem(0, 4, Array(4) { IntDomain(0, 8) }, factors.toList())
        return p.withPassDelta(Presolve.aggregateSubSums(p), BakeConfig.NONE)
    }

    @Test
    fun `a defined sub-sum folds into its single variable`() {
        // x + y + w ≤ 10 with s = x + y becomes s + w ≤ 10.
        val out = run(sumDef(), Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 10))
        val ineq = theInequality(out)
        assertEquals(setOf(0, 3), ineq.vars.toSet(), "the partner terms collapse into s")
        assertEquals(10L, ineq.bound)
        assertEquals(1L, coeffOf(ineq, 0))
    }

    @Test
    fun `a scaled occurrence folds with its multiplier`() {
        // 2x + 2y + w ≤ 20 folds to 2s + w ≤ 20.
        val out = run(sumDef(), Linear(intArrayOf(2, 2, 1), intArrayOf(1, 2, 3), LinearOp.LE, 20))
        val ineq = theInequality(out)
        assertEquals(setOf(0, 3), ineq.vars.toSet())
        assertEquals(2L, coeffOf(ineq, 0), "the multiplier carries onto s")
        assertEquals(20L, ineq.bound)
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
    fun `a two-term equality is not a sub-sum definition`() {
        // s − x = 0 is a plain alias; affine elimination owns it, not this pass.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 5),
            ),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty, "a two-term equality defines no multi-term sub-sum")
    }

    @Test
    fun `the fold preserves the feasible set`() {
        // Per-variable ranges chosen so the sub-sum and the multiplier are genuinely exercised.
        val mins = longArrayOf(0, 0, 0, 0)
        val maxs = longArrayOf(6, 3, 3, 3)
        val domains = Array(4) { IntDomain(mins[it], maxs[it]) }
        val factors = listOf(
            sumDef(),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 5),
            Linear(intArrayOf(2, 2, -1), intArrayOf(1, 2, 3), LinearOp.GE, 1),
        )
        val p = Problem(0, 4, domains, factors)
        val out = p.withPassDelta(Presolve.aggregateSubSums(p), BakeConfig.NONE)
        assertTrue(out.factors.size < p.factors.size || linears(out).any { it.vars.contains(0) }, "a fold occurred")
        assertEquals(
            feasible(p.factors, mins, maxs),
            feasible(out.factors, mins, maxs),
            "aggregation changed the feasible set",
        )
    }

    private fun feasible(factors: Array<Factor>, mins: LongArray, maxs: LongArray): Set<List<Long>> {
        val out = HashSet<List<Long>>()
        val assign = mins.copyOf()
        fun holds(): Boolean = factors.all { f ->
            f as Linear
            var sum = 0L
            for (j in f.vars.indices) sum += f.coeffs[j] * assign[f.vars[j]]
            when (f.op) {
                LinearOp.LE -> sum <= f.bound
                LinearOp.EQ -> sum == f.bound
                LinearOp.NE -> sum != f.bound
                LinearOp.GE -> sum >= f.bound
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
