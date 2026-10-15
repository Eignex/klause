package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSourcePlanObjectiveBindingTest {
    @Test
    fun `objective binding should keep completed polynomial encoders after their construction scope ends`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(2.0, 4.0),
        )
        var installations = 0
        var stopped = false
        val providers = FactorProjectionProviders(
            encoder = EncoderProvider { factor, target ->
                val selected = BuiltInEncoderProvider.select(factor, target)
                if (!selected.enforcesSource) {
                    selected
                } else {
                    Encoder(selected.support) { view, encoding ->
                        installations++
                        selected.install(view, encoding)
                    }
                }
            },
        )
        val plan = source.prepareExecutionPlan(providers, cancellation = Cancellation { stopped })
        val preparedInstallations = installations
        val objective = LinearObjective(intCoefficients = longArrayOf(1), constant = 7)
        stopped = true

        val operation = plan.withObjective(objective, maximize = true)
        val admission = operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE)

        assertTrue(preparedInstallations > 0)
        assertEquals(preparedInstallations, installations)
        assertTrue(admission.runnable)
        assertSame(plan.finiteFactorProjections, operation.finiteFactorProjections)
        assertSame(plan.factorProjections, operation.factorProjections)
        assertSame(source, operation.originalSource)
        assertSame(objective, operation.objective)
        assertTrue(operation.maximize)
        assertEquals(plan.polynomialIntegerCoordinate(0), operation.polynomialIntegerCoordinate(0))
        assertNull(plan.objective)
    }

    @Test
    fun `invalid objective coordinates should preserve polynomial satisfaction and allow a rebind`() {
        val source = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse(
                "(declare-const x Real) (assert (= (* x x) 2))",
            ),
        ).problem
        val plan = source.prepareExecutionPlan()
        val invalid = plan.withObjective(LinearObjective(realCoefficients = doubleArrayOf(1.0, 1.0)))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(0.5), constant = 7)

        val rebound = invalid.withObjective(objective, maximize = true)

        assertTrue(invalid.capabilities.admission(FiniteEngine.BACKTRACK).runnable)
        assertEquals(
            ExecutionDeclineCause.OBJECTIVE,
            invalid.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE).declines.single().cause,
        )
        assertTrue(rebound.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE).runnable)
        assertEquals(
            setOf(ExecutionClaimAuthority.WITNESS),
            rebound.capabilities.admission(FiniteEngine.LOCAL_SEARCH, ExecutionOperation.OPTIMIZE).claimAuthorities,
        )
        assertSame(source, rebound.originalSource)
        assertSame(objective, rebound.objective)
        assertTrue(rebound.maximize)
        assertEquals(plan.realCoordinate(0), rebound.realCoordinate(0))
    }

    @Test
    fun `objective binding should not repair interrupted structural preparation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val plan = source.prepareExecutionPlan(cancellation = Cancellation { true })

        val operation = plan.withObjective(LinearObjective(intCoefficients = longArrayOf(1)))
        val admission = operation.capabilities.admission(FiniteEngine.BACKTRACK)

        assertEquals(PreparedPlanDecline.RESOURCE, operation.decline)
        assertEquals(PreparedExecutionKind.DECLINED, operation.kind)
        assertFalse(admission.runnable)
        assertTrue(admission.declines.any { it.preparation == PreparedPlanDecline.RESOURCE })
        assertSame(source, operation.originalSource)
    }

    @Test
    fun `objective decline should keep the polynomial structure for a supported rebind`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(ContinuousProduct(0, 0, 0)),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val prepared = source.prepareExecutionPlan()
        val legacy = PreparedSourcePlan(
            source, source, PreparedExecutionKind.DECLINED, null,
            prepared.polynomialModel, prepared.components, 0, 0, 1,
            decline = PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE,
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

        val rebound = legacy.withObjective(objective)

        assertEquals(PreparedExecutionKind.DECLINED, legacy.kind)
        assertEquals(PreparedExecutionKind.POLYNOMIAL, rebound.kind)
        assertNull(rebound.decline)
        assertSame(source, rebound.model)
        assertTrue(rebound.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE).runnable)
    }

    @Test
    fun `quadratic rebinding should keep exact source units but drop an earlier affine objective`() {
        val source = assertIs<SmtLibInput.Polynomial>(
            SmtLibAdmission.parse(
                "(declare-const x Real) (assert (= (* x x) 2))",
            ),
        ).problem
        val affine = source.prepareExecutionPlan().withObjective(
            LinearObjective(realCoefficients = doubleArrayOf(0.5)),
            maximize = true,
        )
        val coefficient = QuadraticCoefficient.of(
            com.eignex.klause.util.bigIntOf(1),
            com.eignex.klause.util.bigIntOf(3),
        )
        val objective = QuadraticObjectiveSpec(
            listOf(
                QuadraticObjectiveTerm(
                    coefficient,
                    QuadraticVariable.Real(SourceRealId(0)),
                    QuadraticVariable.Real(SourceRealId(0)),
                ),
            ),
        )

        val operation = affine.withObjective(objective, ObjectiveSense.MAXIMIZE)

        assertSame(objective, operation.quadraticObjective)
        assertEquals(coefficient, operation.quadraticObjective?.terms?.single()?.coefficient)
        assertEquals(ObjectiveSense.MAXIMIZE, operation.quadraticSense)
        assertNull(operation.objective)
        assertSame(source, operation.originalSource)
        assertTrue(operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE).runnable)
        assertTrue(affine.maximize)
    }

    @Test
    fun `native integer objective binding should preserve interleaved numeric declarations without finite domains`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true),
            3,
            0,
            mapOf("n" to 1),
            mapOf("x" to 0, "y" to 2),
            emptyMap(),
        )
        val plan = source.prepareExecutionPlan()
        val objective = QuadraticObjectiveSpec(emptyList())

        val operation = plan.withObjective(objective, ObjectiveSense.MAXIMIZE)

        assertSame(source, operation.originalSource)
        assertNull(operation.model)
        assertNull(operation.finiteIntegerCoordinate(0))
        assertEquals(1, operation.integerCoordinate(0))
        assertEquals(0, operation.realCoordinate(0))
        assertEquals(2, operation.realCoordinate(1))
        assertEquals(PreparedExecutionKind.NONLINEAR_INTEGER, operation.kind)
        assertTrue(operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE).runnable)
        assertFalse(operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.EXACT_COUNT).runnable)
    }

    @Test
    fun `transcendental binding should preserve satisfaction without adding an optimization surface`() {
        val source = TranscendentalProblem(
            TranscendentalFormula.Constant(true),
            mapOf("n" to 1),
            mapOf("x" to 0),
            emptyMap(),
        )
        val plan = source.prepareTranscendentalExecutionPlan()

        val operation = plan.withObjective(LinearObjective(intCoefficients = longArrayOf(1)), maximize = true)

        assertSame(source, operation.originalSource)
        assertNull(operation.model)
        assertNull(operation.finiteIntegerCoordinate(0))
        assertEquals(1, operation.integerCoordinate(0))
        assertEquals(0, operation.realCoordinate(0))
        assertEquals(PreparedExecutionKind.TRANSCENDENTAL, operation.kind)
        assertTrue(operation.capabilities.admission(FiniteEngine.BACKTRACK).runnable)
        assertEquals(
            ExecutionDeclineCause.OPERATION_SURFACE,
            operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE)
                .declines.single().cause,
        )
    }

    @Test
    fun `final preparation stop should keep completed polynomial coordinates across objective binding`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 2)),
            arrayOf(RealProduct(0, 0, 1, 1.0, 4.0)),
            numRealVars = 2,
            realLower = doubleArrayOf(1.0, 4.0),
            realUpper = doubleArrayOf(4.0, 4.0),
        )
        var polls = 0
        val complete = source.preparePolynomialExecutionPlan(
            cancellation = Cancellation {
                polls++
                false
            },
        )
        val lastPoll = polls
        polls = 0
        val stopped = source.preparePolynomialExecutionPlan(cancellation = Cancellation { ++polls >= lastPoll })

        val operation = stopped.withObjective(LinearObjective(intCoefficients = longArrayOf(1)))

        assertEquals(PreparedPlanDecline.RESOURCE, operation.decline)
        assertEquals(PreparedExecutionKind.DECLINED, operation.kind)
        assertEquals(assertNotNull(complete.polynomialIntegerCoordinate(0)), operation.polynomialIntegerCoordinate(0))
        assertEquals(0, operation.finiteIntegerCoordinate(0))
        assertSame(stopped.polynomialModel, operation.polynomialModel)
        assertSame(stopped.finiteFactorProjections, operation.finiteFactorProjections)
        assertEquals(0, assertNotNull(operation.finiteFactorProjections).problem.numFactors)
    }
}
