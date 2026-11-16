package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtQfCommandConformanceTest {
    @Test
    fun `assertion inspection preserves original named and pattern annotations`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ error("unexpected solve") }, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-assertions true)
            (assert (! true :named label :pattern (true)))
            (get-assertions)
        """.trimIndent()))

        assertEquals(listOf("((! true :named label :pattern (true)))"), replies)
    }

    @Test
    fun `echo responses preserve string delimiters and doubled quotes`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ error("unexpected solve") }, replies::add)

        session.execute(SExprReader("(echo \"a \"\"quoted\"\" value\")"))

        assertEquals(listOf("\"a \"\"quoted\"\" value\""), replies)
    }

    @Test
    fun `unknown reasons belong only to the latest unchanged query context`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("unknown", reasonUnknown = "(:resource budget)") }, replies::add)

        session.execute(SExprReader("""
            (check-sat)
            (get-info :reason-unknown)
            (push 1)
            (get-info :reason-unknown)
        """.trimIndent()))

        assertEquals("unknown", replies[0])
        assertEquals("(:reason-unknown (:resource budget))", replies[1])
        assertTrue(replies[2].startsWith("(error "))
    }

    @Test
    fun `disabled optional proof queries report errors after declined enablement`() {
        for ((option, query) in listOf(
            ":produce-proofs" to "get-proof",
            ":produce-unsat-cores" to "get-unsat-core",
            ":produce-unsat-assumptions" to "get-unsat-assumptions",
        )) {
            val replies = ArrayList<String>()
            val session = SmtLibSession({ error("unexpected solve") }, replies::add)

            session.execute(SExprReader("(set-option $option true) (get-option $option) ($query)"))

            assertEquals(listOf("unsupported", "false"), replies.take(2), query)
            assertTrue(replies[2].startsWith("(error "), query)
        }
    }

    @Test
    fun `required error behavior and stack information survive an invalid logic change`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ error("unexpected solve") }, replies::add)

        session.execute(SExprReader("""
            (get-info :error-behavior)
            (set-logic ALL)
            (set-logic QF_BV)
            (get-info :assertion-stack-levels)
            (push 1)
            (get-info :assertion-stack-levels)
            (pop 1)
            (get-info :assertion-stack-levels)
        """.trimIndent()))

        assertEquals("(:error-behavior continued-execution)", replies[0])
        assertTrue(replies[1].startsWith("(error "))
        assertEquals(listOf("(:assertion-stack-levels 0)", "(:assertion-stack-levels 1)",
            "(:assertion-stack-levels 0)"), replies.drop(2))
    }

    @Test
    fun `option and information flags require keyword tokens`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ error("unexpected solve") }, replies::add)

        session.execute(SExprReader("""
            (get-option random-seed)
            (get-info name)
            (set-option print-success true)
            (get-option |:random-seed|)
            (get-info |:name|)
            (set-option |:print-success| true)
        """.trimIndent()))

        assertEquals(6, replies.size)
        assertTrue(replies.all { it.startsWith("(error ") })
    }
}
