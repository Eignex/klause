package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals

class SExprReaderTest {
    @Test
    fun `comments should terminate adjacent bare tokens`() {
        val source = "(assert true; comment with a closing parenthesis )\n)"

        val expressions = SExprReader(source).readAll()

        assertEquals(listOf(SExpr.SList(listOf(SExpr.Atom("assert"), SExpr.Atom("true")))), expressions)
    }
}
