package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearInputRevisionTest {
    @Test
    fun `only participating inequality endpoints should wake strengthening`() {
        for (column in 0..2) {
            val source = Problem(0, 3, Array(3) { IntDomain(0, 3) }, listOf(
                Linear(intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.LE, 7),
            ))
            val inputs = LinearInputRevision(source, PresolvePass.STRENGTHEN_COEFFICIENTS, Cancellation.Never)
            val before = assertNotNull(inputs.current(source))
            inputs.recordDelta(source.factors, intArrayOf(), emptyList())

            val after = inputs.current { v -> 0L to if (v == column) 2L else 3L }

            assertEquals(column != 2, before != after)
        }
    }

    @Test
    fun `sparsification should ignore domain changes`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 3) }, listOf(
            Linear(intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.EQ, 7),
        ))
        val inputs = LinearInputRevision(source, PresolvePass.SPARSIFY_LINEAR_ROWS, Cancellation.Never)
        val before = assertNotNull(inputs.current(source))
        inputs.recordDelta(source.factors, intArrayOf(), emptyList())

        assertEquals(before, inputs.current { error("sparsification does not read ranges") })
    }

    @Test
    fun `dropped duplicate occurrences should release their last participating range`() {
        val row = Linear(intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.LE, 7)
        val source = Problem(0, 2, Array(2) { IntDomain(0, 3) }, listOf(row, row))
        val inputs = LinearInputRevision(source, PresolvePass.STRENGTHEN_COEFFICIENTS, Cancellation.Never)
        inputs.current(source)
        inputs.recordDelta(source.factors, intArrayOf(0), emptyList())
        val before = assertNotNull(inputs.current(source))
        inputs.recordDelta(source.factors, intArrayOf(), emptyList())
        assertNotEquals(before, inputs.current { 0L to 2L })
        inputs.recordDelta(source.factors, intArrayOf(1), emptyList())
        val empty = assertNotNull(inputs.current(source))
        inputs.recordDelta(source.factors, intArrayOf(), emptyList())

        assertEquals(empty, inputs.current { error("dropped columns must not be retained") })
    }

    @Test
    fun `unrelated factor tracking should charge only the delta visit`() {
        for (pass in listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.SPARSIFY_LINEAR_ROWS)) {
            val source = Problem(2, 2, Array(2) { IntDomain(0, 3) }, listOf(
                Linear(intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.EQ, 7),
            ))
            val budget = PresolveBudget(Long.MAX_VALUE)
            val inputs = LinearInputRevision(source, pass, budget.orSpent(Cancellation.Never))
            val before = assertNotNull(inputs.current(source))
            val spent = budget.spent()

            inputs.recordDelta(source.factors, intArrayOf(), listOf(Clause(intArrayOf(0, 2))), false)

            assertEquals(before, inputs.current(source))
            assertEquals(1L, budget.spent() - spent)
        }
    }

    @Test
    fun `interrupted tracking should spend its containing allowance and publish no revision`() {
        for (pass in listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.SPARSIFY_LINEAR_ROWS)) {
            val source = Problem(0, 2, Array(2) { IntDomain(0, 3) }, listOf(
                Linear(intArrayOf(3, 2), intArrayOf(0, 1), LinearOp.LE, 7),
            ))
            val budget = PresolveBudget(1L)

            val inputs = LinearInputRevision(source, pass, budget.orSpent(Cancellation.Never))

            assertNull(inputs.current(source))
            assertEquals(0L, budget.remaining())
        }
    }
}
