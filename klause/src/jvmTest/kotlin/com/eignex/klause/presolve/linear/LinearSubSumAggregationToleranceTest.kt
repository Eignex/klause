package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LinearSubSumAggregationToleranceTest(
    private val pass: String,
    private val reading: String,
    private val policy: SourceTolerance,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun variants(): List<Array<Any>> = listOf("aggregate", "sparsify").flatMap { pass ->
            listOf(
                arrayOf(pass, "absolute", SourceTolerance(SemanticRational.ONE)),
                arrayOf(pass, "relative", SourceTolerance(relative = SemanticRational.ONE)),
            )
        }

        @JvmStatic
        @BeforeClass
        fun initializePresolve() {
            val problem = Problem(
                0, 3, Array(3) { IntDomain(-1, 2) }, listOf(
                    Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                    Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                ),
            )
            Presolver.runSource(problem, PresolveConfig.parse("off,+aggregate"))
            verifyCapturedRealTolerance(problem, Sample(booleanArrayOf(), longArrayOf(-1, 0, 0)),
                SourceTolerance(SemanticRational.ONE), ExecutionControl.Never)
        }
    }

    @Test
    fun `row replacement should preserve a source tolerance witness`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(-1, 2) }, listOf(
                Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            ), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve(),
        )
        val witness = Sample(booleanArrayOf(), longArrayOf(-1, 0, 0))
        assertTrue(
            verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never) is Verification.Accepted,
        )

        val reduced = Presolver.runSource(problem, PresolveConfig.parse("off,+$pass")).problem

        assertTrue(
            verifyCapturedRealTolerance(reduced, witness, policy, ExecutionControl.Never) is Verification.Accepted,
            reading,
        )
    }

    @Test
    fun `typed integer rows should still contract in a model with real coordinates`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(-1, 2) }, listOf(
                Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(
                SourceFormat.IR, floats = FloatSemantics.Continuous, feasibilityTolerance = policy,
            ).resolve(),
        )

        val reduced = Presolver.runSource(problem, PresolveConfig.parse("off,+$pass")).problem

        val target = reduced.factors.filterIsInstance<Linear>().single { it.vars.size == 1 }
        assertEquals(listOf(0), target.vars.toList(), reading)
    }
}
