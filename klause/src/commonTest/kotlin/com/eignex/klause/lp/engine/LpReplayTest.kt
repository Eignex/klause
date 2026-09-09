package com.eignex.klause.lp.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpReplayTest {
    @Test
    fun `persistent replay executes solve rebind and resolve through the seam`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            persistentSettings("rebind"),
            listOf(
                LpReplayEvent.Solve(),
                LpReplayEvent.Rebind(longArrayOf(5L), longArrayOf(10L)),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val report = LpReplay.replay(LpCapture.decode(capture.encode()))

        assertEquals(3, report.steps.size)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, report.steps[0].productionVerdict)
        assertEquals(3.0, Double.fromBits(assertNotNull(report.steps[0].objectiveBits)), 1e-9)
        assertEquals(LpReplayOperation.REBIND, report.steps[1].operation)
        assertNull(report.steps[1].productionVerdict)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, report.steps[2].productionVerdict)
        assertEquals(5.0, Double.fromBits(assertNotNull(report.steps[2].objectiveBits)), 1e-9)
        assertTrue(report.steps[2].metrics.warmHits > 0)
        assertTrue(report.steps[2].hasCertifiedBound)
        val integer = assertNotNull(report.steps[2].certifiers.singleOrNull { it.certifier == LpCertifier.INTEGER })
        assertEquals(1, integer.attempts)
        assertEquals(0, integer.declines)
    }

    @Test
    fun `gated replay deactivates a row only through the supported persistent operation`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            persistentSettings("gated"),
            listOf(
                LpReplayEvent.Solve(),
                LpReplayEvent.ResolveGated(booleanArrayOf(false)),
                LpReplayEvent.ResolveGated(booleanArrayOf(true)),
            ),
        )

        val report = LpReplay.replay(capture)

        assertEquals(LpVerdict.INFEASIBLE, report.steps[0].productionVerdict)
        assertTrue(report.steps[0].hasInfeasibilityProof)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[1].productionVerdict)
        assertEquals(LpCandidateKind.FLOAT_OPTIMUM, report.steps[1].candidate)
        assertFalse(report.steps[1].hasCertifiedBound)
        assertEquals(LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE, report.steps[1].certificationCapability)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[2].productionVerdict)
        assertEquals(LpCandidateKind.FLOAT_INFEASIBILITY, report.steps[2].candidate)
        assertFalse(report.steps[2].hasInfeasibilityProof)
    }

    @Test
    fun `warm basis and primal solve remain discrete portable events`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val basis = newLpSolver(model, componentSplit = false).use { assertNotNull(it.solve()).basis }
        val capture = LpCapture.capture(
            model,
            LpReplaySettings("warm", 7L, LpReplaySolverKind.TABLEAU, componentSplit = false),
            listOf(LpReplayEvent.SolvePrimal(LpCapturedBasis.capture(basis))),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpReplayOperation.SOLVE_PRIMAL, step.operation)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
        assertEquals(1, step.metrics.warmAttempts)
    }

    @Test
    fun `cancellation is replayed as an indeterminate result rather than a verdict`() {
        val capture = LpCapture.capture(
            pivotModel(160),
            LpReplaySettings(
                "cancel",
                19L,
                LpReplaySolverKind.TABLEAU,
                componentSplit = false,
                cancellationPollLimit = 1,
            ),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.INDETERMINATE, step.productionVerdict)
        assertFalse(step.hasCertifiedBound)
        assertFalse(step.hasFeasibleWitness)
        assertFalse(step.hasInfeasibilityProof)
    }

    @Test
    fun `rebind without an override preserves the lifetime cancellation budget`() {
        val model = pivotModel(160)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings(
                "cancel-rebind",
                20L,
                LpReplaySolverKind.PERSISTENT,
                componentSplit = false,
                cancellationPollLimit = 1,
            ),
            listOf(
                LpReplayEvent.Rebind(LongArray(model.n), LongArray(model.n) { 9L }),
                LpReplayEvent.ResolveBounds(),
            ),
        )

        val report = LpReplay.replay(LpCapture.decode(capture.encode()))

        assertEquals(LpReplayOperation.REBIND, report.steps[0].operation)
        assertEquals(LpVerdict.INDETERMINATE, report.steps[1].productionVerdict)
        assertFalse(report.steps[1].hasCertifiedBound)
    }

    @Test
    fun `strict rational refutation records its exact infeasibility proof`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(-1.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 0.0, strict = true)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            LpReplaySettings("strict-refutation", 21L, componentSplit = false),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.INFEASIBLE, step.productionVerdict)
        assertTrue(step.hasInfeasibilityProof)
        assertTrue(step.certifiers.any { it.certifier == LpCertifier.RATIONAL && it.successes == 1 })
    }

    @Test
    fun `component replay retains the exact assembled lower bound`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 3L)
        val capture = LpCapture.capture(
            builder.build(Sense.MINIMIZE),
            LpReplaySettings("components", 22L),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, step.productionVerdict)
        assertEquals(5L, step.integerObjectiveLowerBound)
        assertTrue(step.hasCertifiedBound)
    }

    @Test
    fun `pivot budget reports a certified bound without promoting the candidate to optimum`() {
        val capture = LpCapture.capture(
            coveringModel(),
            LpReplaySettings(
                "pivot-bound",
                23L,
                LpReplaySolverKind.TABLEAU,
                componentSplit = false,
                pivotLimit = 1,
            ),
            listOf(LpReplayEvent.Solve()),
        )

        val step = LpReplay.replay(capture).steps.single()

        assertEquals(LpCandidateKind.FLOAT_BOUND, step.candidate)
        assertEquals(LpVerdict.CERTIFIED_BOUND, step.productionVerdict)
        assertTrue(step.hasCertifiedBound)
        assertFalse(step.hasFeasibleWitness)
        assertTrue(step.metrics.pivots <= 1)
    }

    @Test
    fun `known future events are persisted but rejected before execution`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val futureEvents = listOf<LpReplayEvent>(
            LpReplayEvent.BoundWrite(0, true, 1L, false, 2),
            LpReplayEvent.Push(1),
            LpReplayEvent.Pop(0),
            LpReplayEvent.RowActivation(0, false),
            LpReplayEvent.RowsAppended(LpCapturedModel.capture(model)),
            LpReplayEvent.ObjectiveSwap(LongArray(2), null, 0L, null),
            LpReplayEvent.Epoch(1L, 2L, 3L, 4L),
        )
        for (future in futureEvents) {
            val shaped = shapeFutureEvent(future)
            val capture = LpCapture.capture(
                model,
                persistentSettings("future"),
                listOf(LpReplayEvent.Solve(), shaped),
            )
            var validations = 0

            assertFails {
                LpReplay.replay(LpCapture.decode(capture.encode())) { _, _ ->
                    validations++
                    LpIndependentCheck(LpIndependentValidation.VALIDATED, LpIndependentClaim.PROVED_OPTIMUM)
                }
            }
            assertEquals(0, validations, "${future::class.simpleName} executed a prefix before rejection")
        }
    }

    @Test
    fun `malformed model and unsupported continuous rebind fail before validation`() {
        val valid = LpCapturedModel.capture(LpBuilder().build(Sense.MINIMIZE))
        val malformed = LpCapturedModel(
            valid.n,
            valid.m,
            intArrayOf(1),
            valid.rowIdx,
            valid.colVal,
            valid.rhs,
            valid.cost,
            valid.upper,
            valid.hasUpper,
            valid.loShift,
            valid.objConstant,
            valid.sense,
            valid.tag,
            valid.rowGlobal,
            valid.rowStrict,
            valid.rowPremises,
            valid.flippedRhs,
            valid.probeClampedLo,
            valid.probeClampedHi,
            valid.colContinuous,
            valid.numericAuthority,
            valid.doubleView,
        )
        assertFails {
            LpReplay.replay(LpCapture(LP_CAPTURE_VERSION, persistentSettings("bad"), malformed, emptyList()))
        }

        assertFails {
            LpReplay.replay(
                LpCapture.capture(
                    valid.toModel(),
                    LpReplaySettings(
                        "tableau-refactor",
                        0L,
                        LpReplaySolverKind.TABLEAU,
                        componentSplit = false,
                        refactorUpdateLimit = DEFAULT_REFACTOR_UPDATE_LIMIT + 1,
                    ),
                    emptyList(),
                ),
            )
        }

        val integerBuilder = LpBuilder()
        integerBuilder.addVar(0L, 1L)
        val inconsistent = LpCapture.capture(
            integerBuilder.build(Sense.MINIMIZE),
            persistentSettings("bad-continuous-flag"),
            emptyList(),
        )
        inconsistent.model.colContinuous[0] = true
        assertFails { LpReplay.replay(inconsistent) }

        val realBuilder = LpBuilder()
        realBuilder.addRealVar(0.0, 1.0)
        val real = realBuilder.build(Sense.MINIMIZE)
        assertFails {
            LpReplay.replay(
                LpCapture.capture(
                    real,
                    persistentSettings("real-rebind"),
                    listOf(LpReplayEvent.Rebind(longArrayOf(0L), longArrayOf(1L))),
                ),
            )
        }
    }

    private fun persistentSettings(label: String): LpReplaySettings = LpReplaySettings(
        label,
        0x4c50573034L,
        LpReplaySolverKind.PERSISTENT,
        componentSplit = false,
    )

    private fun pivotModel(columns: Int): LpModel {
        val random = Random(7)
        val builder = LpBuilder()
        val vars = IntArray(columns) { builder.addVar(0L, 9L, random.nextLong(1L, 6L)) }
        repeat(columns / 2) { row ->
            builder.addRow(
                IntArray(4) { vars[(row * 3 + it) % columns] },
                LongArray(4) { 1L },
                Relation.GE,
                random.nextLong(2L, 8L),
            )
        }
        return builder.build(Sense.MINIMIZE)
    }

    private fun coveringModel(): LpModel {
        val builder = LpBuilder()
        val variables = IntArray(4) { builder.addVar(0L, 10L, cost = 1L) }
        builder.addRow(intArrayOf(variables[0], variables[1]), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(variables[1], variables[2]), longArrayOf(1L, 1L), Relation.GE, 4L)
        builder.addRow(intArrayOf(variables[2], variables[3]), longArrayOf(1L, 1L), Relation.GE, 5L)
        builder.addRow(intArrayOf(variables[0], variables[3]), longArrayOf(1L, 1L), Relation.GE, 2L)
        return builder.build(Sense.MINIMIZE)
    }

    private fun shapeFutureEvent(event: LpReplayEvent): LpReplayEvent = when (event) {
        is LpReplayEvent.BoundWrite -> LpReplayEvent.BoundWrite(0, true, 1L, false, 2)
        is LpReplayEvent.RowActivation -> LpReplayEvent.RowActivation(0, false)
        else -> event
    }
}
