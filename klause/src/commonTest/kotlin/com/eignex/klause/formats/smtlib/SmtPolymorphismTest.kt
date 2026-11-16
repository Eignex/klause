package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtPolymorphismTest {
    @Test
    fun `sort parameters require the Boolean instance of universal assertions`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (declare-const c A)
            (assert (distinct a b c))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `universal reflexive schemas have source certificates`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-fun f (A) A)
            (assert (= (f a) (f a)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `universal Boolean schema tautologies have source certificates`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-fun p (A) Bool)
            (assert (or (p a) (not (p a))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `finite instances alone cannot certify a universal assertion`() {
        val commands = SExprReader("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (assert (= a b))
        """.trimIndent()).readAll()
        val reduction = reduceSmtPolymorphism(captureSmtSource(commands, Cancellation.Never), Cancellation.Never)
        val values = object : SmtModelValues {
            override fun checkpoint(units: Long) = Unit
            override fun boolean(name: String): Boolean = false
            override fun integer(name: String): String = "0"
            override fun real(name: String): String = "0"
            override fun value(name: String): SmtValue = SmtValue.Boolean(false)
        }

        assertFailsWith<SmtSourceCheckingUnavailable> { reduction.checks.single().check(values) }
    }

    @Test
    fun `qualified polymorphic constants retain distinct monomorphic model identities`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (assert (as a Bool))
            (assert (= (as a Int) 3))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment)
        assertEquals(setOf(SmtSort.Bool, SmtSort.Int), model.values.map { it.first.result }.toSet())
        assertTrue(model.values.all { it.first.name == "a" })
        assertTrue(parsed.intVarNames.isEmpty())
        assertTrue(model.toSmtLib().contains("(define-fun a () Int 3)"))
    }

    @Test
    fun `function ranks are inferred from nested concrete arguments`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-fun f (A) A)
            (assert (distinct (f (f 1)) (f (f 1))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `polymorphic macro calls instantiate their own formal sorts`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (define-fun id ((x A)) A x)
            (assert (distinct (id 1) 1))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `sort aliases cannot capture global sort parameters`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-sort-parameter A) (define-sort Bad () A)")
        }
    }

    @Test
    fun `local alias parameters can shadow global sort parameters`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (define-sort Id (A) A)
            (declare-const n (Id Int))
            (assert (= n 4))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }
}
