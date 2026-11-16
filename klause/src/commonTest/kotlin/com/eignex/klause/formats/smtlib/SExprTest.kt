package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SExprTest {
    @Test
    fun `comments should terminate adjacent bare tokens`() {
        val source = "(assert true; comment with a closing parenthesis )\n)"

        val expressions = SExprReader(source).readAll()

        assertEquals(listOf(SExpr.SList(listOf(SExpr.Atom("assert"), SExpr.Atom("true")))), expressions)
    }

    @Test
    fun `malformed numeral and symbol tokens are rejected immediately`() {
        for (token in listOf("01", "00.1", "1.", "#b102", "#xg", "abc:def", "|a\\b|")) {
            assertFailsWith<UnsupportedSmtException>(token) { SExprReader("(echo $token)").readAll() }
        }
    }

    @Test
    fun `quoted numeric symbols remain distinct from numeral tokens`() {
        val expressions = SExprReader("(|1| 1 |true| true)").readAll()

        assertEquals(listOf(SExpr.SList(listOf(SExpr.Atom("|1|"), SExpr.Atom("1"),
            SExpr.Atom("|true|"), SExpr.Atom("true")))), expressions)
    }
}
