package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreparedSourcePlanTest {
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
        assertEquals(listOf(1), plan.components.single { it.responsibility == ComponentResponsibility.FINITE }.factorIndices)
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
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= (* (ite b x 1) x) 2))",
        )

        val request = source.pipelineRequest()
        val plan = request.preparedPlan

        assertSame(source, plan.originalSource)
        assertTrue(plan.model.numRealVars > plan.sourceRealCount)
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

}
