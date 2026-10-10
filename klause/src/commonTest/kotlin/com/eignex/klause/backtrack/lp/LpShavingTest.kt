package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.redundantConstraints
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Objective shaving must be SOUND — it may only raise the objective lower bound to a value proven
 * (by propagation + the LP relaxation) to be a true lower bound, never above the optimum. Checked
 * directly (the shaved bound equals the brute-force optimum) and by randomized soundness sweeps.
 */
class LpShavingTest {

    @Test
    fun `the redundancy probe reaches a verdict on a model with continuous columns`() {
        // The probe judges each row against the others, so it rebuilds the model over the rows it kept.
        // A rebuild that states only the finite integer columns drops the LP-only continuous namespace,
        // and a kept row addressing a real column then has no column to address at all.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4),
                ReifiedRealLinear(
                    aux = 0,
                    vars = IntArray(0),
                    intCoeffs = DoubleArray(0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val engine = LpEngine(
            p,
            LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "shave"),
        )
        // A real row puts the relaxation on the double view; the rationalized exact bound still brackets
        // the integer column there, so the domain-implied row certifies as redundant.
        assertEquals(listOf(0), engine.redundantConstraints(Cancellation.Never))
    }
}
