package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.floatFarkasRay
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(Parameterized::class)
class CpToLpRelaxationGatedToleranceTest(private val relaxed: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "relaxed={0}")
        fun variants(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() = LinearSourceRowToleranceTest.initializeRelaxation()
    }

    @Test
    fun `gated refutation should respect the source activity band`() {
        val rate = if (relaxed) SemanticRational.of(BIG_ONE, bigIntOf(4)) else SemanticRational.ZERO
        val policy = SourceTolerance(relative = rate)
        val problem = Problem(1, 0, emptyArray(), listOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0),
                LinearOp.LE, 0.0),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 0.5),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
                feasibilityTolerance = policy).resolve())
        val witness = Sample(booleanArrayOf(true), longArrayOf(), doubleArrayOf(0.75, 0.5))
        val verification = verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never)
        assertEquals(relaxed, verification is Verification.Accepted)
        val base = assertNotNull(CpToLpRelaxation(problem, null, realResidual = true).buildGatedResidual())
        val atom = base.gatedRows[base.gatedRows.indices.single { base.gatedWhenTrue[it] }]
        val complement = base.gatedRows[base.gatedRows.indices.single { !base.gatedWhenTrue[it] }]
        val lower = (0 until base.model.m).single { it !in base.gatedRows }
        val original = assertNotNull(base.model.authoritativeModel())
        val source = original.copy(columns = List(original.numVars) { column ->
            original.column(column).let {
                if (column == base.model.slackCol(complement)) it.copy(bounds = ExactLpBounds()) else it
            }
        })
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val ray = DoubleArray(model.m).also {
            it[lower] = 1.0
            it[atom] = 1.0
        }

        val certificate = floatFarkasRay(model, ray, slack = LpBoundSlack(0.0, if (relaxed) 0.25 else 0.0))

        if (relaxed) assertNull(certificate) else assertNotNull(certificate)
    }
}
