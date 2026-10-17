package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialGraphSubstitutionTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `shared triangular graphs reconstruct within the native allowance`() {
        val declarations = (0..64).joinToString(" ") { "(declare-const x$it Real)" }
        val equations = (2..64).joinToString(" ") { "(assert (= x$it (+ x${it - 1} 1)))" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= x1 (* x0 x0))) $equations (assert (> x64 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(witness.realValue(1), algebraic.power(witness.realValue(0), 2)))
        for (id in 2..64) {
            assertEquals(0, algebraic.compare(witness.realValue(id),
                algebraic.add(witness.realValue(id - 1), algebraic.rational(BigFraction.ONE))))
        }
        assertTrue(algebraic.sign(witness.realValue(64)) > 0)
    }

    @Test
    fun `triangular graph elimination retains unselected equations`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for (equations in listOf(
            "(assert (= x2 (+ x0 1))) (assert (= x2 (+ x0 2))) (assert (= x3 (+ x1 3)))",
            "(assert (= x1 (+ x2 1))) (assert (= x2 (+ x1 1))) (assert (= x3 (* x0 x0)))",
        )) {
            val source = SmtLibPolynomial.parse("$declarations $equations")

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `triangular substitution retains zero leading coefficient fibers`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (+ x0 1) 1)) (assert (= (* x0 x1) 0)) " +
                "(assert (= x2 (* x1 x1))) (assert (= x3 (+ x1 2))) (assert (not (= x1 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertTrue(algebraic.sign(witness.realValue(1)) != 0)
        assertEquals(0, algebraic.compare(witness.realValue(2), algebraic.power(witness.realValue(1), 2)))
    }

    @Test
    fun `acyclic graphs reconstruct reverse coordinate dependencies`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val equations = (1..8).joinToString(" ") { "(assert (= x$it (+ (* x0 x${it + 1}) 1)))" }
        val source = SmtLibPolynomial.parse(
            "$declarations $equations (assert (= (+ x9 1) 2)) " +
                "(assert (>= x0 1)) (assert (<= x0 1)) (assert (> x1 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        for (id in 1..8) {
            val product = algebraic.multiply(witness.realValue(0), witness.realValue(id + 1))
            assertEquals(0, algebraic.compare(witness.realValue(id),
                algebraic.add(product, algebraic.rational(BigFraction.ONE))))
        }
        assertEquals(BigFraction.ONE, witness.realValue(9).rational)
    }

    @Test
    fun `cyclic graph candidates remain enforced constraints`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= x1 (* x0 x2))) (assert (= x2 (+ (* x0 x1) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(witness.realValue(1),
            algebraic.multiply(witness.realValue(0), witness.realValue(2))))
        assertEquals(0, algebraic.compare(witness.realValue(2),
            algebraic.add(algebraic.multiply(witness.realValue(0), witness.realValue(1)),
                algebraic.rational(BigFraction.ONE))))
    }

    @Test
    fun `interruption withholds an unfinished graph substitution`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val equations = (1..32).joinToString(" ") { "(assert (= x$it (+ x${it - 1} 1)))" }
        val source = SmtLibPolynomial.parse("$declarations $equations (assert (> x32 0))")
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val graphs = triangularGraphSections(atoms, arithmetic)
        var stopped = false
        val substitution = PolynomialGraphSubstitution(graphs, PolynomialArithmetic(Cancellation { stopped }))
        val pending = substitution.prepare(atoms.last().polynomial)
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
