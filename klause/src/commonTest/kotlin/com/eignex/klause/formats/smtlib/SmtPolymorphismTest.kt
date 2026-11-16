package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtPolymorphismTest {
    @Test
    fun `unused definitions preserve universal parameters absent from their ranks`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (define-fun p () Bool (= a b))
            (assert (= (as a Bool) (as b Bool)))
            (assert (distinct (as a Int) (as b Int)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `unused universal definitions admit coherent shared Boolean results`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-const b A)
            (define-fun p () Bool (= a b))
            (assert (= (as a Bool) (as b Bool)))
            (assert (= (as a Int) (as b Int)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `concrete macro calls do not retain vacuous universal type obligations`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (define-fun id ((x A)) A x)
            (assert (= (id 1) 1))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `polymorphic rank inference promotes scalar numeric arguments together`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-fun f (A A) A)
            (assert (distinct (f 1 2.0) ((as f Real) 1.0 2)))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `numeric subtyping never coerces invariant array sorts`() {
        val commands = SExprReader("""
            (declare-const a (Array Int Int))
            (declare-fun f ((Array Real Int)) Bool)
            (assert (f a))
        """.trimIndent()).readAll()

        assertFailsWith<UnsupportedSmtException> { captureSmtSource(commands, ExecutionControl.Never) }
    }


    @Test
    fun `fixed polymorphic families instantiate unobserved wide bitvector ranks`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (declare-fun f (A) A)
            (assert (= (f a) a))
        """.trimIndent())
        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(parsed.model).solve()).assignment
        val family = parsed.originalSource!!.polymorphicModel!!
        val sort = SmtSort.BitVector(129)

        val result = withSmtModelValues(parsed, assignment::boolValue, assignment::intValue,
            assignment::realValue, ExecutionControl.Never) { values, _ ->
            val constant = family.instantiate("a", emptyList(), sort, values)
            val function = family.instantiate("f", listOf(sort), sort, values)
            constant to function
        }

        val constant = assertIs<SmtValue.BitVector>(result.first)
        val function = assertIs<SmtValue.Function>(result.second)
        assertEquals(129, constant.bits.length)
        assertEquals(constant, function.entries.single { it.first == listOf(constant) }.second)
    }


    @Test
    fun `universal models share interpretations when instantiated ranks coincide`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-sort-parameter B)
            (declare-const a A)
            (declare-fun p (A) Bool)
            (assert (= (p a) (p (as a B))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `coincident ranks cannot receive conflicting polymorphic interpretations`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-sort-parameter B)
            (declare-const a A)
            (declare-fun p (A) Bool)
            (assert (distinct (p a) (p (as a B))))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `constructive schema checks expand macro bodies with their formal bindings`() {
        val parsed = SmtLib.parse("""
            (declare-sort-parameter A)
            (declare-const a A)
            (define-fun id ((x A)) A x)
            (assert (= (id a) a))
        """.trimIndent())

        val result = BacktrackSolver.source(parsed.model).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        val model = parsed.reconstructModel(assignment)
        assertTrue(model.values.none { it.first.name == "id" })
    }


    @Test
    fun `constructive universal models transport a Boolean equality pattern`() {
        val cases = listOf("(= a b)", "(distinct a b)", "(= (f a) a)", "(p a)")
        for (body in cases) {
            val parsed = SmtLib.parse("""
                (declare-sort-parameter A)
                (declare-const a A)
                (declare-const b A)
                (declare-fun f (A) A)
                (declare-fun p (A) Bool)
                (assert $body)
            """.trimIndent())

            val result = BacktrackSolver.source(parsed.model).solve()

            val assignment = assertIs<OpenTheoryResult.Sat>(result, body).assignment
            val model = parsed.reconstructModel(assignment)
            val integers = model.values.filter { it.first.result == SmtSort.Int && it.first.parameters.isEmpty() }
            assertTrue(integers.all { it.second in listOf(SmtValue.Integer("0"), SmtValue.Integer("1")) })
        }
    }


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
            (declare-fun size (A) Int)
            (assert (>= (size a) 0))
        """.trimIndent()).readAll()
        val reduction = reduceSmtPolymorphism(captureSmtSource(commands, ExecutionControl.Never),
            ExecutionControl.Never)
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
