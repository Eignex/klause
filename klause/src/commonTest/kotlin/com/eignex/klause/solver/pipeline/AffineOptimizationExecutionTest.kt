package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AffineOptimizationExecutionTest {
    @Test
    fun `exact affine optimization should preserve the finite linear optimum`() {
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val request = source.pipelineRequest()
        val objective = LinearObjective(longArrayOf(1), longArrayOf(-1), 7)

        val finite = OpenTheoryPipeline.minimize(request, objective, engine = FiniteEngine.BACKTRACK).result
        val exact = OpenTheoryPipeline.optimize(request, objective, engine = FiniteEngine.BACKTRACK).result

        val finiteOptimum = assertIs<MinimizeResult.Optimal>(finite)
        val exactOptimum = assertIs<QuadraticOptimizationResult.Optimal>(exact)
        assertEquals(5.0, finiteOptimum.objective)
        assertEquals(BigFraction.ofLong(5), exactOptimum.incumbent.value.rational)
        assertEquals(false, exactOptimum.incumbent.sample.boolValue(0))
        assertEquals(2L, exactOptimum.incumbent.sample.intValue(0))
    }

    @Test
    fun `source affine bounds should keep full width sums beyond the finite score range`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        val objective = LinearObjective(boolWeights = longArrayOf(Long.MIN_VALUE), constant = Long.MIN_VALUE)
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                assumptions = Assumptions.None.withBool(0, true),
            ),
        )

        val result = OpenTheoryPipeline.optimize(
            source.pipelineRequest(),
            objective,
            params,
            FiniteEngine.BACKTRACK,
        ).result

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result)
        val expected = BigFraction.ofLong(Long.MIN_VALUE) + BigFraction.ofLong(Long.MIN_VALUE)
        assertEquals(expected, optimum.incumbent.value.rational)
        assertEquals(expected, optimum.bound.value.rational)
    }
}
