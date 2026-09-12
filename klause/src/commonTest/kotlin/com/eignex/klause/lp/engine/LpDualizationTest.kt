package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpDualizationTest {
    @Test
    fun `completed proof work remains charged when cancellation arrives`() {
        var cancelled = false
        val meter = LpDualizationMeter(100L, 256, Cancellation { cancelled })
        meter.step(7L)
        cancelled = true

        assertFailsWith<RuntimeException> { meter.completed(23L) }

        assertEquals(30L, meter.work)
    }

    @Test
    fun `a tall root produces an original basis and exact optimum`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))

        val basis = assertNotNull(attempt.solve(source))
        val result = solveAndCertify(
            source.model,
            ExactLpBasis(basis.basicVars.toList(), basis.status.map { ExactLpStatus.valueOf(it.name) }),
        )

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
        assertEquals(BigFraction.ofLong(6L), result.lowerBound)
        assertTrue(attempt.metrics.basisRecovered)
        assertTrue(attempt.metrics.totalWork > 0L)
        assertSame(source, attempt.sourceState)
    }

    @Test
    fun `default and non tall roots decline before spending work`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val disabled = LpRootDualizationAttempt(LpDualizationOptions())
        val short = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))

        assertNull(disabled.solve(source))
        assertNull(short.solve(source))

        assertEquals(LpDualizationDecline.DISABLED, disabled.metrics.decline)
        assertEquals(LpDualizationDecline.NOT_TALL, short.metrics.decline)
        assertEquals(0L, disabled.metrics.totalWork + short.metrics.totalWork)
    }

    @Test
    fun `an exhausted finite parent allowance declines without creating an auxiliary`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))

        assertNull(attempt.solve(source, parentWork = 1L))

        assertEquals(LpDualizationDecline.PARENT_BUDGET, attempt.metrics.decline)
        assertEquals(0L, attempt.metrics.totalWork)
    }

    @Test
    fun `an exhausted attempt cannot acquire a second allowance`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(
            LpDualizationOptions(enabled = true, minRowColumnRatio = 1, constructionWork = 1L),
        )

        assertNull(attempt.solve(source))
        assertFailsWith<IllegalStateException> { attempt.solve(source) }

        assertEquals(LpDualizationDecline.WORK, attempt.metrics.decline)
        assertNull(attempt.certificate)
    }

    @Test
    fun `cancellation prevents source publication`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        assertNull(attempt.solve(source, token = Cancellation { true }))

        assertEquals(LpDualizationDecline.CANCELLED, attempt.metrics.decline)
        assertNull(attempt.certificate)
    }

    @Test
    fun `strict source bounds are explicitly unsupported`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one))),
                listOf(one),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(one, zero)),
            ),
        )
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        assertNull(attempt.solve(source))

        assertEquals(LpDualizationDecline.STRICT, attempt.metrics.decline)
    }

    @Test
    fun `scalar limits reject oversized source authority`() {
        val zero = ExactLpNumber.of(0L)
        val big = ExactLpNumber.of(1L shl 40)
        val source = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, big))),
                listOf(big),
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(big, zero)),
            ),
        )
        val attempt = LpRootDualizationAttempt(
            LpDualizationOptions(enabled = true, minRowColumnRatio = 1, maxBits = 24),
        )

        assertNull(attempt.solve(source))

        assertEquals(LpDualizationDecline.BITS, attempt.metrics.decline)
    }

    @Test
    fun `explicit root context uses the source certification ladder after dualization`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val model = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())

        val result = solveAndCertify(
            model,
            context = LpSolveContext(rootDualization = LpDualizationOptions(enabled = true)),
        )

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(6L), result.lowerBound)
        assertEquals(listOf(BigFraction.ofLong(3L)), result.exactPrimal)
        assertTrue(assertNotNull(result.float).warmStarted)
    }

    @Test
    fun `nonfinite auxiliary hints decline and close the numerical owner`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        var closed = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: Cancellation,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver = object : TableauCutSolver {
                override val infeasibleRay: DoubleArray? = null
                override fun solve(warm: Basis?) = FloatLpResult(
                    Basis(intArrayOf(0), Array(model.numVars) { VarStatus.AT_LOWER }),
                    0.0,
                    doubleArrayOf(0.0),
                    DoubleArray(model.n) { Double.NaN },
                    exactState = model.exactState,
                )
                override fun solvePrimal(warm: Basis?) = solve(warm)
                override fun gomoryCuts(maxCuts: Int) = emptyList<Cut>()
                override fun mirCuts(maxCuts: Int) = emptyList<Cut>()
                override fun close() {
                    closed = true
                }
            }
        }
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        assertNull(attempt.solve(source, LpSolveContext(factory)))

        assertTrue(closed)
        assertEquals(LpDualizationDecline.PROJECTION, attempt.metrics.decline)
        assertNull(attempt.certificate)
    }

    @Test
    fun `a truncated auxiliary supplies only its independently checked source bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: Cancellation,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver = object : TableauCutSolver {
                override val infeasibleRay: DoubleArray? = null
                override fun solve(warm: Basis?) = FloatLpResult(
                    Basis(intArrayOf(2), Array(model.numVars) { VarStatus.AT_LOWER }),
                    -6.0,
                    doubleArrayOf(-3.0),
                    doubleArrayOf(0.0, 0.0, 2.0),
                    optimal = false,
                    exactState = model.exactState,
                )
                override fun solvePrimal(warm: Basis?) = solve(warm)
                override fun gomoryCuts(maxCuts: Int) = emptyList<Cut>()
                override fun mirCuts(maxCuts: Int) = emptyList<Cut>()
            }
        }
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        assertNull(attempt.solve(source, LpSolveContext(factory)))
        val result = assertNotNull(certifyDualizedSource(assertNotNull(source.toWorkingModel()), attempt))

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ofLong(6L), result.lowerBound)
        assertNull(result.witness)
    }

    @Test
    fun `primary auxiliary failure survives measurement and cleanup failures`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        var closed = false
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: Cancellation,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver = object : TableauCutSolver {
                override val infeasibleRay: DoubleArray? = null
                override val lastMetrics: LpSolveMetrics get() = error("measurement")
                override fun solve(warm: Basis?): FloatLpResult? = error("primary")
                override fun solvePrimal(warm: Basis?) = solve(warm)
                override fun gomoryCuts(maxCuts: Int) = emptyList<Cut>()
                override fun mirCuts(maxCuts: Int) = emptyList<Cut>()
                override fun close() {
                    closed = true
                    error("cleanup")
                }
            }
        }
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        val failure = assertFailsWith<IllegalStateException> { attempt.solve(source, LpSolveContext(factory)) }

        assertTrue(closed)
        assertEquals("primary", failure.message)
        assertEquals(listOf("measurement", "cleanup"), failure.suppressedExceptions.map { it.message })
        assertTrue(attempt.metrics.constructionWork > 0L)
    }
}
