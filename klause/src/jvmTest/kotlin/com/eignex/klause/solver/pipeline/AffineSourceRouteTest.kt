package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class AffineSourceRouteTest(private val factor: Factor, private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} maximize={1}")
        fun variants(): List<Array<Any>> = listOf(
            ContinuousProduct(0, 0, 1), RealProduct(0, 0, 1, -1.0, 1.0),
        ).flatMap { factor -> listOf(false, true).map { arrayOf<Any>(factor, it) } }
    }

    @Test
    fun `shared polynomial routing retains the original affine objective and source direction`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(factor), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))

        val route = source.pipelineRoute(objective, maximize)

        val polynomial = assertIs<SourceProblemRoute.PolynomialTheory>(route)
        assertSame(objective, polynomial.objective)
        assertEquals(maximize, polynomial.maximize)
    }
}
