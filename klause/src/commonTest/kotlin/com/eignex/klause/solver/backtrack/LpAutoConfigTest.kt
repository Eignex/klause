package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #245: structural auto-configuration of the LP-relaxation family. */
class LpAutoConfigTest {

    private fun problem(vararg factors: Factor, intVars: Int = 3): Problem =
        Problem(0, intVars, Array(intVars) { IntDomain(0, 5) }, arrayOf(*factors))

    @Test
    fun `linear structure enables lp bounding only`() {
        val p = problem(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpBounding)
        assertFalse(r.lpCuts)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `all-different enables bounding and cuts and lagrangian`() {
        val p = problem(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.lpBounding)
        assertTrue(r.lpCuts)
        assertTrue(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `global cardinality enables cuts but not lagrangian`() {
        val gcc = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(0, 1, 2),
            countLow = intArrayOf(0, 0, 0),
            countHigh = intArrayOf(1, 1, 1),
            closed = true,
        )
        val r = LpAutoConfig.recommend(problem(gcc))
        assertTrue(r.lpBounding)
        assertTrue(r.lpCuts)
        assertFalse(r.lagrangian)
    }

    @Test
    fun `cumulative enables energetic reasoning only`() {
        val p = problem(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1))
        val r = LpAutoConfig.recommend(p)
        assertTrue(r.energeticReasoning)
        assertFalse(r.lpBounding)
        assertFalse(r.lpCuts)
    }

    @Test
    fun `circuit enables circuit cuts and bounding`() {
        val r = LpAutoConfig.recommend(problem(Circuit(intArrayOf(0, 1, 2))))
        assertTrue(r.lpBounding)
        assertTrue(r.lpCircuit)
    }

    @Test
    fun `constant-array element enables element hull but a variable array does not`() {
        val constArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0)),
        )
        assertTrue(constArr.lpBounding)
        assertTrue(constArr.lpElement)

        val varArr = LpAutoConfig.recommend(
            problem(Element(idx = 0, result = 1, arr = intArrayOf(2), arrIsVars = true, indexOffset = 0)),
        )
        assertFalse(varArr.lpElement)
    }

    @Test
    fun `table enables table hull and bounding`() {
        val r = LpAutoConfig.recommend(problem(Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 0, 1, 1))))
        assertTrue(r.lpBounding)
        assertTrue(r.lpTable)
    }

    @Test
    fun `pseudo-boolean enables cover cuts`() {
        val pb = PseudoBoolean(intArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, true)), PbOp.LE, 4)
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>(pb)))
        assertTrue(r.lpCuts)
        assertTrue(r.lpBounding)
    }

    @Test
    fun `pure boolean problem enables nothing`() {
        val r = LpAutoConfig.recommend(Problem(2, 0, emptyArray(), arrayOf<Factor>()))
        assertFalse(r.lpBounding)
        assertFalse(r.lpCuts)
        assertFalse(r.lagrangian)
        assertFalse(r.energeticReasoning)
    }

    @Test
    fun `caller-set flags are never turned off`() {
        // A Cumulative-only problem would not enable lpBounding, but an explicit base setting stays.
        val p = problem(Cumulative(intArrayOf(0, 1, 2), intArrayOf(3, 3, 3), intArrayOf(1, 1, 1), capacity = 1))
        val r = LpAutoConfig.recommend(p, BacktrackParams(lpBounding = true, lpGomory = false))
        assertTrue(r.lpBounding)
        assertFalse(r.lpGomory) // unrelated base settings are preserved
        assertTrue(r.energeticReasoning)
    }

    @Test
    fun `recommended config preserves the optimum`() {
        // Triangle covering (see LpBoundingTest): optimum 3; auto-config must not change it.
        val p = problem(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val auto = LpAutoConfig.recommend(p, BacktrackParams(randomSeed = 1L))
        assertTrue(auto.lpBounding)
        val result = BacktrackSolver(p).minimize(obj, auto)
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }
}
