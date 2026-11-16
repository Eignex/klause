package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutColumnLayoutTest {
    @Test
    fun `bindings should belong to the exact map that was checked`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val first = CutSourceMap(Any(), 0, listOf(CutColumnSource(source)))
        val next = CutSourceMap(first.model, 1, first.columns)
        val bound = assertNotNull(CutColumnLayout.capture(first, 64L, ExecutionControl.Never) {})
        val layout = assertNotNull(bound.layoutFor(first))

        assertNull(bound.layoutFor(next))
        assertNotNull(assertNotNull(layout.bind(next, ExecutionControl.Never) {}).layoutFor(next))
    }

    @Test
    fun `affine columns should decline capture`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        for (column in listOf(CutColumnSource(source, scale = BigFraction.ofLong(2)),
            CutColumnSource(source, offset = BigFraction.ONE))) {
            val map = CutSourceMap(Any(), 0, listOf(column))
            assertNull(CutColumnLayout.capture(map, 64L, ExecutionControl.Never) {})
        }
    }

    @Test
    fun `insufficient storage should decline capture with its inspection charged`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val map = CutSourceMap(Any(), 0, listOf(CutColumnSource(source)))
        var work = 0L

        assertNull(CutColumnLayout.capture(map, 9L, ExecutionControl.Never) { work += it })
        assertTrue(work > 0L)
    }

    @Test
    fun `cancellation after validation work should withhold the binding`() {
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val map = CutSourceMap(Any(), 0, listOf(CutColumnSource(source)))
        val bound = assertNotNull(CutColumnLayout.capture(map, 64L, ExecutionControl.Never) {})
        val layout = assertNotNull(bound.layoutFor(map))
        var cancelled = false
        var work = 0L

        val interrupted = layout.bind(map, ExecutionControl { cancelled }) {
            work += it
            cancelled = true
        }

        assertNull(interrupted)
        assertTrue(work > 0L)
        assertNotNull(layout.bind(map, ExecutionControl.Never) {})
    }
}
