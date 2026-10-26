package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialFailureCaptureTest {
    @Test
    fun `a failure capture cannot be shared across query scopes`() {
        val source = PolynomialRealFormula.Constant(false)
        val first = QfNraQuery(source, 0, 0, QfNraParams(), PolynomialCondition())
        val second = QfNraQuery(source, 0, 0, QfNraParams(), PolynomialCondition())
        val capture = PolynomialFailureCapture()
        first.failureCapture = capture

        assertFailsWith<IllegalStateException> { second.failureCapture = capture }
    }

    @Test
    fun `a resumed search stop retains its own operation after interleaved root work`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val graphs = (0 until 6).joinToString(" ") { "(= x$it (+ (* x${it + 1} x${it + 1}) 1))" }
        val source = SmtLibPolynomial.parse("$declarations (assert (and $graphs (= (* x6 x6) 2)))")
        val capture = PolynomialFailureCapture()
        var stopped = false
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(Cancellation { stopped }, maximumWork = 500_000), PolynomialCondition())
        query.failureCapture = capture

        var result: QfNraResult? = null
        while (result == null) {
            val progress = query.progress
            if (progress.searchSnapshot > 0 && progress.rootFinishedWork == null &&
                progress.rootWork >= progress.searchWork && capture.operation["stage"] == "root-reduction"
            ) stopped = true
            result = query.next()
        }

        assertTrue(stopped)
        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertTrue(capture.operation["stage"] != "root-reduction")
    }

    @Test
    fun `sign conflict capture keeps complete signed premises`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const gate Bool) " +
                "(assert (or gate (and (not (<= x 0)) (> y 0) (not (>= (* x y) 0)))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val capture = PolynomialFailureCapture()
        capture.scope(formula, PolynomialCondition(), arithmetic)
        capture.context(checkNotNull(PolynomialFormulaReduction.stages(formula, 2, arithmetic).next()), arithmetic)

        formula.booleanRegions(arithmetic,
            onPartialCheck = { owner, premises -> capture.signCheck(owner, premises, 7, 0, arithmetic) },
            onConflict = { capture.signConflict(it, 7, 0, arithmetic) })
            .asSequence().filterNotNull().toList()

        val conflict = assertNotNull(capture.firstSignConflict)
        assertEquals("7", conflict["snapshot"])
        assertEquals("3", conflict["signedPremises.count"])
        assertEquals(mapOf("GT" to 2, "LT" to 1), (0..2)
            .map { conflict.getValue("signedPremises.$it.relation") }.groupingBy { it }.eachCount())
        assertEquals("true", conflict["complete"])
    }

    @Test
    fun `the first refusal retains its operands after later operations`() {
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val capture = PolynomialFailureCapture()
        capture.operation("elimination", 1, 2, work.spentWork, arithmetic, listOf(Polynomial.ONE), 0)
        val before = work.spentWork

        capture.refused("MATRIX_CELLS", arithmetic)
        capture.operation("lifting", 1, 3, work.spentWork, arithmetic)
        capture.refused("DENSE_COEFFICIENTS", arithmetic)

        val refusal = assertNotNull(capture.firstRefusal)
        assertEquals("MATRIX_CELLS", refusal["resource"])
        assertEquals("elimination", refusal["operation.stage"])
        assertEquals("1", refusal["operation.operand.0.0.coefficient"])
        assertTrue(work.spentWork > before)
    }

    @Test
    fun `wide operands mark the capture incomplete`() {
        val polynomial = Polynomial.of((0..128).map {
            PolynomialTerm(Monomial.of(mapOf(0 to it)), BigFraction.ONE)
        })
        val capture = PolynomialFailureCapture()

        capture.operation("elimination", 2, 1, 10, PolynomialArithmetic(), listOf(polynomial), 0)

        assertEquals("false", capture.operation["complete"])
        assertEquals("129", capture.operation["operand.0.terms"])
        assertTrue(capture.operation.values.sumOf { it.length } < 16_384)
    }

    @Test
    fun `a containing work stop publishes no partial copy`() {
        val work = QfNraWork(QfNraParams(maximumWork = 2))
        val capture = PolynomialFailureCapture()

        assertFailsWith<PolynomialInterrupted> {
            capture.operation("projection", 1, 1, 0, PolynomialArithmetic(work), listOf(Polynomial.ONE))
        }

        assertTrue(capture.interruptedCapture)
        assertTrue(capture.operation.isEmpty())
        assertEquals(2L, work.spentWork)
    }

    @Test
    fun `completed observation preserves a strict irrational witness`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (and (= (* x x) 2) (> x 0)))")
        val plain = solveQfNra(source.formula, source.boolCount, source.realCount)
        val capture = PolynomialFailureCapture()
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount, QfNraParams(), PolynomialCondition())
        query.failureCapture = capture

        var observed: QfNraResult? = null
        while (observed == null) observed = query.next()

        val expected = assertIs<QfNraResult.Satisfiable>(plain).assignment.realValue(0)
        val actual = assertIs<QfNraResult.Satisfiable>(observed).assignment.realValue(0)
        assertEquals(0, AlgebraicArithmetic().compare(expected, actual))
        assertTrue(observed.stats.workSpent >= plain.stats.workSpent)
        assertEquals("true", capture.scope["complete"])
    }

    @Test
    fun `capture retains negative premises and exact condition bounds`() {
        val arithmetic = PolynomialArithmetic()
        val capture = PolynomialFailureCapture()
        val atom = PolynomialAtom(Polynomial.variable(0), PolynomialRelation.LT)
        val formula = PolynomialFormula.conjunction(listOf(atom), arithmetic)
        capture.scope(formula, PolynomialCondition(lower = mapOf(0 to BigFraction.MINUS_ONE)), arithmetic)

        capture.rejected(formula, PolynomialBooleanRegion(mapOf(0 to false), emptyMap()),
            mapOf(0 to AlgebraicArithmetic().rational(BigFraction.MINUS_ONE)), 0, 3, 17, arithmetic)

        val failure = assertNotNull(capture.firstCadFailure)
        assertEquals("false", failure["premise.0"])
        assertEquals("3", failure["candidate.snapshot"])
        assertEquals("-1", capture.scope["condition.lower.0"])
        assertEquals("LT", failure["active.0.relation"])
        assertEquals("17", failure["work"])
    }

    @Test
    fun `repetition observation stops at its comparison allowance`() {
        val arithmetic = PolynomialArithmetic()
        val capture = PolynomialFailureCapture()
        val formula = PolynomialFormula.conjunction(emptyList(), arithmetic)
        val point = mapOf(0 to AlgebraicArithmetic().rational(BigFraction.ZERO))

        repeat(300) { capture.rejected(formula, null, point, null, 1, it.toLong(), arithmetic) }

        assertEquals(300L, capture.failures)
        assertEquals(255L, capture.repeatedFirstFailure)
        assertEquals(false, capture.repetitionComplete)
    }
}
