package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.TheoryRealId
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FactorPolynomialProjectionTest {
    @Test
    fun `selected open polynomial execution should retain original identities for every strategy`() {
        val product = ContinuousProduct(0, 0, 1)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), arrayOf(product),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
            numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))

        for (engine in FiniteEngine.entries) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.SATISFY, engine))
            val plan = assertNotNull(invocation.preparedPlan)
            val execution = assertIs<PreparedExecution.NonlinearInteger>(plan.execution)
            val projection = assertNotNull(execution.projection)

            assertEquals(SourceExecutionKind.NONLINEAR_INTEGER, SourceSetup.executionKind(invocation))
            assertSame(source, plan.originalSource)
            assertSame(product, projection.model.factors.single())
            assertEquals(0, plan.integerCoordinate(0))
            assertEquals(1, plan.realCoordinate(0))
            assertEquals(2, plan.realCoordinate(1))
            assertNull(plan.finiteIntegerCoordinate(0))
            assertTrue(source.intBounds.isOpenLower(0) && source.intBounds.isOpenUpper(0))
            assertEquals(mapOf(SourceRealId(0) to TheoryRealId(1), SourceRealId(1) to TheoryRealId(2)),
                projection.attributions.single().realEmbeddings)
        }
    }

    @Test
    fun `open polynomial factor execution should publish exact original source coordinates`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), arrayOf(ContinuousProduct(0, 0, 1)),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
            numRealVars = 2, realLower = doubleArrayOf(1.0, 1.0), realUpper = doubleArrayOf(1.0, 1.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val execution = assertIs<SourceExecutionResult.Polynomial>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val assignment = assertIs<OpenTheoryResult.Sat>(execution.result.result).assignment
        assertEquals("1", assignment.realValue(0))
        assertEquals("1", assignment.realValue(1))
        assertSame(source, execution.invocation.source.originalSource)
    }

    @Test
    fun `mixed product projection should keep integer and real coordinates separate`() {
        val source = Problem(0, 2, arrayOf(IntDomain(2, 2), IntDomain(0, 0)),
            arrayOf(ContinuousProduct(0, 0, 1), RealProduct(0, 0, 2, 1.0, 1.0)),
            openIntLo = booleanArrayOf(false, true), openIntHi = booleanArrayOf(false, true),
            numRealVars = 3, realLower = doubleArrayOf(1.0, 1.0, 2.0), realUpper = doubleArrayOf(1.0, 1.0, 2.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val execution = assertIs<SourceExecutionResult.Polynomial>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val assignment = assertIs<OpenTheoryResult.Sat>(execution.result.result).assignment
        assertEquals("2", assignment.intValue(0))
        assertEquals("1", assignment.realValue(0))
        assertEquals("2", assignment.realValue(2))
        val projection = assertNotNull(assertIs<PreparedExecution.NonlinearInteger>(
            assertNotNull(invocation.preparedPlan).execution).projection)
        assertEquals(mapOf(SourceIntId(0) to TheoryRealId(0)), projection.attributions[1].integerEmbeddings)
        assertEquals(mapOf(SourceRealId(0) to TheoryRealId(2), SourceRealId(2) to TheoryRealId(4)),
            projection.attributions[1].realEmbeddings)
    }

    @Test
    fun `factor backed quadratic publication should retain an original wide integer`() {
        val source = SmtLib.parse("(declare-const n Int) (declare-const x Real) " +
            "(assert (= n 9223372036854775808)) (assert (= (* x x) 2))")
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
            QuadraticCoefficient.ONE, QuadraticVariable.Int(SourceIntId(0)))))
        val invocation = SourceSetup.prepare(SourceSetupRequest(source.source, FiniteEngine.BACKTRACK, objective))

        val execution = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val point = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result.result).incumbent
        assertEquals(parseBigInt("9223372036854775808"), point.sample.exactIntValue(0))
        assertNotNull(point.sample.witnessCertificate?.quadraticPublication)
        assertIs<SourceEvidenceCheck.Checked>(checkQuadraticSourceWitness(
            assertNotNull(invocation.preparedPlan), point.sample, Cancellation.Never, PolynomialLimits()))
    }

    @Test
    fun `factor backed quadratic checking should reject an invalid mixed point`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), arrayOf(RealProduct(0, 0, 1, 1.0, 1.0)),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true),
            numRealVars = 2, realLower = doubleArrayOf(1.0, 0.0), realUpper = doubleArrayOf(1.0, 4.0))
        val plan = source.preparePolynomialExecutionPlan()
        val point = Sample(booleanArrayOf(), longArrayOf(2), doubleArrayOf(1.0, 3.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ofLong(3)))

        val checked = checkQuadraticSourceWitness(plan, point, Cancellation.Never, PolynomialLimits())

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `integer projection should preserve finite holes beside an open declaration`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 0), IntDomain(0, 2).excludeValue(1)), emptyArray(),
            openIntLo = booleanArrayOf(true, false), openIntHi = booleanArrayOf(true, false))
        val projections = FactorProjectionProviders.Default.prepare(source)
        val projection = assertNotNull(source.integerPolynomialProjection(source, projections, Cancellation.Never) {})
        val formula = projection.formula

        PolynomialTheoryService(formula.formula, formula.boolCount, formula.numericCount).invocation().use { query ->
            val excluded = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ONE)
            val included = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ofLong(2))

            assertIs<QfNraResult.Unsatisfiable>(query.check(PolynomialCondition(lower = excluded, upper = excluded)))
            assertIs<QfNraResult.Satisfiable>(query.check(PolynomialCondition(lower = included, upper = included)))
        }
    }

    @Test
    fun `unsupported whole scopes should decline before installing a factor prefix`() {
        for (unsupportedFirst in listOf(false, true)) {
            val first = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
            val second = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
            val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), arrayOf(first, second),
                openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
            var installs = 0
            val unsupported = if (unsupportedFirst) first else second
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (target != EncodingTarget.POLYNOMIAL) BuiltInEncoderProvider.select(factor, target)
                else Encoder(if (factor === unsupported) EncoderSupport.UNSUPPORTED else EncoderSupport.EQUIVALENT) {
                    _, encoding -> installs++; encoding.polynomial(PolynomialRealFormula.Constant(true)); null
                }
            })
            val declines = ArrayList<EncoderDecline>()

            val projection = source.integerPolynomialProjection(source, providers.prepare(source),
                Cancellation.Never, declines::add)

            assertNull(projection)
            assertEquals(0, installs)
            assertEquals(listOf(EncoderDecline.UNSUPPORTED), declines)
        }
    }

    @Test
    fun `conditioned integer encoders should require actual source pins`() {
        for (fixed in listOf(false, true)) {
            val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2)
            val source = Problem(0, 2, arrayOf(IntDomain(2, 2), IntDomain(0, 0)), arrayOf(row),
                openIntLo = booleanArrayOf(!fixed, true), openIntHi = booleanArrayOf(!fixed, true))
            var installs = 0
            val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                if (target != EncodingTarget.POLYNOMIAL) BuiltInEncoderProvider.select(factor, target)
                else Encoder(EncoderSupport.CONDITIONED, requiredIntegers = listOf(SourceIntId(0))) { _, encoding ->
                    installs++
                    assertEquals(bigIntOf(2), encoding.fixedInteger(SourceIntId(0)))
                    encoding.polynomial(PolynomialRealFormula.Constant(true))
                    null
                }
            })
            val declines = ArrayList<EncoderDecline>()

            val projection = source.integerPolynomialProjection(source, providers.prepare(source),
                Cancellation.Never, declines::add)

            assertEquals(if (fixed) 1 else 0, installs)
            if (fixed) assertEquals(mapOf(SourceIntId(0) to bigIntOf(2)),
                assertNotNull(projection).attributions.single().integerConditions)
            else {
                assertNull(projection)
                assertEquals(listOf(EncoderDecline.CONDITIONS), declines)
            }
        }
    }

    @Test
    fun `transient installation cancellation should discard the selected projection`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), arrayOf(row),
            openIntLo = booleanArrayOf(true), openIntHi = booleanArrayOf(true))
        var interrupted = false
        val stop = Cancellation { interrupted.also { interrupted = false } }
        val providers = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (target != EncodingTarget.POLYNOMIAL) BuiltInEncoderProvider.select(factor, target)
            else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                encoding.polynomial(PolynomialRealFormula.Constant(true))
                interrupted = true
                null
            }
        })
        val declines = ArrayList<EncoderDecline>()

        val projection = source.integerPolynomialProjection(source, providers.prepare(source), stop, declines::add)

        assertNull(projection)
        assertEquals(listOf(EncoderDecline.RESOURCE), declines)
        assertTrue(source.intBounds.isOpenUpper(0))
    }

    @Test
    fun `affine real objectives beside retained products should keep original units`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)),
            numRealVars = 2, realLower = doubleArrayOf(2.0, 0.0), realUpper = doubleArrayOf(2.0, 4.0))
        val objective = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(0.0, 1.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective))

        val execution = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result.result)
        assertEquals(BigFraction.ofLong(11), optimum.incumbent.value.rational)
        assertSame(objective, invocation.sourceObjective)
        assertSame(source, assertNotNull(invocation.preparedPlan).originalSource)
    }

    @Test
    fun `maximized minimum word coefficients should normalize through exact execution`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(Long.MIN_VALUE), constant = Long.MIN_VALUE)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
            ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective, maximize = true))

        val execution = assertIs<SourceExecutionResult.Quadratic>(
            SourceSetup.execute(SourceExecutionRequest(invocation)))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(execution.result.result)
        assertEquals(BigFraction.ofLong(Long.MIN_VALUE), optimum.incumbent.value.rational)
        assertEquals(ObjectiveSense.MAXIMIZE, optimum.sense)
        assertSame(objective, invocation.sourceObjective)
    }
}
