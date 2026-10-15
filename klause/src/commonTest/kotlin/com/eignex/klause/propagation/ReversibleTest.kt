package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class ReversibleTest {
    private fun freshState(): PropagationState {
        val problem = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val s = PropagationState(problem, Assumptions.None)
        s.undoLogging = true
        return s
    }

    @Test
    fun `RevInt should restore to each mark across nested levels and repeated writes`() {
        val s = freshState()
        val r = RevInt(s, 10)
        val m0 = s.mark()
        r.set(20)
        r.set(30)
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
    fun `RevIntArray should restore per element across levels`() {
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
    fun `RevRef should restore its reference across levels`() {
        val s = freshState()
        val r = RevRef(s, "a")
        val m0 = s.mark()
        r.set("b")
        r.set("c")
        s.undoTo(m0)
        assertEquals("a", r.value)
    }
}
