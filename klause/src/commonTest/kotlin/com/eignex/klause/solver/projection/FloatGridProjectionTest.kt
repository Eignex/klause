package com.eignex.klause.solver.projection

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class FloatGridProjectionTest {
    @Test
    fun `private linear comparisons should preserve strict and nonstrict grid boundaries`() {
        for ((operation, strict, expected) in listOf(
            Triple(LinearOp.LE, false, setOf(0L, 1L)),
            Triple(LinearOp.LE, true, setOf(0L)),
            Triple(LinearOp.GE, false, setOf(1L, 2L)),
            Triple(LinearOp.GE, true, setOf(2L)),
            Triple(LinearOp.EQ, false, setOf(1L)),
            Triple(LinearOp.NE, false, setOf(0L, 2L)),
        )) {
            val relation = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                operation, 0.5, strict)
            val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(relation), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
                semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())

            val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
                listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))
            val points = BacktrackSolver(prepared.problem.bake()).enumerate().map { it.intValue(0) }.toSet()

            assertEquals(expected, points)
        }
    }

    @Test
    fun `a finite source range should not be clamped to the fallback grid window`() {
        val semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000,
            UndeclaredRealBounds(SemanticRational.ofLong(-1), SemanticRational.ofLong(1)))).resolve()
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(100.0), realUpper = doubleArrayOf(102.0), semantics = semantics)

        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))
        val sample = assertNotNull(prepared.mapping.reconstruct(prepared.problem,
            Sample(booleanArrayOf(), longArrayOf(1, 2)), Cancellation.Never))

        assertEquals(BigFraction.ofLong(102), sample.exactReals?.single())
        assertEquals(1L, sample.intValue(0))
        assertSame(source.intDomainOrNull(0), prepared.problem.intDomainOrNull(0))
        assertEquals(100.0, source.realLower[0])
        assertSame(semantics, sample.semantics)
    }

    @Test
    fun `missing endpoints should use rational private windows without becoming source bounds`() {
        val semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000,
            UndeclaredRealBounds(SemanticRational.of(bigIntOf(1), bigIntOf(3)),
                SemanticRational.of(bigIntOf(2), bigIntOf(3))))).resolve()
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 2,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY, 0.25),
            realUpper = doubleArrayOf(0.5, Double.POSITIVE_INFINITY), semantics = semantics)

        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)), FloatGridVariable(SourceRealId(1)))), Cancellation.Never))
        val sample = assertNotNull(prepared.mapping.reconstruct(prepared.problem,
            Sample(booleanArrayOf(), longArrayOf(1, 2)), Cancellation.Never))

        assertEquals(BigFraction.ofLong(5) * BigFraction.ofLong(12).reciprocal(), sample.exactReals?.get(0))
        assertEquals(BigFraction.ofLong(2) * BigFraction.ofLong(3).reciprocal(), sample.exactReals?.get(1))
        assertEquals(Double.NEGATIVE_INFINITY, source.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, source.realUpper[1])
    }

    @Test
    fun `continuous source policy should not acquire a private grid`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous).resolve())

        val result = prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0), 3))), Cancellation.Never)

        assertEquals(FloatGridDecline.POLICY, assertIs<FloatGridPreparation.Declined>(result).cause)
    }

    @Test
    fun `an open source integer should not be closed by float projection`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-3, 3)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0), openIntLo = booleanArrayOf(true),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())

        val result = prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never)

        assertEquals(FloatGridDecline.OPEN_INTEGER, assertIs<FloatGridPreparation.Declined>(result).cause)
        assertFalse(source.intBounds.hasLower(0))
    }

    @Test
    fun `a partially selected real relation should decline the whole grid view`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(RealAbs(0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 0.0), realUpper = doubleArrayOf(2.0, 2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(5, 1000)).resolve())

        val result = prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never)

        val declined = assertIs<FloatGridPreparation.Declined>(result)
        assertEquals(FloatGridDecline.FACTOR, declined.cause)
        assertEquals(0, declined.sourceFactor)
    }

    @Test
    fun `a rounded product point should be checked against the original real product`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)), FloatGridVariable(SourceRealId(1)))), Cancellation.Never))
        val privatePoint = Sample(booleanArrayOf(), longArrayOf(1, 0))
        val relation = assertIs<GridProduct>(prepared.problem.factors.single())
        assertEquals(true, relation.accepts(privatePoint::intValue))

        val original = assertNotNull(prepared.mapping.reconstruct(prepared.problem, privatePoint, Cancellation.Never))
        val result = verifyRealCoordinates(source, original)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `another private model should not reuse a float reconstruction`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val request = FloatGridRequest(source, listOf(FloatGridVariable(SourceRealId(0))))
        val first = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(request, Cancellation.Never))
        val second = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(request, Cancellation.Never))

        assertFailsWith<IllegalArgumentException> {
            first.mapping.reconstruct(second.problem, Sample(booleanArrayOf(), longArrayOf(1)), Cancellation.Never)
        }
    }

    @Test
    fun `a stopped projection should not restart reconstruction under an unlimited token`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        var stopped = false
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation { stopped }))
        val point = Sample(booleanArrayOf(), longArrayOf(1))
        stopped = true

        val first = prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never)
        stopped = false
        val retry = prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never)

        assertNull(first)
        assertNull(retry)
    }

    @Test
    fun `projection should decline rather than renew its containing work allowance`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(2.0, 2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 7
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val result = prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)), FloatGridVariable(SourceRealId(1)))), stop)

        assertEquals(FloatGridDecline.RESOURCE, assertIs<FloatGridPreparation.Declined>(result).cause)
    }

    @Test
    fun `maximization should retain the source coefficient and constant through the grid objective`() {
        val source = Problem(0, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))
        val originalObjective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(3.0))

        val projected = assertNotNull(prepared.mapping.projectObjective(originalObjective, Cancellation.Never))
        val original = assertNotNull(prepared.mapping.reconstruct(prepared.problem,
            Sample(booleanArrayOf(), longArrayOf(2)), Cancellation.Never))

        assertSame(originalObjective, projected.original)
        assertEquals(BigFraction.ofLong(13), projected.sourceValue(-2, maximize = true))
        assertEquals(originalObjective.evaluateExact(original), projected.sourceValue(-2, maximize = true))
    }

    @Test
    fun `mixed objective weights should retain one exact positive source scale`() {
        val source = Problem(1, 1, arrayOf(IntDomain(-1, 1)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))
        val originalObjective = LinearObjective(longArrayOf(2), longArrayOf(3), 7, doubleArrayOf(0.5))
        val point = Sample(booleanArrayOf(true), longArrayOf(1, 2))

        val projected = assertNotNull(prepared.mapping.projectObjective(originalObjective, Cancellation.Never))
        val original = assertNotNull(prepared.mapping.reconstruct(prepared.problem, point, Cancellation.Never))

        assertEquals(originalObjective.evaluateExact(original),
            projected.sourceValue(projected.native.evaluateLong(point), maximize = false))
    }

    @Test
    fun `an unrepresentable native objective should leave the prepared source view available`() {
        val source = Problem(1, 0, emptyArray<IntDomain>(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
            semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Grid(3, 1000)).resolve())
        val prepared = assertIs<FloatGridPreparation.Prepared>(prepareFloatGrid(FloatGridRequest(source,
            listOf(FloatGridVariable(SourceRealId(0)))), Cancellation.Never))

        val objective = prepared.mapping.projectObjective(LinearObjective(boolWeights = longArrayOf(1),
            realCoefficients = doubleArrayOf(Double.MIN_VALUE)), Cancellation.Never)

        assertNull(objective)
        assertNotNull(prepared.mapping.reconstruct(prepared.problem,
            Sample(booleanArrayOf(false), longArrayOf(0)), Cancellation.Never))
    }
}
