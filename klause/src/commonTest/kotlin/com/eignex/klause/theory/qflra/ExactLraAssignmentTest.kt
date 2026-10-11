package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactLraAssignmentTest {
    @Test
    fun `exact real witnesses own their constructor and exported coordinates`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val bools = booleanArrayOf(true)
        val reals = mutableListOf(third)
        val assignment = ExactLraAssignment(bools, reals)
        val copied = assignment.copy()

        bools[0] = false
        reals[0] = BigFraction.ZERO
        assignment.bools[0] = false
        copied.component1()[0] = false

        assertEquals(ExactLraAssignment(booleanArrayOf(true), listOf(third)), assignment)
        assertEquals(assignment, copied)
    }
}
