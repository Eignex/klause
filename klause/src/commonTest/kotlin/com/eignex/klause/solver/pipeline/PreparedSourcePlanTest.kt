package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSourcePlanTest {
    @Test
    fun `open source preparation retains selected deduction on its compact finite view`() {
        val open = Bits(3).also { it.set(1) }
        val row = AllDifferent(intArrayOf(0, 2), 0, 3)
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(
            longArrayOf(1, 0, 0), longArrayOf(1, 0, 2), open, open,
        ), factors = arrayOf(row, Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 0)))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor !== row) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The source relation is checked at a complete point.") { NoPropagator }
        })

        val prepared = assertIs<OpenSourcePreparation.Planned>(source.prepareOpenSource(
            config = com.eignex.klause.presolve.PresolveConfig.NONE, projections = providers.prepare(source)))
        val view = prepared.plan.cpProjection(prepared.model, prepared.plan.cpSourceDomains(prepared.model))

        assertEquals(IntDomain(0, 2), view.problem.rootIntDomain(1))
    }

    @Test
    fun `complete admission does not require an available scorer`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider { null })

        val plan = source.prepareExecutionPlan(providers, preferFinite = true)

        assertTrue(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        val local = plan.capabilities.admission(ExecutionStrategy.LOCAL_SEARCH)
        assertFalse(local.runnable)
        assertEquals(ExecutionDeclineCause.HEURISTIC_PROJECTION, local.declines.single().cause)
        assertEquals(0, local.declines.single().factorIndex)
    }

    @Test
    fun `unsupported deduction identifies its source factor during admission`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { null })

        val admitted = source.prepareExecutionPlan(providers, preferFinite = true).capabilities
            .admission(ExecutionStrategy.COMPLETE)

        assertFalse(admitted.runnable)
        assertEquals(ExecutionDeclineCause.FINITE_PROJECTION, admitted.declines.single().cause)
        assertEquals(0, admitted.declines.single().factorIndex)
    }

    @Test
    fun `an inert clause projection retains exact source enumeration`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider {
            PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The original clause is checked at a complete point.") { NoPropagator }
        })
        val plan = source.prepareExecutionPlan(providers, preferFinite = true)
        val ownership = assertNotNull(plan.componentPlan)

        val view = ownership.cpProjection(source, emptyMap()).problem
        val witnesses = BacktrackSolver(view).enumerate().toList()

        assertTrue(plan.capabilities.admission(ExecutionStrategy.COMPLETE).runnable)
        assertEquals(FactorOwner.CP, ownership.factorOwner(0))
        assertEquals(listOf(true), witnesses.map { it.boolValue(0) })
    }

    @Test
    fun `objective and conditioned plans retain selected finite deduction`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(
            row, Linear(intVars = intArrayOf(), intCoeffs = doubleArrayOf(), realVars = intArrayOf(0),
                realCoeffs = doubleArrayOf(1.0), op = LinearOp.EQ, bound = 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor !== row) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The source row is checked at a complete point.") { NoPropagator }
        })
        val sourcePlan = source.prepareExecutionPlan(providers, preferFinite = true)

        val objective = sourcePlan.withObjective(LinearObjective(intCoefficients = longArrayOf(1)))
        val conditioned = assertNotNull(objective.prepareConditionedLinearQuery())
        val view = assertNotNull(conditioned.componentPlan).cpProjection(source, mapOf(0 to IntDomain(0, 2)))

        assertSame(sourcePlan.factorProjections, objective.factorProjections)
        assertSame(sourcePlan.factorProjections, conditioned.factorProjections)
        assertEquals(IntDomain(0, 2), view.problem.rootIntDomain(0))
    }

    @Test
    fun `polynomial finite views retain selection by original factor identity`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(ContinuousProduct(0, 0, 1), row),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(2.0, 4.0))
        val providers = FactorProjectionProviders(propagation = PropagationProjectionProvider { factor ->
            if (factor !== row) BuiltInPropagationProjectionProvider.select(factor)
            else PropagationProjection(PropagationCapability.INERT, AssignmentCheckCapability.SOURCE_CERTIFICATION,
                "The finite source row is checked at a complete point.") { NoPropagator }
        })

        val plan = source.prepareExecutionPlan(providers)
        val finite = assertNotNull(plan.polynomialModel).finite.bake(assertNotNull(plan.finiteFactorProjections))

        assertEquals(PreparedExecutionKind.POLYNOMIAL, plan.kind)
        assertEquals(IntDomain(0, 2), finite.rootIntDomain(0))
        assertEquals(setOf(1L, 2L), BacktrackSolver(finite).enumerate().map { it.intValue(0) }.toSet())
    }

    @Test
    fun `polynomial decomposition retains original factor indices and integer embedding`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf(
            ContinuousProduct(0, 0, 2),
            AllDifferent(intArrayOf(0, 1), 0, 3),
            RealProduct(1, 0, 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
        ), numRealVars = 3,
            realLower = doubleArrayOf(0.0, Double.NEGATIVE_INFINITY, 2.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 2.0))

        val route = assertIs<SourceProblemRoute.PolynomialTheory>(source.pipelineRoute())
        val plan = route.preparedPlan

        assertSame(source, plan.originalSource)
        assertSame(plan, route.request.preparedPlan)
        assertEquals(listOf(1), plan.components.single {
            it.responsibility == ComponentResponsibility.FINITE
        }.factorIndices)
        assertEquals(listOf(0, 2), plan.components.single {
            it.responsibility == ComponentResponsibility.COMPLETION
        }.factorIndices)
        assertEquals(3, plan.polynomialIntegerCoordinate(1))
        assertEquals(listOf(0, 1), plan.components.single {
            it.responsibility == ComponentResponsibility.FINITE
        }.integerCoordinates)
        assertSame(source.factors[1], assertNotNull(plan.polynomialModel).finite.factors.single())
    }

    @Test
    fun `polynomial assembler declines open integers without changing source declarations`() {
        val open = Bits(1).also { it.set(0) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(ContinuousProduct(0, 0, 0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(source.pipelineRoute())

        assertEquals(PreparedPlanDecline.OPEN_POLYNOMIAL_INTEGER, route.preparedPlan.decline)
        assertSame(source, route.preparedPlan.model)
        assertTrue(!source.intBounds.hasLower(0) && !source.intBounds.hasUpper(0))
    }

    @Test
    fun `polynomial snapshots preserve declaration identities separately from auxiliary coordinates`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= (* (ite b x 1) x) 2))",
        )
        val source = PolynomialRealProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.sourceRealCount, parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames)

        val request = source.pipelineRequest()
        val plan = request.preparedPlan

        assertSame(source, plan.originalSource)
        assertTrue(assertNotNull(plan.model).numRealVars > plan.sourceRealCount)
        assertEquals(0, plan.realCoordinate(source.realVarNames.getValue("x")))
        assertEquals(0, plan.booleanCoordinate(source.boolVarNames.getValue("b")))
        assertFailsWith<IllegalArgumentException> { plan.realCoordinate(plan.sourceRealCount) }
    }

    @Test
    fun `finite preparation retains the selected objective and source ownership`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 3)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))

        val plan = source.prepareExecutionPlan(objective, maximize = true, preferFinite = true)
        val request = FinitePipelineRequest(source, FiniteEngine.BACKTRACK, objective, preparedPlan = plan)

        assertSame(plan, request.preparedPlan)
        assertSame(objective, plan.objective)
        assertTrue(plan.maximize)
        assertEquals(listOf(0, 1), plan.components.single().integerCoordinates)
        assertEquals(IntVariableOwner.CP, request.componentPlan.intOwner(1))
    }
    @Test
    fun `direct finite mixed product preparation preserves linear completion ownership`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))

        val request = FinitePipelineRequest(source, FiniteEngine.LOCAL_SEARCH)

        assertEquals(PreparedExecutionKind.FINITE_LINEAR, request.preparedPlan.kind)
        assertEquals(0, request.preparedPlan.finiteIntegerCoordinate(0))
        assertSame(source, request.preparedPlan.originalSource)
    }

    @Test
    fun `conditioned linear preparation preserves finite owners beside exact real completion`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)), arrayOf(
            AllDifferent(intArrayOf(0, 1), 0, 3),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val initial = source.prepareExecutionPlan(preferFinite = true)

        val prepared = assertNotNull(initial.prepareConditionedLinearQuery())
        val ownership = assertNotNull(prepared.componentPlan)

        assertSame(source, prepared.originalSource)
        assertEquals(IntVariableOwner.CP, ownership.intOwner(0))
        assertEquals(FactorOwner.CP, ownership.factorOwner(0))
        assertEquals(FactorOwner.THEORY, ownership.factorOwner(1))
        assertEquals(ProblemPipeline.EXACT_LIRA, ownership.theoryPipeline)
    }

    @Test
    fun `nonlinear source declarations retain interleaved exact numeric coordinates`() {
        val source = assertIs<SmtLibInput.NonlinearInteger>(SmtLibAdmission.parse(
            "(declare-const x Int) (declare-const r Real) (declare-const z Int) (assert (= (* x z) r))",
        )).problem

        val plan = source.prepareExecutionPlan()

        assertSame(source, plan.originalSource)
        assertEquals(PreparedExecutionKind.NONLINEAR_INTEGER, plan.kind)
        kotlin.test.assertNull(plan.model)
        assertEquals(0, plan.integerCoordinate(0))
        assertEquals(2, plan.integerCoordinate(1))
        assertEquals(1, plan.realCoordinate(0))
        kotlin.test.assertNull(plan.finiteIntegerCoordinate(1))
    }

}
