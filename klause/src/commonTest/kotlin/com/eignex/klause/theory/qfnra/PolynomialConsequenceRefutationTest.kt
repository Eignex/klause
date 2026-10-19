package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialConsequenceRefutationTest {
    @Test
    fun `complete consequences combine nonzero equations and weighted squares beyond probe dimensions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (> b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.projectedPolynomials)
        assertTrue(checkNotNull(refuted.stats.booleanTheoryRegions) > 0)
    }

    @Test
    fun `complete square consequences retain the feasible zero factor branch`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (>= b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

        val refuted = preparePolynomialConsequenceRefutation(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `complete consequences keep guarded equation alternatives separate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (or (and (> x0 0) (= (* x0 x1) 0) (> x1 0)) (= x1 1)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ONE, witness.realValue(1).rational)
        assertEquals(source.realCount, witness.realCount)
    }

    @Test
    fun `a complete proof growth decline withholds its private consequence`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (= x (+ y 1))) (assert (= (* x x) (- y)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

        val refuted = preparePolynomialConsequenceRefutation(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `complete consequence preparation observes cancellation after suspension`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x (+ y 1)) (= y (+ x 1))))",
        )
        val normalization = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, normalization)
        val atoms = assertNotNull(formula.conjunctiveAtoms(normalization, Int.MAX_VALUE))
        var stopped = false
        val pending = preparePolynomialConsequenceRefutation(atoms, PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.asSequence().filterNotNull().single() }
    }

    @Test
    fun `containing work exhaustion withholds a complete region consequence`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (> x0 0) (> x1 0) (= (* x0 (- x2 (* x4 x4))) 0)
              (= (+ (* x0 x2) (* x1 x3) (- (* x0 x4 x4))) 0)
              (= (+ x0 (* x4 x4) (* (- 2) x4) 1 x3) 0)))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, source.boolCount,
            source.realCount, QfNraParams(maximumWork = 100_000)))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `complete weighted square reasoning refutes coupled positive polynomial equations`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val a = "(* 64 alpha b (^ r 7))"
        val t = "(* theta (+ (^ r 8) 1))"
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (declare-const delta Real) (declare-const alpha Real) (declare-const mu Real)
            (declare-const lambda Real) (declare-const theta Real) (declare-const gamma Real)
            (declare-const v Real) (declare-const r Real) (declare-const b Real)
            (assert (and (> delta 0) (> alpha 0) (> mu 0) (> theta 0) (> gamma 0) (> v 0) (> r 0) (> b 0)
              (= (* theta (- gamma (* b (+ (^ r 8) 1)))) 0)
              (= (+ (* gamma mu) (* lambda b) (- v)) 0)
              (= (+ (* 8 alpha (- gamma (* b (+ (^ r 8) 1)))) (* delta (- v r))) 0)
              (= (+ (* $a $a) (* $a delta $t) (* 2 $a delta) (* 2 $a $t) $a
                    (* (+ delta 1) (+ delta $t) (+ 1 $t))
                    (- (* 8 delta lambda theta b (^ r 7)))) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 1_000_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.projectedPolynomials)
    }

    @Test
    fun `nonzero equality factors and square remainders refute together`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (> b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertTrue(refuted)
        assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 16_384)))
    }

    @Test
    fun `non-strict factor premises retain zero as a feasible value`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (>= b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertFalse(refuted)
    }

    @Test
    fun `known nonzero equality factors can have either sign`() {
        for (premise in listOf("(> a 0)", "(< a 0)", "(distinct a 0)")) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const a Real) (declare-const x Real) (declare-const y Real)
                (assert (and $premise (= (* a (- y x)) 0) (> (- y x) 0)))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

            assertTrue(refuted, premise)
        }
    }

    @Test
    fun `unknown-zero factors cannot erase a feasible singular case`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const x Real) (assert (and (= (* a x) 0) (> x 1) (>= a 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertFalse(refuted)
    }

    @Test
    fun `a monomial equation does not replace a nondivisible coordinate`() {
        for (bounds in listOf("(> a 0) (> x 2)", "(> a 2) (> x 0)")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const a Real) (declare-const x Real) (assert (and (= (* a x) 1) $bounds))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

            assertFalse(refuted, bounds)
        }
    }

    @Test
    fun `square transfers retain a feasible zero boundary`() {
        for (relation in listOf("=", "<=", ">=", "distinct")) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real) (declare-const y Real)
                (assert ($relation (+ (* x x) (* y y) (* (- 2) x y)) 0))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

            assertFalse(refuted, relation)
        }
    }

    @Test
    fun `strict negative bounds reject a nonnegative square sum`() {
        for (comparison in listOf(
            "(< (+ (* x x) (* y y) (* (- 2) x y)) 0)",
            "(> (- (+ (* x x) (* y y) (* (- 2) x y))) 0)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert $comparison)",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

            assertTrue(refuted, comparison)
        }
    }

    @Test
    fun `multiple square pairs preserve a strictly positive remainder`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const d Real) (declare-const t Real) (declare-const y Real) (declare-const c Real)
            (assert (and (>= d 0) (>= t 0) (>= y 0) (> c 0)
              (= (+ (* d d t y) (* t y) (* d t t y y) d (* d d) (* t t y y)
                (* (- 6) d t y) c) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertTrue(refuted)
    }

    @Test
    fun `a negative common factor cannot authorize nonnegative squares`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const x Real) (declare-const y Real)
            (assert (and (< a 0) (< (+ (* a x x) (* a y y) (* (- 2) a x y)) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertFalse(refuted)
    }

    @Test
    fun `insufficient positive coefficients cannot cover a negative term`() {
        for (relation in listOf("=", "<")) {
            val source = SmtLibPolynomial.parse(
                """
                (declare-const x Real) (declare-const y Real)
                (assert (and (> x 0) (> y 0) ($relation (+ (* 2 x x) (* 2 y y) (* (- 5) x y)) 0)))
                """.trimIndent(),
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

            assertFalse(refuted, relation)
        }
    }

    @Test
    fun `guarded equalities supply no unconditional consequences`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const x Real)
            (assert (and (> a 0) (or (= (* a x) 0) (= x 1)) (> x 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertFalse(refuted)
        val point = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 2)).assignment
        assertEquals(BigFraction.ONE, point.realValue(1).rational)
    }

    @Test
    fun `admission declines withhold partial consequences`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x (+ y 1)) (= y (+ x 1))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        assertTrue(refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096))

        for ((atoms, terms, bits) in listOf(Triple(1, 2048, 4096), Triple(128, 1, 4096), Triple(128, 2048, 1))) {
            assertFalse(refutePolynomialConsequences(formula, arithmetic, atoms, terms, bits))
        }
    }

    @Test
    fun `rewrites decline growth beyond original coordinate degrees`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x (* y y)) (= y (* x x)) (> x 2)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialConsequences(formula, arithmetic, 128, 2048, 4096)

        assertFalse(refuted)
    }

    @Test
    fun `interruption exposes no partial polynomial consequence`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const x Real) (declare-const y Real)
            (assert (and (> a 0) (= (* a (- y x)) 0) (> (- y x) 0)))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        assertTrue(refutePolynomialConsequences(formula, PolynomialArithmetic(counter), 128, 2048, 4096))

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                refutePolynomialConsequences(formula, PolynomialArithmetic(stop), 128, 2048, 4096)
            }
        }
    }

    @Test
    fun `containing work exhaustion withholds a completed consequence`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (> b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 5,
            QfNraParams(maximumWork = 16_384)))

        val result = solveQfNra(source.formula, 0, 5,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
