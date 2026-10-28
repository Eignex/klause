package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonlinearIntegerResiduesTest {
    @Test
    fun `globally impossible alternatives should leave a checked integer witness`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (and (= x 1) (= y 1))))")

        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(residueRefutation = true))

        val witness = assertIs<QfNraResult.Satisfiable>(execution.result).assignment
        assertTrue(checkNonlinearSource(source.formula, witness, PolynomialArithmetic()))
        assertEquals(1L, execution.stats.residues.excluded)
        assertEquals(0L, execution.stats.residues.refuted)
    }

    @Test
    fun `complete impossible Boolean coverage should refute the integer source`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (= (+ (* x x) (* y y)) 7)))")

        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(residueRefutation = true))

        assertIs<QfNraResult.Unsatisfiable>(execution.result)
        assertEquals(2L, execution.stats.residues.excluded)
        assertEquals(1L, execution.stats.residues.refuted)
        assertEquals(0L, execution.stats.checks)
    }

    @Test
    fun `residue exclusions should preserve Boolean pins and changed numeric windows`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (and b (= x 1) (= y 1))))")
        val params = NonlinearIntegerParams(residueRefutation = true)
        val yes = PolynomialCondition(bools = mapOf(0 to true), lower = mapOf(0 to BigFraction.ONE))
        val no = PolynomialCondition(bools = mapOf(0 to false))

        val rejected = solveNonlinearIntegers(source, params, no)
        val accepted = solveNonlinearIntegers(source, params, yes)

        assertIs<QfNraResult.Unsatisfiable>(rejected.result)
        val witness = assertIs<QfNraResult.Satisfiable>(accepted.result).assignment
        assertTrue(checkNonlinearSource(source.formula, witness, PolynomialArithmetic()))
        assertEquals(0L, rejected.stats.residues.refuted)
        assertEquals(1L, rejected.stats.residues.excluded)
        assertEquals(1L, accepted.stats.residues.excluded)
    }

    @Test
    fun `widening a window should keep the surviving integer branch reachable`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (and (= x 1) (= y 1))))")
        val params = NonlinearIntegerParams(residueRefutation = true)
        val zero = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
        val narrow = solveNonlinearIntegers(source, params, PolynomialCondition(lower = zero, upper = zero))

        val widened = solveNonlinearIntegers(source, params)

        assertIs<QfNraResult.Unsatisfiable>(narrow.result)
        assertIs<QfNraResult.Satisfiable>(widened.result)
        assertEquals(1L, narrow.stats.residues.excluded)
        assertEquals(1L, widened.stats.residues.excluded)
        assertEquals(0L, narrow.stats.residues.refuted)
    }

    @Test
    fun `negation should consume only impossible equalities and preserve supported predicates`() {
        for (assertion in listOf(
            "(assert (not (= (+ (* x x) (* y y)) 3)))",
            "(assert (not (= (+ (* x x) (* y y)) 2)))",
            "(assert (distinct (+ (* x x) (* y y)) 3))",
        )) {
            val source = source(assertion)

            val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(residueRefutation = true))

            val witness = assertIs<QfNraResult.Satisfiable>(execution.result, assertion).assignment
            assertTrue(checkNonlinearSource(source.formula, witness, PolynomialArithmetic()), assertion)
            assertEquals(0L, execution.stats.residues.refuted)
        }
    }

    @Test
    fun `real and auxiliary alternatives should retain their original predicates`() {
        for (assertion in listOf(
            "(assert (or (= (+ (* x x) (* r r)) 3) (= x y)))",
            "(assert (or (= (+ (* x x) (ite b (* y y) 0)) 3) (= x y)))",
            "(assert (or (= (+ (* x x) (* y y)) 21) (= x y)))",
        )) {
            val source = source(assertion)
            val residues = NonlinearIntegerResidues(source, PolynomialArithmetic(), PolynomialLimits())

            val reduced = residues.simplify()

            assertSame(source.formula, reduced, assertion)
            assertEquals(0L, residues.stats.excluded)
        }
    }

    @Test
    fun `an incomplete Boolean reduction should publish no atom exclusions`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (= (+ (* x x) (* y y)) 7)))")
        val residues = NonlinearIntegerResidues(source, PolynomialArithmetic(), PolynomialLimits(maxMatrixCells = 30))

        assertSame(source.formula, residues.simplify())
        assertEquals(0L, residues.stats.excluded)
        assertEquals(0L, residues.stats.refuted)
        assertEquals(1L, residues.stats.declined)
    }

    @Test
    fun `cancellation after an atom proof should withhold incomplete Boolean consumption`() {
        val source = source("(assert (or (= (+ (* x x) (* y y)) 3) (= (+ (* x x) (* y y)) 7)))")
        lateinit var residues: NonlinearIntegerResidues
        val cancellation = Cancellation { residues.stats.rings >= 4 }
        residues = NonlinearIntegerResidues(source, PolynomialArithmetic(cancellation), PolynomialLimits())

        assertFailsWith<PolynomialInterrupted> { residues.simplify() }

        assertEquals(0L, residues.stats.excluded)
        assertEquals(0L, residues.stats.refuted)
    }

    @Test
    fun `mandatory multivariate systems should refute with shared integer residues`() {
        val assertions = listOf(
            "(assert (= (+ (* x x) (* y y)) 3))",
            "(assert (= (+ (* x x) (* y y)) (+ (* 4 z) 3)))",
            "(assert (= (* x y) 2)) (assert (= (+ x y) 4))",
            "(assert (= (+ (* (/ 1 2) x x) (* (/ 1 2) y y)) (/ 3 2)))",
            "(assert (= (+ (* (- x) (- x)) (* y y)) (- 1)))",
            "(assert (= (+ (* x x) (* y y)) 100000000000000000000000000000000000003))",
            "(define-fun square ((n Int)) Int (* n n)) " +
                "(assert (let ((a x) (b y)) (= (+ (square a) (square b)) 3)))",
        )
        for (assertion in assertions) {
            val source = source(assertion)

            val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(residueRefutation = true))

            assertIs<QfNraResult.Unsatisfiable>(execution.result, assertion)
            assertEquals(1L, execution.stats.residues.refuted, assertion)
            assertEquals(0L, execution.stats.checks, assertion)
            assertTrue(execution.stats.residues.work > 0, assertion)
        }
    }

    @Test
    fun `surviving modular support should supply no integer verdict`() {
        for (assertion in listOf(
            "(assert (= (+ (* x x) (* y y)) 2))",
            "(assert (= (+ (* x x) (* y y)) 21))",
            "(assert (= (* (/ 1 2) x y) 1))",
            "(assert (= (+ (^ x 2147483647) y) 0))",
        )) {
            val residues = NonlinearIntegerResidues(source(assertion), PolynomialArithmetic(), PolynomialLimits())

            val refuted = residues.refutes()

            assertFalse(refuted, assertion)
        }
    }

    @Test
    fun `composite zero divisors should preserve a real integer witness`() {
        val residues = NonlinearIntegerResidues(source(
            "(assert (= (* x y) 4)) (assert (= x 2)) (assert (= y 2))",
        ), PolynomialArithmetic(), PolynomialLimits())

        assertFalse(residues.refutes())
        assertEquals(5L, residues.stats.rings)
    }

    @Test
    fun `guarded real auxiliary and non equality support should decline`() {
        for (assertion in listOf(
            "(assert (or (= (+ (* x x) (* y y)) 3) (= x y)))",
            "(assert (not (= (+ (* x x) (* y y)) 3)))",
            "(assert (< (+ (* x x) (* y y)) 3))",
            "(assert (= (+ (* x x) (* r r)) 3))",
            "(assert (= (+ (* x x) (ite b (* y y) 0)) 3))",
            "(assert (= (* x x) 2)) (assert (= (* y y) 3))",
        )) {
            val residues = NonlinearIntegerResidues(source(assertion), PolynomialArithmetic(), PolynomialLimits())

            assertFalse(residues.refutes(), assertion)
            assertEquals(0L, residues.stats.refuted, assertion)
        }
    }

    @Test
    fun `containing storage limits should decline before a refutation`() {
        for (limits in listOf(PolynomialLimits(maxMatrixCells = 0), PolynomialLimits(maxDenseCoefficients = 1))) {
            val residues = NonlinearIntegerResidues(source("(assert (= (+ (* x x) (* y y)) 3))"),
                PolynomialArithmetic(), limits)

            assertFalse(residues.refutes())
            assertEquals(1L, residues.stats.declined)
        }
    }

    @Test
    fun `denominator growth should decline without proof`() {
        for (assertion in listOf(
            "(assert (= (+ (* (^ (/ 1 2) 10000) x x) (* y y)) 3))",
        )) {
            val residues = NonlinearIntegerResidues(source(assertion), PolynomialArithmetic(), PolynomialLimits())

            assertFalse(residues.refutes(), assertion)
            assertEquals(0L, residues.stats.refuted)
            assertEquals(1L, residues.stats.declined)
        }
    }

    @Test
    fun `compositional images should refute large powers before enumerating tuples`() {
        val source = source("(assert (= (+ (^ x 2147483646) (^ y 2147483646) " +
            "(^ z 2147483646) (^ w 2147483646)) 7))")

        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(residueRefutation = true))

        assertIs<QfNraResult.Unsatisfiable>(execution.result)
        assertEquals(1L, execution.stats.residues.refuted)
        assertEquals(0L, execution.stats.residues.tuples)
        assertEquals(0L, execution.stats.checks)
        assertEquals(0L, execution.stats.residues.declined)
    }

    @Test
    fun `overlapping images should preserve realizable sums aliases and rational scales`() {
        for (assertion in listOf(
            "(assert (= (+ (^ x 2147483646) (^ y 2147483646) " +
                "(^ z 2147483646) (^ w 2147483646)) 3))",
            "(assert (= (+ (^ x 2147483646) (^ y 2147483646) " +
                "(^ z 2147483646) (^ w 2147483646)) 4))",
            "(assert (= (+ (^ x 2) (^ x 2) (^ y 2)) 3))",
            "(assert (= (+ (* (/ 1 2) (^ x 2)) (* (/ 1 3) (^ y 2))) (/ 5 6)))",
            "(assert (= (+ (^ x 2) (- (^ x 2)) (^ y 2)) 1))",
        )) {
            val residues = NonlinearIntegerResidues(source(assertion), PolynomialArithmetic(), PolynomialLimits())

            assertFalse(residues.refutes(), assertion)
            assertEquals(0L, residues.stats.refuted, assertion)
        }
    }

    @Test
    fun `scaled disjoint images should preserve exact denominator edge factors`() {
        val residues = NonlinearIntegerResidues(source(
            "(assert (= (+ (* (/ 1 2) (^ x 2)) (* (/ 1 2) (^ y 2))) (/ 3 2)))",
        ), PolynomialArithmetic(), PolynomialLimits())

        assertTrue(residues.refutes())
        assertEquals(0L, residues.stats.tuples)
    }

    @Test
    fun `overlapping individual equations should retain shared tuple refutations`() {
        val residues = NonlinearIntegerResidues(source(
            "(assert (= (* x y) 2)) (assert (= (+ x y) 4))",
        ), PolynomialArithmetic(), PolynomialLimits())

        assertTrue(residues.refutes())
        assertTrue(residues.stats.tuples > 0)
    }

    @Test
    fun `containing cancellation should interrupt charged residue work`() {
        var charged = 0L
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 40
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val residues = NonlinearIntegerResidues(source("(assert (= (+ (* x x) (* y y)) 3))"),
            PolynomialArithmetic(token), PolynomialLimits())

        assertFailsWith<PolynomialInterrupted> { residues.refutes() }
        assertEquals(40L, charged)
        assertEquals(0L, residues.stats.refuted)
    }

    @Test
    fun `private residue work should share the containing allowance and preserve a stopped verdict`() {
        val execution = solveNonlinearIntegers(source("(assert (= (+ (* x x) (* y y)) 3))"),
            NonlinearIntegerParams(arithmetic = QfNraParams(maximumWork = 40), residueRefutation = true))

        assertIs<QfNraResult.Unknown>(execution.result)
        assertEquals(0L, execution.stats.residues.refuted)
        assertEquals(40L, execution.stats.polynomial.workSpent)
    }

    @Test
    fun `paused entry should preserve the same residue result and charge on resume`() {
        for (assertion in listOf(
            "(assert (= (+ (* x x) (* y y)) 3))",
            "(assert (or (= (+ (* x x) (* y y)) 3) (= (+ (* x x) (* y y)) 7)))",
            "(assert (or (= (+ (* x x) (* y y)) 3) (and (= x 1) (= y 1))))",
        )) {
            val source = source(assertion)
            val params = NonlinearIntegerParams(residueRefutation = true)
            val whole = solveNonlinearIntegers(source, params)
            NonlinearIntegerCursor(source, params, PolynomialCondition()).use { cursor ->
                assertNull(cursor.runSlice(Cancellation { true }))
                assertEquals(0L, cursor.stats.polynomial.workSpent)

                val resumed = requireNotNull(cursor.runSlice(maximumCandidates = 1))

                assertEquals(whole.result::class, resumed.result::class)
                assertEquals(whole.stats, resumed.stats)
            }
        }
    }

    @Test
    fun `a changed window should retain supported source residues without caching a scoped refutation`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (declare-const y Int) (assert (= (+ (* x x) (* y y)) 2))",
            allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        val params = NonlinearIntegerParams(residueRefutation = true)
        val zero = mapOf(0 to BigFraction.ZERO, 1 to BigFraction.ZERO)
        val one = mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE)
        val narrow = solveNonlinearIntegers(source, params, PolynomialCondition(lower = zero, upper = zero))

        val changed = solveNonlinearIntegers(source, params, PolynomialCondition(lower = one, upper = one))

        assertIs<QfNraResult.Unsatisfiable>(narrow.result)
        assertIs<QfNraResult.Satisfiable>(changed.result)
        assertEquals(0L, narrow.stats.residues.refuted)
        assertEquals(0L, changed.stats.residues.refuted)
    }

    private fun source(assertions: String): NonlinearIntegerProblem {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (declare-const y Int) (declare-const z Int) (declare-const w Int) " +
                "(declare-const r Real) (declare-const b Bool) $assertions", allowIntegers = true,
        )
        return NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
    }
}
