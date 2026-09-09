package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpCaptureTest {
    @Test
    fun `general authority is rejected before capture v1 projection`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.ofIeee(0.1))))),
            emptyList(), ExactLpObjective(listOf(zero)))

        assertFailsWith<IllegalArgumentException> { LpCapturedModel.capture(model) }
        assertFailsWith<IllegalArgumentException> { LpCapture.capture(model, LpReplaySettings("unsupported", 0L), emptyList()) }
    }

    @Test
    fun `checked integral bridge uses unchanged capture v1 bytes`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(listOf(emptyList()), emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L))))),
            emptyList(), ExactLpObjective(listOf(ExactLpNumber.of(3L))))
        val legacy = assertNotNull(model.toLegacy())
        val settings = LpReplaySettings("integral", 0L)

        val bytes = LpCapture.capture(model, settings, emptyList()).encode()

        assertEquals(1, LP_CAPTURE_VERSION)
        assertContentEquals(LpCapture.capture(legacy, settings, emptyList()).encode(), bytes)
        assertContentEquals(bytes, LpCapture.decode(bytes).encode())
    }

    @Test
    fun `capture round trips every authoritative model field losslessly`() {
        val large = (1L shl 53) + 19L
        val nanBits = 0x7ff8000000000042L
        val model = LpModel(
            n = 2,
            m = 2,
            csc = Csc(intArrayOf(0, 2, 3), intArrayOf(0, 1, 1), longArrayOf(large, -7L, 11L)),
            rhs = longArrayOf(large + 1L, Long.MIN_VALUE + 41L),
            cost = longArrayOf(large + 2L, -13L, 0L, 0L),
            upper = longArrayOf(large + 3L, 0L, 0L, Long.MAX_VALUE),
            hasUpper = booleanArrayOf(true, false, false, true),
            loShift = longArrayOf(-large, 17L),
            objConstant = large + 4L,
            sense = Sense.MAXIMIZE,
            tag = intArrayOf(31, -9),
            rowGlobal = booleanArrayOf(false, true),
            rowStrict = booleanArrayOf(true, false),
            rowPremises = arrayOf(
                LpRowPremises(
                    intArrayOf(8, 3),
                    booleanArrayOf(true, false),
                    longArrayOf(large + 5L, -large),
                    intArrayOf(71, -72),
                ),
                null,
            ),
            flippedRhs = longArrayOf(large + 6L, -23L),
            probeClampedLo = booleanArrayOf(true, false),
            probeClampedHi = booleanArrayOf(false, true),
            colContinuous = booleanArrayOf(true, false),
            doubleView = LpDoubleView(
                intArrayOf(0, 1, 3),
                intArrayOf(1, 0, 1),
                doubleArrayOf(Double.fromBits(nanBits), Double.POSITIVE_INFINITY, -0.0),
                doubleArrayOf(Double.NEGATIVE_INFINITY, Double.fromBits(nanBits)),
                doubleArrayOf(-0.0, Double.POSITIVE_INFINITY, 0.0, Double.NaN),
                doubleArrayOf(Double.POSITIVE_INFINITY, -0.0, 0.0, Double.NEGATIVE_INFINITY),
                booleanArrayOf(false, true, false, true),
                Double.fromBits(nanBits),
                doubleArrayOf(-0.0, Double.NEGATIVE_INFINITY),
            ),
        )
        val settings = LpReplaySettings(
            "lossless",
            large,
            LpReplaySolverKind.TABLEAU,
            componentSplit = false,
            cancellationPollLimit = 3,
            pivotLimit = 17,
            workLimit = large + 7L,
            refactorUpdateLimit = 61,
            trackDegeneracy = true,
        )

        val warm = LpCapturedBasis.capture(
            Basis(
                intArrayOf(2, 3),
                arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC, VarStatus.BASIC),
            ),
        )
        val doubleCostBits = longArrayOf(nanBits, (-0.0).toRawBits(), 0L, Double.POSITIVE_INFINITY.toRawBits())
        val decoded = LpCapture.decode(
            LpCapture.capture(
                model,
                settings,
                listOf(
                    LpReplayEvent.Solve(warm),
                    LpReplayEvent.ObjectiveSwap(
                        longArrayOf(1L, 2L, 0L, 0L),
                        doubleCostBits,
                        large + 8L,
                        nanBits,
                    ),
                    LpReplayEvent.Rebind(longArrayOf(0L, 0L), longArrayOf(1L, 1L), 7),
                ),
            ).encode(),
        )
        val restored = decoded.model.toModel()

        assertEquals(large, decoded.settings.seed)
        assertEquals(3, decoded.settings.cancellationPollLimit)
        assertEquals(17, decoded.settings.pivotLimit)
        assertEquals(large + 7L, decoded.settings.workLimit)
        assertEquals(61, decoded.settings.refactorUpdateLimit)
        assertTrue(decoded.settings.trackDegeneracy)
        assertEquals(LpNumericAuthority.IEEE754_BITS_SOURCE_RATIONAL_ABSENT, decoded.model.numericAuthority)
        assertModelEquals(model, restored)
        assertTrue(restored.rowStrict[0])
        assertFalse(restored.rowGlobal[0])
        assertContentEquals(intArrayOf(8, 3), assertNotNull(restored.rowPremises[0]).vars)
        assertNull(restored.rowPremises[1])
        val decodedWarm = assertNotNull((decoded.events[0] as LpReplayEvent.Solve).warm)
        assertContentEquals(warm.basicVars, decodedWarm.basicVars)
        assertContentEquals(warm.statuses, decodedWarm.statuses)
        val objectiveSwap = decoded.events[1] as LpReplayEvent.ObjectiveSwap
        assertContentEquals(doubleCostBits, assertNotNull(objectiveSwap.doubleCostBits))
        assertEquals(nanBits, objectiveSwap.doubleObjConstantBits)
        assertEquals(7, (decoded.events[2] as LpReplayEvent.Rebind).cancellationPollLimit)
    }

    @Test
    fun `capture owns nested arrays after caller mutation`() {
        val premiseVars = intArrayOf(4)
        val premiseSides = booleanArrayOf(true)
        val premiseThresholds = longArrayOf(9L)
        val premiseLits = intArrayOf(12)
        val cost = longArrayOf(3L, 0L)
        val cscValues = longArrayOf(2L)
        val model = LpModel(
            1,
            1,
            Csc(intArrayOf(0, 1), intArrayOf(0), cscValues),
            longArrayOf(7L),
            cost,
            longArrayOf(5L, 0L),
            booleanArrayOf(true, false),
            longArrayOf(2L),
            6L,
            Sense.MINIMIZE,
            intArrayOf(99),
            booleanArrayOf(false),
            booleanArrayOf(false),
            arrayOf(LpRowPremises(premiseVars, premiseSides, premiseThresholds, premiseLits)),
        )
        val rebindLo = longArrayOf(2L)
        val capture = LpCapture.capture(
            model,
            LpReplaySettings("owned", 1L, LpReplaySolverKind.PERSISTENT, componentSplit = false),
            listOf(LpReplayEvent.Rebind(rebindLo, longArrayOf(8L))),
        )

        cscValues[0] = -1L
        cost[0] = -1L
        premiseVars[0] = -1
        premiseSides[0] = false
        premiseThresholds[0] = -1L
        premiseLits[0] = -1
        rebindLo[0] = -1L

        assertContentEquals(longArrayOf(2L), capture.model.colVal)
        assertContentEquals(longArrayOf(3L, 0L), capture.model.cost)
        val premise = assertNotNull(capture.model.rowPremises[0])
        assertContentEquals(intArrayOf(4), premise.vars)
        assertContentEquals(booleanArrayOf(true), premise.isUpper)
        assertContentEquals(longArrayOf(9L), premise.thresholds)
        assertContentEquals(intArrayOf(12), premise.boolLits)
        assertContentEquals(longArrayOf(2L), (capture.events.single() as LpReplayEvent.Rebind).lo)
    }

    @Test
    fun `empty model persists with an explicit Long authority`() {
        val empty = LpBuilder().build(Sense.MINIMIZE)

        val decoded = LpCapture.decode(
            LpCapture.capture(empty, LpReplaySettings("empty", 0L), listOf(LpReplayEvent.Solve())).encode(),
        )

        assertEquals(LpNumericAuthority.LONG_EXACT, decoded.model.numericAuthority)
        assertEquals(0, decoded.model.n)
        assertEquals(0, decoded.model.m)
        assertContentEquals(intArrayOf(0), decoded.model.colPtr)
        assertNull(decoded.model.doubleView)
    }

    @Test
    fun `unknown versions and malformed bytes are rejected`() {
        val capture = LpCapture.capture(
            LpBuilder().build(Sense.MINIMIZE),
            LpReplaySettings("versions", 0L),
            listOf(LpReplayEvent.Solve()),
        )
        val unknownCapture = capture.encode().also { bytes ->
            bytes[8] = 0
            bytes[9] = 0
            bytes[10] = 0
            bytes[11] = 2
        }
        val truncated = capture.encode().copyOf(capture.encode().size - 1)
        val unknownEvent = capture.encode().also { bytes ->
            val eventOffset = bytes.size - 9
            bytes[eventOffset] = 0
            bytes[eventOffset + 1] = 0
            bytes[eventOffset + 2] = 0
            bytes[eventOffset + 3] = 99
        }
        val unknownSolverKind = capture.encode().also { bytes ->
            val solverKindOffset = 8 + 4 + 4 + "versions".encodeToByteArray().size + 8
            bytes[solverKindOffset] = 0
            bytes[solverKindOffset + 1] = 0
            bytes[solverKindOffset + 2] = 0
            bytes[solverKindOffset + 3] = 99
        }

        assertFailsWith<IllegalArgumentException> { LpCapture.decode(unknownCapture) }
        assertFailsWith<IllegalArgumentException> { LpCapture.decode(truncated) }
        assertFails { LpCapture.decode(unknownEvent) }
        assertFails { LpCapture.decode(unknownSolverKind) }
        assertFailsWith<IllegalArgumentException> {
            LpCapture(
                LP_CAPTURE_VERSION,
                capture.settings,
                capture.model,
                listOf(LpReplayEvent.Solve(eventVersion = LP_EVENT_VERSION + 1)),
            ).validateFormat()
        }
    }

    @Test
    fun `malformed sparse columns and warm bases are rejected`() {
        val builder = LpBuilder()
        val x = builder.addOpenAboveVar(0L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(2L), Relation.LE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val duplicateRows = LpCapturedModel.capture(model)
        duplicateRows.rowIdx[1] = duplicateRows.rowIdx[0]
        val strictWithoutDouble = LpCapturedModel.capture(model)
        strictWithoutDouble.rowStrict[0] = true

        assertFailsWith<IllegalArgumentException> { duplicateRows.validate() }
        assertFailsWith<IllegalArgumentException> { strictWithoutDouble.validate() }
        assertFailsWith<IllegalArgumentException> {
            LpCapturedBasis(
                intArrayOf(model.slackCol(0), model.slackCol(1)),
                intArrayOf(3, 1, 1),
            ).toBasis(model.hasUpper, model.m)
        }
    }

    private fun assertModelEquals(expected: LpModel, actual: LpModel) {
        assertEquals(expected.n, actual.n)
        assertEquals(expected.m, actual.m)
        assertContentEquals(expected.csc.colPtr, actual.csc.colPtr)
        assertContentEquals(expected.csc.rowIdx, actual.csc.rowIdx)
        assertContentEquals(expected.csc.colVal, actual.csc.colVal)
        assertContentEquals(expected.rhs, actual.rhs)
        assertContentEquals(expected.cost, actual.cost)
        assertContentEquals(expected.upper, actual.upper)
        assertContentEquals(expected.hasUpper, actual.hasUpper)
        assertContentEquals(expected.loShift, actual.loShift)
        assertEquals(expected.objConstant, actual.objConstant)
        assertEquals(expected.sense, actual.sense)
        assertContentEquals(expected.tag, actual.tag)
        assertContentEquals(expected.rowGlobal, actual.rowGlobal)
        assertContentEquals(expected.rowStrict, actual.rowStrict)
        assertContentEquals(expected.flippedRhs, actual.flippedRhs)
        assertContentEquals(expected.probeClampedLo, actual.probeClampedLo)
        assertContentEquals(expected.probeClampedHi, actual.probeClampedHi)
        assertContentEquals(expected.colContinuous, actual.colContinuous)
        val expectedDouble = assertNotNull(expected.doubleView)
        val actualDouble = assertNotNull(actual.doubleView)
        assertContentEquals(expectedDouble.colPtr, actualDouble.colPtr)
        assertContentEquals(expectedDouble.rowIdx, actualDouble.rowIdx)
        assertContentEquals(expectedDouble.colVal.map(Double::toRawBits), actualDouble.colVal.map(Double::toRawBits))
        assertContentEquals(expectedDouble.rhs.map(Double::toRawBits), actualDouble.rhs.map(Double::toRawBits))
        assertContentEquals(expectedDouble.cost.map(Double::toRawBits), actualDouble.cost.map(Double::toRawBits))
        assertContentEquals(expectedDouble.upper.map(Double::toRawBits), actualDouble.upper.map(Double::toRawBits))
        assertContentEquals(expectedDouble.hasUpper, actualDouble.hasUpper)
        assertEquals(expectedDouble.objConstant.toRawBits(), actualDouble.objConstant.toRawBits())
        assertContentEquals(expectedDouble.loShift.map(Double::toRawBits), actualDouble.loShift.map(Double::toRawBits))
    }
}
