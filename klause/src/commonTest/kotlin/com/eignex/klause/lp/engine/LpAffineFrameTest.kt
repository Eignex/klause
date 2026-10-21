package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationBudget
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpAffineFrameTest {
    @Test
    fun `a lifted primal should preserve original origins and fixed logical costs`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(ExactLpNumber.of(5L)),
            listOf(
                ExactLpColumn(ExactLpBounds(), origin = ExactLpNumber.of(10L)),
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(4L))),
                    origin = ExactLpNumber.of(20L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(three), ExactLpSide(three))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(
                listOf(ExactLpNumber.of(2L), three, ExactLpNumber.of(7L)),
                ExactLpNumber.of(11L),
                ExactLpNumber.of(2L),
                ExactLpNumber.of(13L),
            ),
        )
        val state = LpExactState(source)
        val budget = ContinuationBudget(ExactContinuationLimits(), Cancellation.Never)
        val frame = assertNotNull(LpAffineFrame.capture(state, budget))

        val lifted = frame.liftPrimal(listOf(BigFraction.ofLong(21L)), budget)
        val checked = verifyRationalCertificate(assertNotNull(state.toWorkingModel()), sourcePrimal = lifted)

        assertEquals(listOf(BigFraction.ofLong(13L), BigFraction.ofLong(21L)), assertNotNull(checked.witness).primal)
        assertEquals(frame.model.objective.sourceValue(listOf(BigFraction.ONE)), checked.witness.objective)
    }

    @Test
    fun `lifted duals should preserve the original objective bound`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null, cost = 2L)
                addVar(1L, 4L, cost = 3L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 5L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val state = LpExactState(source)
        val budget = ContinuationBudget(ExactContinuationLimits(), Cancellation.Never)
        val frame = assertNotNull(LpAffineFrame.capture(state, budget))

        val multipliers = frame.liftMultipliers(emptyList(), ray = false, budget)
        val checked = verifyRationalCertificate(assertNotNull(state.toWorkingModel()), duals = multipliers)
        val reduced = verifyRationalCertificate(
            assertNotNull(LpExactState(frame.model).toWorkingModel()),
            duals = emptyList(),
        )

        assertEquals(assertNotNull(reduced.bound).value, assertNotNull(checked.bound).value)
        assertEquals(listOf(BigFraction.ofLong(2L)), checked.bound.dualMultipliers)
    }

    @Test
    fun `lifted Farkas multipliers should cite original scoped rows and bounds`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, one), ExactLpEntry(1, one)),
                listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))),
            ),
            listOf(zero, zero),
            List(4) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(9))), ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(1, false, ExactLpSide(one), 11L))
        assertTrue(trail.assertBound(2, false, ExactLpSide(zero), 12L))
        assertTrue(trail.assertBound(2, true, ExactLpSide(zero), 13L))
        assertTrue(trail.assertBound(3, false, ExactLpSide(zero), 14L))
        val state = trail.state
        val budget = ContinuationBudget(ExactContinuationLimits(), Cancellation.Never)
        val frame = assertNotNull(LpAffineFrame.capture(state, budget))

        val ray = frame.liftMultipliers(listOf(BigFraction.MINUS_ONE), ray = true, budget)
        val checked = verifyRationalCertificate(assertNotNull(state.toWorkingModel()), ray = ray)

        assertNotNull(checked.conflict)
        val support = assertNotNull(checked.conflictSupport)
        assertTrue(support.state === state)
        assertEquals(setOf(11L, 13L, 14L), support.sides.map { it.witness }.toSet())
        assertEquals(listOf(9), assertNotNull(support.rows.first { it.first == 0 }.second.premises).literalEntries())
    }

    @Test
    fun `a bounded pivot should remain in the original layout`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addVar(0L, 4L)
                addVar(0L, 4L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val state = LpExactState(source)

        val frame = LpAffineFrame.capture(state, ContinuationBudget(ExactContinuationLimits(), Cancellation.Never))

        assertNull(frame)
        assertEquals(source, state.baseModel)
    }

    @Test
    fun `stopped frame construction should publish no layout`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null)
                addVar(0L, 4L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val cases = listOf(
            Triple(ExactContinuationLimits(maxWork = 0L), Cancellation.Never, ContinuationDecline.WORK),
            Triple(ExactContinuationLimits(maxAllocation = 0L), Cancellation.Never, ContinuationDecline.ALLOCATION),
            Triple(ExactContinuationLimits(), Cancellation { true }, ContinuationDecline.CANCELLED),
        )
        for ((limits, cancellation, reason) in cases) {
            val stopped = assertFailsWith<ContinuationStop> {
                LpAffineFrame.capture(LpExactState(source), ContinuationBudget(limits, cancellation))
            }

            assertEquals(reason, stopped.reason)
        }
    }

    @Test
    fun `a private frame should keep the original verification coordinate guard`() {
        val source = assertNotNull(
            LpBuilder().apply {
                repeat(ReconstructionLimits().maxCoordinates) { addFreeVar(null, null) }
                addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )

        val frame = LpAffineFrame.capture(
            LpExactState(source),
            ContinuationBudget(ExactContinuationLimits(), Cancellation.Never),
        )

        assertNull(frame)
    }
}
