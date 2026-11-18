package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.integerTableauCuts
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class CpToLpRelaxationCutToleranceTest(private val mir: Boolean, private val relaxed: Boolean) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), listOf(
                Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 3),
            ))
            val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(-1)))
                .build(RootDomains(problem))
            RevisedSimplex(base.model).use { solver ->
                val result = assertNotNull(solver.solve())
                val cut = integerTableauCuts(base.model, result.basis, result.primal, 1, mir = false).first()
                val source = assertNotNull(SourceCut.fromCut(cut, base).orNull())
                source.toCut(assertNotNull(base.sourceMap))
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "mir={0} relaxed={1}")
        fun variants(): List<Array<Boolean>> = listOf(false, true).flatMap { mir ->
            listOf(false, true).map { arrayOf(mir, it) }
        }
    }

    @Test
    fun `retained rounding cuts should preserve a source tolerance objective`() {
        val policy = if (relaxed) SourceTolerance(SemanticRational.ONE) else SourceTolerance()
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 1) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 1),
            ), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve(),
        )
        val witness = Sample(booleanArrayOf(), if (relaxed) longArrayOf(1, 1, 1) else longArrayOf(1, 0, 0))
        assertTrue(
            verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never) is Verification.Accepted,
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        val relaxer = CpToLpRelaxation(problem, objective)
        val base = relaxer.build(RootDomains(problem))
        val cuts = RevisedSimplex(base.model).use { solver ->
            val result = assertNotNull(solver.solve())
            integerTableauCuts(base.model, result.basis, result.primal, 8, mir)
        }
        assertTrue(cuts.isNotEmpty())
        val sourceCut = assertNotNull(SourceCut.fromCut(cuts.first(), base).orNull())
        val retained = assertNotNull(sourceCut.toCut(assertNotNull(base.sourceMap)).orNull())
        val tightened = relaxer.build(RootDomains(problem), listOf(retained))

        val bound = RevisedSimplex(tightened.model).use { solver ->
            val result = assertNotNull(solver.solve())
            assertNotNull(FloatDeductionCertificate.of(tightened.model, result.duals,
                slack = LpBoundSlack(if (relaxed) 1.0 else 0.0, 0.0))).lowerBound
        }

        val value = if (relaxed) -3.0 else -1.0
        assertTrue(bound <= value, "bound $bound excludes source objective $value")
    }
}
