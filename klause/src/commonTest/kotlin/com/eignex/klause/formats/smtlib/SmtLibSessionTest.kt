package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmtLibSessionTest {
    @Test
    fun `each query solves the active assertion stack`() {
        val snapshots = ArrayList<String>()
        val replies = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (declare-const p Bool)
            (assert p)
            (check-sat)
            (push 1)
            (assert (not p))
            (check-sat)
            (pop 1)
            (check-sat)
        """.trimIndent()))

        assertEquals(listOf("sat", "sat", "sat"), replies)
        assertTrue("(assert (not p))" in snapshots[1])
        assertEquals(snapshots[0], snapshots[2])
    }

    @Test
    fun `assumptions apply to only their query`() {
        val snapshots = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("unsat") }, {})

        session.execute(SExprReader("""
            (declare-const p Bool)
            (check-sat-assuming ((not p)))
            (check-sat)
        """.trimIndent()))

        assertTrue("(assert (not p))" in snapshots[0])
        assertFalse("(assert (not p))" in snapshots[1])
    }

    @Test
    fun `popped declarations can be redeclared`() {
        val snapshots = ArrayList<String>()
        val replies = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (push 1)
            (declare-const x Int)
            (pop 1)
            (declare-const x Bool)
            (check-sat)
        """.trimIndent()))

        assertEquals(listOf("sat"), replies)
        assertFalse("(declare-const x Int)" in snapshots.single())
        assertTrue("(declare-const x Bool)" in snapshots.single())
    }

    @Test
    fun `global declarations survive pop and reset assertions`() {
        val snapshots = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat") }, {})

        session.execute(SExprReader("""
            (set-option :global-declarations true)
            (push 1)
            (declare-const p Bool)
            (assert p)
            (reset-assertions)
            (check-sat)
        """.trimIndent()))

        assertTrue("(declare-const p Bool)" in snapshots.single())
        assertFalse("(assert p)" in snapshots.single())
    }

    @Test
    fun `model queries require enabled production and a current sat result`() {
        val replies = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "((define-fun p () Bool true))"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (declare-const p Bool)
            (check-sat)
            (get-model)
            (set-option :produce-models true)
            (get-value (p (not p)))
            (push 1)
            (get-model)
        """.trimIndent()))

        assertEquals("sat", replies[0])
        assertTrue(replies[1].startsWith("(error "))
        assertEquals("((p true) ((not p) true))", replies[2])
        assertTrue(replies[3].startsWith("(error "))
    }

    @Test
    fun `unsupported proof production is reported without enabling it`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("unsat") }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (set-option :produce-proofs true)
            (get-option :produce-proofs)
            (get-proof)
        """.trimIndent()))

        assertEquals(listOf("unsupported", "false", "unsupported"), replies)
    }

    @Test
    fun `stack underflow preserves active assertions`() {
        val snapshots = ArrayList<String>()
        val replies = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("unsat") }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (assert false)
            (pop 1)
            (check-sat)
        """.trimIndent()))

        assertTrue(replies.first().startsWith("(error "))
        assertEquals("unsat", replies.last())
        assertTrue("(assert false)" in snapshots.single())
    }

    @Test
    fun `reset clears declarations options and query result`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (set-option :print-success false)
            (declare-const p Bool)
            (reset)
            (declare-const p Int)
            (get-option :produce-models)
        """.trimIndent()))

        assertEquals(listOf("success", "success", "false"), replies)
    }

    @Test
    fun `exit stops reading later commands`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ error("unexpected query") }, replies::add)

        session.execute(SExprReader("(set-option :print-success false) (exit) (check-sat)"))

        assertTrue(replies.isEmpty())
    }

    @Test
    fun `sexpression rendering preserves quoted tokens`() {
        val text = "(echo \"a \"\"quote\"\"\") (declare-const |a (b)| Bool)"
        val commands = SExprReader(text).readAll()

        assertEquals(text, commands.joinToString(" ") { it.smtText() })
    }
}
