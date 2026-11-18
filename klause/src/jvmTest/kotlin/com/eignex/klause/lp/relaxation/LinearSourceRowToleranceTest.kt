package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.shl
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LinearSourceRowToleranceTest(
    private val form: String, private val cached: Boolean, private val relaxed: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} cached={1} relaxed={2}")
        fun variants(): List<Array<Any>> = listOf("linear", "exact", "wide exact").flatMap { form ->
            listOf(false, true).flatMap { cached -> listOf(false, true).map { arrayOf(form, cached, it) } }
        }

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), listOf(
                Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 3),
            ))
            val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(-1)))
                .build(RootDomains(problem))
            RevisedSimplex(base.model).use { it.solve() }
        }
    }

    @Test
    fun `linear source pricing should cover its accepted relative activity`() {
        val relative = if (relaxed) SemanticRational.of(BIG_ONE, bigIntOf(2)) else SemanticRational.ZERO
        val policy = SourceTolerance(relative = relative)
        val pairs = listOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(0, 2))
        val scale = if (form == "wide exact") BigFraction.of(BIG_ONE shl 64, BIG_ONE) else BigFraction.ONE
        val factors: List<Factor> = pairs.map { variables ->
            if (form == "linear") Linear(intArrayOf(1, 1), variables, LinearOp.LE, 1)
            else ExactLinear.of(variables.map(Term::ofIntVar).toIntArray(), listOf(scale, scale), LinearOp.LE, scale)
        }
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, factors,
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve())
        val witness = Sample(booleanArrayOf(), if (relaxed) longArrayOf(1, 1, 1) else longArrayOf(0, 0, 1))
        assertTrue(
            verifyCapturedRealTolerance(problem, witness, policy, ExecutionControl.Never) is Verification.Accepted,
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        val selection = if (cached) FactorProjectionProviders.Default.prepare(problem) else null
        val consumer = CpToLpRelaxation(problem, objective, projections = selection,
            reuseSourceTemplates = cached, reuseTemplateMetadata = cached)
        val base = consumer.build(RootDomains(problem))
        if (cached) assertNotNull(consumer.preparationTemplates)

        val bound = RevisedSimplex(base.model).use { solver ->
            val result = assertNotNull(solver.solve())
            assertNotNull(FloatDeductionCertificate.of(base.model, result.duals,
                slack = LpBoundSlack(0.0, if (relaxed) 0.5 else 0.0))).lowerBound
        }

        val value = if (relaxed) -3.0 else -1.0
        assertTrue(bound <= value, "form=$form cached=$cached bound $bound excludes $value")
    }
}
