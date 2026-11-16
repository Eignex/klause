package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearRowTest {
    @Test
    fun `Term references should decode back to their variable or literal`() {
        assertTrue(!Term.isBool(Term.ofIntVar(7)))
        assertEquals(7, Term.intVar(Term.ofIntVar(7)))
        val lit = Lit.make(3, false)
        assertTrue(Term.isBool(Term.ofLit(lit)))
        assertEquals(lit, Term.lit(Term.ofLit(lit)))
        assertTrue(Term.isReal(Term.ofRealVar(7)))
        assertTrue(!Term.isInt(Term.ofRealVar(7)))
        assertTrue(!Term.isBool(Term.ofRealVar(7)))
        assertEquals(7, Term.realVar(Term.ofRealVar(7)))
    }
}
