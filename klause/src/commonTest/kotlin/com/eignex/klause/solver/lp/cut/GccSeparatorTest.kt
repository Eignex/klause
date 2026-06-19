package com.eignex.klause.solver.lp.cut

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.GlobalCardinality
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.RevisedSimplex
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #248↔#22: GlobalCardinality sum cuts — value-multiplicity generalization of the AllDifferent Hall cut. */
class GccSeparatorTest {

    /**
     * Separate the GCC cut at the LP vertex chosen by [coef]: with no GCC rows in the relaxation each
     * column seats at the cost-favoured bound, so `coef = -1` maximizes `Σx` (violates the upper cut)
     * and `coef = +1` minimizes it (violates the lower cut).
     */
    private fun cuts(factor: GlobalCardinality, hi: Int, coef: Long): List<Cut> {
        val n = factor.xs.size
        val p = Problem(0, n, Array(n) { IntDomain(0, hi) }, arrayOf<Factor>(factor))
        val session = PropagationSession(p)
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = LongArray(n) { coef }))
            .build(session)
        val sol = requireNotNull(RevisedSimplex(r.model).solve())
        return GccSeparator().separate(CutContext(p, r, sol.primal, PropagationSession(p)))
    }

    private fun lower(factor: GlobalCardinality, hi: Int) = cuts(factor, hi, 1L).single { it.rel == Relation.GE }.rhs
    private fun upper(factor: GlobalCardinality, hi: Int) = cuts(factor, hi, -1L).single { it.rel == Relation.LE }.rhs

    @Test
    fun `cut bounds the sum by the occurrence-capped distribution`() {
        // 6 vars over cover {0,1,2}, each value used in [1,3] times: forced 0+1+2 = 3, then 3 free
        // slots. Each value's residual capacity is high−low = 2, so min fills value 0 (×2) then value 1
        // (×1) = 3+1 = 4; max fills value 2 (×2) then value 1 (×1) = 3+4+1 = 8.
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2, 3, 4, 5),
            cover = intArrayOf(0, 1, 2),
            countLow = intArrayOf(1, 1, 1),
            countHigh = intArrayOf(3, 3, 3),
            closed = true,
        )
        assertEquals(4L, lower(gcc, 2))
        assertEquals(8L, upper(gcc, 2))
    }

    @Test
    fun `all caps one reduces to the hall sum`() {
        // cover {0,1,2}, each used at most once over 3 vars ⇒ AllDifferent over {0,1,2}: Σx in [3,3].
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(0, 1, 2),
            countLow = intArrayOf(0, 0, 0),
            countHigh = intArrayOf(1, 1, 1),
            closed = true,
        )
        assertEquals(3L, lower(gcc, 2))
        assertEquals(3L, upper(gcc, 2))
    }

    @Test
    fun `cut excludes no feasible closed distribution`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2, 3),
            cover = intArrayOf(0, 1, 2),
            countLow = intArrayOf(1, 0, 0),
            countHigh = intArrayOf(2, 2, 2),
            closed = true,
        )
        val lo = lower(gcc, 2)
        val hi = upper(gcc, 2)
        // Enumerate every closed assignment honouring the caps; Σ must lie within [lo, hi].
        for (a in 0..2) {
            for (b in 0..2) {
                for (c in 0..2) {
                    for (d in 0..2) {
                        val cnt = IntArray(3)
                        cnt[a]++
                        cnt[b]++
                        cnt[c]++
                        cnt[d]++
                        if (cnt[0] < 1 || cnt[0] > 2 || cnt[1] > 2 || cnt[2] > 2) continue
                        val s = (a + b + c + d).toLong()
                        assertTrue(s in lo..hi, "($a,$b,$c,$d): $s not in [$lo,$hi]")
                    }
                }
            }
        }
    }

    @Test
    fun `open gcc is not separated`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(1, 2),
            countLow = intArrayOf(0, 0),
            countHigh = intArrayOf(2, 2),
            closed = false,
        )
        assertTrue(cuts(gcc, 2, -1L).isEmpty())
        assertTrue(cuts(gcc, 2, 1L).isEmpty())
    }
}
