package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.numericalSourceTolerance
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class LpHarvestToleranceTest(
    private val harvest: Boolean,
    private val reading: String,
    private val numerical: Boolean,
    private val tolerance: SourceTolerance,
    private val gap: Double,
    private val infeasible: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "harvest={0} {1}")
        fun variants(): List<Array<Any>> = listOf(false, true).flatMap { harvest ->
            val absolute = SourceTolerance(SemanticRational.ONE)
            listOf(
                arrayOf<Any>(harvest, "absolute overlap", true, absolute, 1.0, false),
                arrayOf<Any>(harvest, "default overlap", true, numericalSourceTolerance, 1.5e-7, false),
                arrayOf<Any>(harvest, "relative overlap", true,
                    SourceTolerance(relative = SemanticRational.ONE), 1.0, false),
                arrayOf<Any>(harvest, "separated bands", true, absolute, 4.0, true),
                arrayOf<Any>(harvest, "zero tolerance", true, SourceTolerance(), 1.0, true),
                arrayOf<Any>(harvest, "exact deductions", false, absolute, 1.0, true),
            )
        }

        @JvmStatic
        @BeforeClass
        fun initializeSolver() {
            val source = Problem(
                0, 0, emptyArray(), arrayOf(
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.5e-7),
                ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(4.0),
            )
            lpRootInfeasibleReporting(source, LinearObjective(), LpPlan(bounding = true))
            lpHarvestReporting(source.bake(), LinearObjective(), LpPlan(bounding = true))
        }
    }

    @Test
    fun `root LP refutation should honor the selected source tolerance`() {
        val source = Problem(
            0, 0, emptyArray(), arrayOf(
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, gap),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(4.0),
            semantics = SourceSemantics(
                SourceFormat.IR, floats = FloatSemantics.Continuous,
                feasibilityTolerance = tolerance,
            ).resolve(),
        )

        val refuted = if (harvest) {
            lpHarvestReporting(source.bake(), LinearObjective(), LpPlan(bounding = true), numericalLp = numerical)
                .report.rootInfeasible
        } else {
            lpRootInfeasibleReporting(source, LinearObjective(), LpPlan(bounding = true), numericalLp = numerical)
                .infeasible
        }

        assertEquals(infeasible, refuted, reading)
    }
}
