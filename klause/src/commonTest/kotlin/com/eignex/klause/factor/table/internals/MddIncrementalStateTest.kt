package com.eignex.klause.factor.table.internals

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MddIncrementalStateTest {
    private val diagram = Mdd(
        intArrayOf(0, 1, 2, 3), intArrayOf(1, 2, 3, 1, 1), intArrayOf(0, 6, 15, 30, 36),
        longArrayOf(
            0, 0, 0, 0, 1, 1,
            0, 0, 0, 1, 0, 1, 1, 1, 2,
            0, 0, 0, 1, 0, 0, 1, 1, 0, 2, 0, 0, 2, 1, 0,
            0, 0, 0, 0, 1, 0,
        ),
        0, intArrayOf(0), 3,
    )

    @Test
    fun `path weights should count assignments through branching suffixes and merged prefixes`() {
        val state = PropagationState(
            Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(diagram)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState

        for ((position, expected) in listOf(intArrayOf(2, 8), intArrayOf(6, 4), intArrayOf(6, 4), intArrayOf(5, 5))
            .withIndex()
        ) {
            val counts = assertNotNull(payload.supportCounts(position, longArrayOf(0, 1)) { true })
            assertContentEquals(expected, counts.supporting)
            assertEquals(10, counts.total)
        }
    }

    @Test
    fun `path weights should restore root and sibling counts after a domain removal`() {
        val state = PropagationState(
            Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(diagram)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState
        assertEquals(10, assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true }).total)
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.excludeIntValue(2, 1))
        assertNull(state.runToFixpoint(allFactors = false))
        assertContentEquals(
            intArrayOf(2, 4), assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true }).supporting,
        )

        state.undoTo(root)

        assertContentEquals(
            intArrayOf(2, 8), assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true }).supporting,
        )
        state.currentLevel = 1
        check(state.excludeIntValue(1, 0))
        assertNull(state.runToFixpoint(allFactors = false))
        assertContentEquals(
            intArrayOf(0, 4), assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true }).supporting,
        )
    }

    @Test
    fun `path weights should count interior holes and their undo`() {
        val ternary = Mdd(
            intArrayOf(0), intArrayOf(1, 1), intArrayOf(0, 9),
            longArrayOf(0, 0, 0, 0, 1, 0, 0, 2, 0), 0, intArrayOf(0), 3,
        )
        val state = PropagationState(
            Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(ternary)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState
        val values = longArrayOf(0, 1, 2)
        assertEquals(3, assertNotNull(payload.supportCounts(0, values) { true }).total)
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1))
        assertNull(state.runToFixpoint(allFactors = false))

        val narrowed = assertNotNull(payload.supportCounts(0, values) { true })
        state.undoTo(root)
        val restored = assertNotNull(payload.supportCounts(0, values) { true })

        assertContentEquals(intArrayOf(1, 0, 1), narrowed.supporting)
        assertEquals(2, narrowed.total)
        assertContentEquals(intArrayOf(1, 1, 1), restored.supporting)
        assertEquals(3, restored.total)
    }

    @Test
    fun `interrupted path counting should publish no partial counts and retry`() {
        val state = PropagationState(
            Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(diagram)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState
        assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true })
        check(state.excludeIntValue(3, 1))
        assertNull(state.runToFixpoint(allFactors = false))
        var visits = 0

        assertNull(payload.supportCounts(0, longArrayOf(0, 1)) { ++visits < 12 })

        assertContentEquals(
            intArrayOf(1, 4), assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true }).supporting,
        )
    }

    @Test
    fun `ambiguous diagrams should retain edge count fallback`() {
        val ambiguous = Mdd(
            intArrayOf(0, 1), intArrayOf(1, 2, 1), intArrayOf(0, 9, 15),
            longArrayOf(0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 0), 0, intArrayOf(0), 3,
        )
        val state = PropagationState(
            Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(ambiguous)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState

        val counts = assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true })

        assertContentEquals(intArrayOf(2, 1), counts.supporting)
        assertEquals(3, counts.total)
    }

    @Test
    fun `aliased diagrams should retain edge count fallback`() {
        val aliased = Mdd(
            intArrayOf(0, 0), intArrayOf(1, 2, 1), intArrayOf(0, 6, 12),
            longArrayOf(0, 0, 0, 0, 1, 1, 0, 0, 0, 1, 1, 0), 0, intArrayOf(0), 3,
        )
        val state = PropagationState(
            Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(aliased)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState

        val counts = assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true })

        assertContentEquals(intArrayOf(1, 1), counts.supporting)
        assertEquals(0, state.mddPathWeightCells)
    }

    @Test
    fun `storage declines should retain edge counts without allocating path weights`() {
        val state = PropagationState(
            Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(diagram)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        state.mddPathWeightCells = 1_048_576
        val payload = state.refPayload[0] as MddIncrementalState

        val counts = assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true })

        assertContentEquals(intArrayOf(1, 1), counts.supporting)
        assertEquals(1_048_576, state.mddPathWeightCells)
    }

    @Test
    fun `overflowing path counts should retain edge counts without wrapping`() {
        val layers = 32
        val large = Mdd(
            IntArray(layers) { it }, IntArray(layers + 1) { 1 }, IntArray(layers + 1) { it * 6 },
            LongArray(layers * 6) { if (it % 6 == 4) 1 else 0 }, 0, intArrayOf(0), 3,
        )
        val state = PropagationState(
            Problem(0, layers, Array(layers) { IntDomain(0, 1) }, arrayOf<Factor>(large)), Assumptions.None,
        )
        assertNull(state.runToFixpoint(allFactors = true))
        val payload = state.refPayload[0] as MddIncrementalState

        val counts = assertNotNull(payload.supportCounts(0, longArrayOf(0, 1)) { true })

        assertContentEquals(intArrayOf(1, 1), counts.supporting)
        assertEquals(2, counts.total)
    }
}
