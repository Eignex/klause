package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialPredicateSharingTest {
    @Test
    fun `definition free complementary siblings remove every numeric boundary`() {
        for ((gate, truth) in listOf("or" to PolynomialFormula.TRUE, "and" to PolynomialFormula.FALSE)) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) " +
                    "(assert ($gate (< x 0) (>= (* 2 x) 0) (> y 0)))",
            )
            val arithmetic = PolynomialArithmetic()

            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            assertTrue(formula.atoms.isEmpty())
            assertEquals(truth, formula.truth(intArrayOf(), intArrayOf(), arithmetic))
        }
    }

    @Test
    fun `definition free duplicate children expose one conjunctive predicate`() {
        for (gate in listOf("and", "or")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (assert ($gate (> x 0) (< (* (- 2) x) 0) (> x 0)))",
            )
            val arithmetic = PolynomialArithmetic()

            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            assertEquals(formula.atoms, formula.conjunctiveAtoms(arithmetic, 1))
            assertEquals(1, formula.atoms.size)
        }
    }

    @Test
    fun `Boolean constant siblings compact numeric predicates in retained order`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const z Real) " +
                "(assert (and true (> y 0) (or true (> z 0)) (> x 0)))",
        )
        val arithmetic = PolynomialArithmetic()

        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertEquals(listOf(1, 0), formula.atoms.map { it.polynomial.mainVariable })
        assertEquals(formula.atoms, formula.conjunctiveAtoms(arithmetic, 2))
    }

    @Test
    fun `numeric constants retain their predicates until Boolean propagation`() {
        val source = SmtLibPolynomial.parse("(assert (and (= 0 0) (< 1 0)))")
        val arithmetic = PolynomialArithmetic()

        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertEquals(listOf(PolynomialRelation.EQ, PolynomialRelation.LT), formula.atoms.map { it.relation })
        assertTrue(formula.booleanRegions(arithmetic).asSequence().filterNotNull().none())
    }

    @Test
    fun `atom replacement compacts exposed complements without changing its source`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (or (< x 0) (>= y 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val original = formula.atoms.toList()

        val rewritten = formula.withAtoms(listOf(original[0],
            PolynomialAtom(original[0].polynomial, PolynomialRelation.GE)), arithmetic)

        assertTrue(rewritten.atoms.isEmpty())
        assertEquals(PolynomialFormula.TRUE, rewritten.truth(intArrayOf(), intArrayOf(), arithmetic))
        assertEquals(original, formula.atoms)
    }

    @Test
    fun `compacted tautologies retain sparse source Boolean identities and conditioned pins`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const unused Bool) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (or b (not b)) c))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        assertEquals(listOf(2), formula.booleanVariables)
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(1 to pin, 2 to true)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(pin, witness.boolValue(1))
                assertTrue(witness.boolValue(2))
                assertEquals(3, witness.boolCount)
            }
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(bools = mapOf(2 to false))))
        }
    }

    @Test
    fun `cancelled atom compaction preserves the immutable source`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (or (< x 0) (>= y 0)))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val original = formula.atoms.toList()
        var work = 0
        val arithmetic = PolynomialArithmetic(Cancellation { ++work > 20 })

        assertFailsWith<PolynomialInterrupted> { formula.withAtoms(original, arithmetic) }

        assertEquals(original, formula.atoms)
        assertEquals(2, formula.atoms.size)
    }

    @Test
    fun `graph substitution removes exposed complements and reconstructs the source fibre`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (= x (+ y 1)) (or (< x 1) (>= y 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(1, reduction.eliminatedCount)
        assertTrue(reduction.formula.atoms.all { it.polynomial.mainVariable == null })
        val result = solveQfNra(source.formula, source.boolCount, source.realCount)
        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(witness.realValue(1).rational!! + BigFraction.ONE, witness.realValue(0).rational)
    }

    @Test
    fun `Boolean definition substitution retains numeric predicate order`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (or b (> y 0)) (= b (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduced = assertNotNull(formula.reduceBooleanDefinitions(arithmetic))

        assertEquals(formula.atoms, reduced.atoms)
    }
}
