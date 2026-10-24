package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParityInputRevisionTest {
    @Test
    fun `only odd equality columns should invalidate parity on a range change`() {
        for (column in 0..2) {
            val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, listOf(
                Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.EQ, 1),
            ))
            val inputs = ParityInputRevision(source, Cancellation.Never)
            val before = assertNotNull(inputs.current(source))
            val delta = SourceDelta(bounds = source.intBounds.tightening().also { it.atMost(column, 1) }.build())
            val changed = assertNotNull(source.withSourcePassDelta(delta))

            inputs.recordDelta(source.factors, delta.droppedIndices, delta.addedFactors)
            val after = assertNotNull(inputs.current(changed))

            assertEquals(column == 0, before != after)
        }
    }

    @Test
    fun `only new participating unit pins should invalidate parity`() {
        val source = Problem(4, 0, emptyArray(), listOf(Xor(intArrayOf(0, 2, 4), 0)))
        val inputs = ParityInputRevision(source, Cancellation.Never)
        val before = assertNotNull(inputs.current(source))
        val unrelated = SourceDelta(addedFactors = listOf(Clause(intArrayOf(Lit.make(3, true)))))
        val once = assertNotNull(source.withSourcePassDelta(unrelated))
        inputs.recordDelta(source.factors, unrelated.droppedIndices, unrelated.addedFactors)
        assertEquals(before, inputs.current(once))
        val pin = SourceDelta(addedFactors = listOf(Clause(intArrayOf(Lit.make(2, true)))))
        val twice = assertNotNull(once.withSourcePassDelta(pin))

        inputs.recordDelta(once.factors, pin.droppedIndices, pin.addedFactors)
        val after = assertNotNull(inputs.current(twice))

        assertNotEquals(before, after)
        inputs.recordDelta(twice.factors, pin.droppedIndices, pin.addedFactors)
        assertEquals(after, inputs.current(assertNotNull(twice.withSourcePassDelta(pin))))
    }

    @Test
    fun `finite root pins should invalidate parity without a new unit factor`() {
        val source = Problem(3, 0, emptyArray(), listOf(Xor(intArrayOf(0, 2, 4), 0)))
        val inputs = ParityInputRevision(source, Cancellation.Never)
        val before = assertNotNull(inputs.current(source))

        inputs.recordDelta(source.factors, intArrayOf(), emptyList())
        val after = inputs.current({ v -> if (v == 2) true else null }) { null to null }

        assertNotEquals(before, after)
        assertEquals(after, inputs.current({ v -> if (v == 2) true else null }) { null to null })
    }

    @Test
    fun `a unit matching an established root pin should keep the input revision`() {
        val source = Problem(3, 0, emptyArray(), listOf(Xor(intArrayOf(0, 2, 4), 0)))
        val inputs = ParityInputRevision(source, Cancellation.Never)
        val before = inputs.current({ v -> if (v == 2) true else null }) { null to null }

        inputs.recordDelta(source.factors, intArrayOf(), listOf(Clause(intArrayOf(Lit.make(2, true)))))
        val after = inputs.current({ v -> if (v == 2) true else null }) { null to null }

        assertEquals(before, after)
    }

    @Test
    fun `changed parity rows should invalidate the input revision`() {
        val source = Problem(3, 0, emptyArray(), listOf(Xor(intArrayOf(0, 2, 4), 0)))
        val inputs = ParityInputRevision(source, Cancellation.Never)
        val before = assertNotNull(inputs.current(source))
        val delta = SourceDelta(droppedIndices = intArrayOf(0), addedFactors = listOf(Xor(intArrayOf(0, 2, 4), 1)))

        inputs.recordDelta(source.factors, delta.droppedIndices, delta.addedFactors)

        assertNotEquals(before, inputs.current(assertNotNull(source.withSourcePassDelta(delta))))
    }

    @Test
    fun `interrupted premise tracking should spend the shared allowance without publishing a revision`() {
        val source = Problem(3, 0, emptyArray(), listOf(Xor(intArrayOf(0, 2, 4), 0)))
        val budget = PresolveBudget(1L)

        val inputs = ParityInputRevision(source, budget.orSpent(Cancellation.Never))

        assertNull(inputs.current(source))
        assertEquals(0L, budget.remaining())
    }
}
