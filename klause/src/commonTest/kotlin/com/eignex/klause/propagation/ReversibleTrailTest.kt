package com.eignex.klause.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct unit tests for the reversible-state trail ([RevInt] / [RevRef] / [RevIntArray] +
 * [PropagationState.mark] / [undoTo]): multiple writes per level must roll back LIFO to each
 * mark's value, across nested marks. This is the substrate every incremental propagator relies on,
 * so it's exercised in isolation here (the Table STR2 port is the end-to-end gate).
 */
class ReversibleTrailTest {

    private fun freshState(): PropagationState {
        val problem = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val s = PropagationState(problem, Assumptions.None)
        s.undoLogging = true
        return s
    }

    @Test
    fun `RevInt restores to each mark across nested levels and repeated writes`() {
        val s = freshState()
        val r = RevInt(s, 10)
        val m0 = s.mark()
        r.set(20)
        r.set(30) // two writes since m0
        val m1 = s.mark()
        r.set(40)
        r.set(50)
        assertEquals(50, r.value)
        s.undoTo(m1)
        assertEquals(30, r.value, "undo to m1 restores the value at m1")
        s.undoTo(m0)
        assertEquals(10, r.value, "undo to m0 restores the original value")
    }

    @Test
    fun `RevIntArray restores per-element across levels`() {
        val s = freshState()
        val a = RevIntArray(s, size = 3, init = 0)
        val m0 = s.mark()
        a[0] = 7
        a[2] = 9
        val m1 = s.mark()
        a[0] = 70
        a[1] = 11
        assertEquals(listOf(70, 11, 9), listOf(a[0], a[1], a[2]))
        s.undoTo(m1)
        assertEquals(listOf(7, 0, 9), listOf(a[0], a[1], a[2]), "undo to m1")
        s.undoTo(m0)
        assertEquals(listOf(0, 0, 0), listOf(a[0], a[1], a[2]), "undo to m0")
    }

    @Test
    fun `RevRef restores reference cell across levels`() {
        val s = freshState()
        val r = RevRef(s, "a")
        val m0 = s.mark()
        r.set("b")
        r.set("c")
        s.undoTo(m0)
        assertEquals("a", r.value)
    }

    @Test
    fun `no-op writes do not grow the trail or change rollback`() {
        val s = freshState()
        val r = RevInt(s, 5)
        val m0 = s.mark()
        r.set(5) // no-op (same value)
        r.set(8)
        s.undoTo(m0)
        assertEquals(5, r.value)
    }
}
