package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmtSessionValidationTest {
    @Test
    fun `named assumption terms introduce definitions without retaining the assumption`() {
        val snapshots = ArrayList<String>()
        val replies = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (check-sat-assuming ((! true :named label)))
            (assert label)
            (check-sat)
        """.trimIndent()))

        assertEquals(listOf("sat", "sat"), replies)
        assertTrue("(define-fun label () Bool true)" in snapshots.last())
        assertTrue("(assert true)" !in snapshots.last())
    }

    @Test
    fun `closed named terms in recursive definitions enter the command scope`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (define-fun-rec f ((x Int)) Int (ite (! true :named ready) 0 (f x)))
            (assert ready)
            (check-sat)
        """.trimIndent()))

        assertEquals(listOf("sat"), replies)
    }

    @Test
    fun `named terms cannot capture match variables with global names`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("""
            (declare-const y Int)
            (declare-datatype D ((box (field Int))))
            (define-fun f ((x D)) Int (match x (((box y) (! y :named captured)))))
            (assert (= captured 0))
        """.trimIndent()))

        assertEquals(2, replies.size)
        assertTrue(replies.all { it.startsWith("(error ") })
    }

    @Test
    fun `invalid assertions preserve the previous checked model`() {
        val replies = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "((define-fun p () Bool true))"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-models true)
            (declare-const p Bool)
            (check-sat)
            (assert 1)
            (get-model)
        """.trimIndent()))

        assertEquals("sat", replies[0])
        assertTrue(replies[1].startsWith("(error "))
        assertEquals(model.render(), replies[2])
    }

    @Test
    fun `later declarations cannot repair an earlier undefined assertion`() {
        val replies = ArrayList<String>()
        val snapshots = ArrayList<String>()
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat") }, replies::add)

        session.execute(SExprReader("(assert p) (declare-const p Bool) (check-sat)"))

        assertTrue(replies[0].startsWith("(error "))
        assertEquals("sat", replies[1])
        assertTrue("(assert p)" !in snapshots.single())
    }

    @Test
    fun `named definitions survive global assertion scopes`() {
        val replies = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "()"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :global-declarations true)
            (set-option :produce-assignments true)
            (push 1)
            (assert (! true :named label))
            (pop 1)
            (check-sat)
            (get-assignment)
        """.trimIndent()))

        assertEquals(listOf("sat", "((label true))"), replies)
    }

    @Test
    fun `named value query definitions survive global scope pops`() {
        val replies = ArrayList<String>()
        val snapshots = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "()"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ snapshots += it; SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :global-declarations true)
            (set-option :produce-models true)
            (push 1)
            (check-sat)
            (get-value ((! true :named label)))
            (pop 1)
            (assert label)
            (check-sat)
        """.trimIndent()))

        assertEquals(listOf("sat", "(((! true :named label) true))", "sat"), replies)
        assertTrue("(define-fun label () Bool true)" in snapshots.last())
        assertTrue("(assert label)" in snapshots.last())
    }

    @Test
    fun `named Boolean and arithmetic terms keep their distinct assignment roles`() {
        val replies = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "()"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-assignments true)
            (assert (! (= (! 1 :named one) 1) :named proposition))
            (check-sat)
            (get-assignment)
        """.trimIndent()))

        assertEquals(listOf("sat", "((proposition true))"), replies)
    }

    @Test
    fun `malformed model queries preserve the checked interpretation`() {
        val replies = ArrayList<String>()
        val model = object : SmtSessionModel {
            override fun render(): String = "()"
            override fun evaluate(term: SExpr): String = "true"
        }
        val session = SmtLibSession({ SmtSessionResult("sat", model) }, replies::add)

        session.execute(SExprReader("""
            (set-option :produce-models true)
            (declare-const p Bool)
            (check-sat)
            (get-value ((not p p)))
            (get-value (p))
        """.trimIndent()))

        assertTrue(replies[1].startsWith("(error "))
        assertEquals("((p true))", replies[2])
    }

    @Test
    fun `metadata does not override solver identity or unknown option defaults`() {
        val replies = ArrayList<String>()
        val session = SmtLibSession({ SmtSessionResult("sat") }, replies::add,
            version = "1.2.3", authors = "Example Authors")

        session.execute(SExprReader("""
            (set-info :name "arbitrary metadata")
            (set-info :custom "metadata")
            (get-info :name)
            (get-info :version)
            (get-info :authors)
            (get-info :custom)
            (get-option :random-seed)
            (set-option :random-seed 0)
            (get-option :reproducible-resource-limit)
        """.trimIndent()))

        assertEquals(listOf("(:name \"klause\")", "(:version \"1.2.3\")",
            "(:authors \"Example Authors\")", "unsupported", "0", "unsupported", "0"), replies)
    }
}
