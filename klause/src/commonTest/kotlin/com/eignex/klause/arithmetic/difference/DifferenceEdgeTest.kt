package com.eignex.klause.arithmetic.difference

import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DifferenceEdgeTest {
    @Test
    fun `minimum long coefficient declines without emitting an edge`() {
        val edges = ArrayList<DifferenceEdge>()

        val appended = appendDifferenceEdges(
            intArrayOf(0), { Long.MIN_VALUE }, LinearOp.LE, 0L,
            DifferenceFragment.ZERO, DifferenceEdge.ALWAYS, edges,
        )

        assertFalse(appended)
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `minimum long bound declines when its reverse edge cannot be represented`() {
        for (relation in listOf(LinearOp.GE, LinearOp.EQ)) {
            val edges = ArrayList<DifferenceEdge>()

            val appended = appendDifferenceEdges(
                intArrayOf(0), { 1L }, relation, Long.MIN_VALUE,
                DifferenceFragment.ZERO, DifferenceEdge.ALWAYS, edges,
            )

            assertFalse(appended, relation.toString())
            assertTrue(edges.isEmpty(), relation.toString())
        }
    }
}
