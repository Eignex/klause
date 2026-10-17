package com.eignex.klause.solver.projection

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FloatGridFiniteDomainTest {
    @Test
    fun `constant private grids should retain their original membership attribution`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(0.125))),
            numRealVars = 1, realLower = doubleArrayOf(0.125), realUpper = doubleArrayOf(0.125),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0), buckets = 3, preserveBuckets = true))), Cancellation.Never))

        val point = BacktrackSolver(prepared.problem.bake()).enumerate().first()
        val original = assertNotNull(prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never))

        assertEquals(0, prepared.mapping.sourceFactor(0))
        assertEquals(BigFraction.ofLong(8).reciprocal(), assertNotNull(original.exactReals).single())
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, original))
    }

    @Test
    fun `a private product view should retain its unguarded operand membership`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(
            ContinuousProduct(0, 0, 1, 0, doubleArrayOf(-1.0, 0.125, 0.5)),
        ), numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(5, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)), FloatGridVariable(SourceRealId(1)))), Cancellation.Never))

        val points = BacktrackSolver(prepared.problem.bake()).enumerate().map { point ->
            assertNotNull(prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never))
        }.toList()

        assertEquals(setOf(BigFraction.ofLong(-1), BigFraction.ofLong(2).reciprocal()),
            points.map { assertNotNull(it.exactReals)[0] }.toSet())
        for (point in points) assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, point))
    }

    @Test
    fun `a selected grid should exclude nonmembers without rounding declared values`() {
        val source = Problem(0, 0, emptyArray(),
            arrayOf(RealFiniteDomain(0, doubleArrayOf(-1.0, 0.125, 0.5))),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(5, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(
            FloatGridRequest(source, listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))

        val points = BacktrackSolver(prepared.problem.bake()).enumerate().map { point ->
            assertNotNull(prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never))
        }.toList()

        assertEquals(setOf(BigFraction.ofLong(-1), BigFraction.ofLong(2).reciprocal()),
            points.map { assertNotNull(it.exactReals).single() }.toSet())
        for (point in points) assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, point))
        assertEquals(1, source.numRealVars)
        assertEquals(0, source.numIntVars)
    }

    @Test
    fun `private grid exhaustion should preserve the nonempty original membership`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(0.125))),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(2, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(
            FloatGridRequest(source, listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))

        val privateResult = prepared.problem.baked

        assertIs<PropagationResult.Unsat>(privateResult)
        assertEquals(0.125, (source.factors.single() as RealFiniteDomain).values.single())
        assertEquals(1, source.numRealVars)
    }
}
