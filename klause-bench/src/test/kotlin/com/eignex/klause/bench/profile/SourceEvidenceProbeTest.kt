package com.eignex.klause.bench.profile

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.minimize
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceEvidenceProbeTest {
    @Test
    fun `actual maximization endpoints retain exact rational source evidence`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(3.0))
        val variable = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ofLong(2), variable, variable)), QuadraticCoefficient.ofLong(7))

        val result = assertIs<QuadraticOptimizationResult.Optimal>(OpenTheoryPipeline.minimize(source.pipelineRequest(),
            objective, PolynomialTheoryParams(maximumWork = 100_000), FiniteEngine.BACKTRACK,
            ObjectiveSense.MAXIMIZE).result)

        val coordinate = assertNotNull(result.incumbent.sample.algebraicReals).single()
        val three = BigFraction.ofLong(3)
        val value = BigFraction.ofLong(25)
        assertEquals(three, SourceEvidenceProbe.rationalRepresentation(coordinate,
            listOf(BigFraction.ofLong(-2), BigFraction.ZERO, three)))
        assertEquals(value, SourceEvidenceProbe.rationalRepresentation(result.incumbent.value, listOf(value)))
        assertEquals(value, SourceEvidenceProbe.rationalRepresentation(result.bound.value, listOf(value)))
        assertNull(SourceEvidenceProbe.rationalRepresentation(coordinate,
            listOf(BigFraction.ofLong(-2), BigFraction.ZERO)))
    }
}
