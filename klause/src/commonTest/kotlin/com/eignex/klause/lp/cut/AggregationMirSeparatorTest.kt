package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [AggregationMirSeparator] {0,½} cuts must be valid — satisfied by every integer-feasible point — and
 * must actually separate the fractional LP point they were derived from. Validity is checked by brute
 * force against the full integer box, since an invalid cut would silently remove real solutions.
 */
class AggregationMirSeparatorTest {

    private fun separate(p: Problem, obj: LinearObjective): Pair<List<Cut>, LpRelaxation>? {
        val session = PropagationSession(p)
        val r = CpToLpRelaxation(p, obj).build(session)
        if (r.model.n == 0) return null
        val sol = RevisedSimplex(r.model).solve() ?: return null
        val cuts = AggregationMirSeparator().separate(CutContext(p, r, sol.primal, session))
        return cuts to r
    }

    /** Σ coeffs·x ⟨op⟩ bound holds for the integer assignment [x]. */
    private fun satisfies(f: Linear, x: IntArray): Boolean {
        var s = 0L
        for (i in f.vars.indices) s += checkNotNull(f.integerConstants).coeffs[i] * x[f.vars[i]]
        return when (f.op) {
            LinearOp.LE -> s <= checkNotNull(f.integerConstants).bound
            LinearOp.GE -> s >= checkNotNull(f.integerConstants).bound
            LinearOp.EQ -> s == checkNotNull(f.integerConstants).bound
            else -> true
        }
    }

    private fun cutHolds(cut: Cut, rel: LpRelaxation, x: IntArray): Boolean {
        var s = 0L
        for (k in cut.cols.indices) s += cut.coeffs[k] * x[rel.colVarId[cut.cols[k]]]
        return s <= cut.rhs // separator emits only LE cuts
    }

    /** Brute-force every integer point of the box, asserting each emitted cut holds at every point that
     *  satisfies all Linear factors. */
    private fun assertCutsValid(p: Problem, cuts: List<Cut>, rel: LpRelaxation) {
        val lins = p.factors.filterIsInstance<Linear>()
        val x = IntArray(p.numIntVars)
        fun recurse(v: Int) {
            if (v == p.numIntVars) {
                if (lins.all { satisfies(it, x) }) {
                    for (cut in cuts) {
                        assertTrue(
                            cutHolds(cut, rel, x),
                            "cut ${cut.cols.toList()}·x ≤ ${cut.rhs} cuts off feasible ${x.toList()}",
                        )
                    }
                }
                return
            }
            val d = p.finiteIntDomain(v)
            for (value in d.min..d.max) {
                x[v] = value.toInt()
                recurse(v + 1)
            }
        }
        recurse(0)
    }

    @Test
    fun `single row half cut separates and stays valid`() {
        // 2x + 2y ≤ 3 over [0,3]: the LP maximum of x+y is 1.5 (x=y=0.75), but every integer point has
        // x+y ≤ 1. The {0,½} cut ⌊2/2⌋x + ⌊2/2⌋y ≤ ⌊3/2⌋ = (x+y ≤ 1) separates it.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L))
        val (cuts, rel) = separate(p, obj) ?: error("LP should solve")
        assertTrue(cuts.isNotEmpty(), "expected a separating {0,½} cut")
        assertCutsValid(p, cuts, rel)
    }

    @Test
    fun `a column the model leaves open below yields no half cut`() {
        // The same separating row, but x's lower endpoint is one the finite lane invented. The rounding
        // needs the shift y = x − lo to be nonnegative and the cut is published globally, so a shift by
        // an invented endpoint cannot be taken.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3)),
            openIntLo = booleanArrayOf(true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L, -1L))
        val (cuts, _) = separate(p, obj) ?: error("LP should solve")
        assertTrue(cuts.isEmpty(), "an invented lower endpoint must not become the aggregation shift")
    }

}
