package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialCellFeedbackTest {
    @Test
    fun `a regional reuse trace retains its rejected numeric candidate`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val scope = PolynomialCellScope.Region(source.formula, condition, snapshot,
            PolynomialBooleanRegion(mapOf(0 to true), emptyMap()), Any())
        val feedback = PolynomialCellFeedback(source.formula, condition)

        assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))

        val trace = assertNotNull(feedback.firstRegionUse)
        assertTrue(trace.current === scope)
        assertEquals(0, algebraic.compare(zero, trace.value))
        assertNull(feedback.firstCrossRegionUse)
    }

    @Test
    fun `source cells survive unrelated Boolean prefixes within one snapshot`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const a Bool) (declare-const b Bool) " +
                "(assert (and (= (* x x) 2) (or a b)))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val feedback = PolynomialCellFeedback(source.formula, condition)
        val first = PolynomialCellScope.Region(source.formula, condition, snapshot,
            PolynomialBooleanRegion(mapOf(0 to true), mapOf(0 to true)), Any())
        val second = PolynomialCellScope.Region(source.formula, condition, snapshot,
            PolynomialBooleanRegion(mapOf(0 to true), mapOf(1 to true)), Any())

        assertNotNull(feedback.rejection(0, predicate, first, zero, listOf(cell), arithmetic, algebraic))
        val reused = feedback.rejection(0, predicate, second, zero, emptyList(), arithmetic, algebraic)

        assertNotNull(reused)
        val trace = assertNotNull(feedback.firstCrossRegionUse)
        assertEquals(first.premises, trace.origin.premises)
        assertEquals(second.premises, trace.current.premises)
    }

    @Test
    fun `numeric filters retain exact open and closed endpoints`() {
        for (text in listOf("(= (* x x) 2)", "(> (* x x) 0)", "(distinct (* x x) 0)")) {
            val source = SmtLibPolynomial.parse("(declare-const x Real) (assert $text)")
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
            val condition = PolynomialCondition()
            val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
            val predicate = support.predicates.single()
            val zero = algebraic.rational(BigFraction.ZERO)
            val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
            val feedback = PolynomialCellFeedback(source.formula, condition)
            val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
            assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))

            val boundary = assertNotNull(cell.upper)
            val rejected = feedback.rejection(0, predicate, scope, boundary, emptyList(), arithmetic, algebraic)

            assertEquals(cell.upperClosed, rejected != null)
            assertNull(feedback.rejection(0, predicate, scope, algebraic.rational(BigFraction.ofLong(2)),
                emptyList(), arithmetic, algebraic))
        }
    }

    @Test
    fun `condition widening discards conditional checked cells`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const gate Bool) (assert (or gate (= (* x x) 2)))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val condition = PolynomialCondition(bools = mapOf(0 to false))
        val formula = condition.apply(source.formula, 1, 1, arithmetic::step)
        val snapshot = PolynomialFormula.normalize(formula, arithmetic)
        val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
        val predicate = assertNotNull(snapshot.forcedAtoms(arithmetic)).single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, scope, zero, arithmetic, algebraic))
        val feedback = PolynomialCellFeedback(source.formula, condition)
        assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))

        val widened = PolynomialCellScope.Query(source.formula, PolynomialCondition(), snapshot)

        assertNull(feedback.rejection(0, predicate, widened, zero, listOf(cell), arithmetic, algebraic))
    }

    @Test
    fun `replacing the snapshot discards retained source feedback`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val feedback = PolynomialCellFeedback(source.formula, condition)
        val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
        assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))
        val replacement = PolynomialFormula.normalize(source.formula, arithmetic)

        feedback.replaceSnapshot(replacement, arithmetic)

        val current = PolynomialCellScope.Query(source.formula, condition, replacement)
        assertNull(feedback.rejection(0, predicate, current, zero, emptyList(), arithmetic, algebraic))
    }

    @Test
    fun `regional cells cannot cross affine owners with identical premises`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val premises = PolynomialBooleanRegion(mapOf(0 to true), emptyMap())
        val scope = PolynomialCellScope.Region(source.formula, condition, snapshot, premises, Any())
        val predicate = snapshot.atoms.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, scope, zero, arithmetic, algebraic))
        val feedback = PolynomialCellFeedback(source.formula, condition)
        assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))

        val sibling = PolynomialCellScope.Region(source.formula, condition, snapshot, premises, Any())

        assertNull(feedback.rejection(0, predicate, sibling, zero, listOf(cell), arithmetic, algebraic))
    }

    @Test
    fun `retracted negative numeric or Boolean premises discard regional feedback`() {
        for (numeric in listOf(false, true)) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const gate Bool) (assert (or gate (not (> x 0))))",
            )
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
            val condition = PolynomialCondition()
            val owner = Any()
            val scope = PolynomialCellScope.Region(source.formula, condition, snapshot,
                PolynomialBooleanRegion(mapOf(0 to false), mapOf(0 to false)), owner)
            val predicate = snapshot.atoms.single().copy(relation = PolynomialRelation.LE)
            val one = algebraic.rational(BigFraction.ONE)
            val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, scope, one, arithmetic, algebraic))
            val feedback = PolynomialCellFeedback(source.formula, condition)
            assertNotNull(feedback.rejection(0, predicate, scope, one, listOf(cell), arithmetic, algebraic))
            val premises = if (numeric) PolynomialBooleanRegion(emptyMap(), mapOf(0 to false))
                else PolynomialBooleanRegion(mapOf(0 to false), emptyMap())

            val retracted = PolynomialCellScope.Region(source.formula, condition, snapshot, premises, owner)

            assertNull(feedback.rejection(0, predicate, retracted, one, emptyList(), arithmetic, algebraic))
        }
    }

    @Test
    fun `a source certificate cannot enter another source identity`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val other = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val condition = PolynomialCondition()
        val snapshot = PolynomialFormula.normalize(other.formula, arithmetic)
        val scope = PolynomialCellScope.Query(other.formula, condition, snapshot)
        val feedback = PolynomialCellFeedback(other.formula, condition)

        assertNull(feedback.rejection(0, predicate, scope, zero, listOf(cell), arithmetic, algebraic))
    }

    @Test
    fun `a different active predicate cannot reuse a source coordinate cell`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
        val feedback = PolynomialCellFeedback(source.formula, condition)

        val different = predicate.copy(relation = PolynomialRelation.NE)

        assertNull(feedback.rejection(0, different, scope, zero, listOf(cell), arithmetic, algebraic))
    }

    @Test
    fun `capacity eviction leaves the remaining checked cell usable`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val two = algebraic.rational(BigFraction.ofLong(2))
        val first = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val second = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, two, arithmetic, algebraic))
        val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
        val feedback = PolynomialCellFeedback(source.formula, condition, maximumCells = 1)
        assertNotNull(feedback.rejection(0, predicate, scope, zero, listOf(first), arithmetic, algebraic))

        feedback.admit(second, arithmetic)

        assertNull(feedback.rejection(0, predicate, scope, zero, emptyList(), arithmetic, algebraic))
        assertNotNull(feedback.rejection(0, predicate, scope, two, emptyList(), arithmetic, algebraic))
    }

    @Test
    fun `cancelled admission publishes no feedback`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val support = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = support.predicates.single()
        val zero = algebraic.rational(BigFraction.ZERO)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(predicate, support, zero, arithmetic, algebraic))
        val scope = PolynomialCellScope.Query(source.formula, condition, snapshot)
        val feedback = PolynomialCellFeedback(source.formula, condition)
        assertNull(feedback.rejection(0, predicate, scope, zero, emptyList(), arithmetic, algebraic))
        stopped = true

        assertFailsWith<PolynomialInterrupted> { feedback.admit(cell, arithmetic) }

        stopped = false
        assertNull(feedback.rejection(0, predicate, scope, zero, emptyList(), arithmetic, algebraic))
    }

    @Test
    fun `regional repair consumes a checked failure from a previous Boolean prefix`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const a Bool) (declare-const b Bool) " +
                "(assert (and (= (* x x) 2) (or a b)))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val feedback = PolynomialCellFeedback(source.formula, condition)
        val refinement = PolynomialCellRefinement(source.formula, condition, QfNraWork(QfNraParams()),
            PolynomialLimits(), feedback) {}
        refinement.prepare(snapshot)
        val zero = algebraic.rational(BigFraction.ZERO)
        for (boolean in listOf(0, 1)) {
            val region = PolynomialBooleanRegion(mapOf(0 to true), mapOf(boolean to true))
            val reduction = PolynomialAffineReduction.prepare(snapshot, region, 1, arithmetic)
                .asSequence().filterNotNull().single()
            val owner = assertNotNull(refinement.region(snapshot, region, reduction))

            val repaired = assertNotNull(owner.repair(0, zero))

            assertEquals(0, algebraic.signAt(snapshot.atoms.single().polynomial, mapOf(0 to repaired)))
        }
        assertIs<PolynomialCellScope.Source>(assertNotNull(feedback.firstCrossRegionUse).cell.scope)
    }

    @Test
    fun `feedback retains a singular Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const gate Bool) " +
                "(assert (or (and (not gate) (= (* x0 x0) 2) (= (* x0 x1) 1) (= (* x1 x1) 1)) " +
                "(and gate (= x0 0) (= x1 0))))",
        )
        for (enabled in listOf(false, true)) {
            val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000, cellRefinement = true, cellFeedback = enabled),
                PolynomialCondition())

            var result: QfNraResult? = null
            while (result == null) result = query.next()

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertTrue(witness.boolValue(0))
            assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
            assertEquals(BigFraction.ZERO, witness.realValue(1).rational)
        }
    }

    @Test
    fun `invocation feedback permits a widened algebraic completion`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val narrow = PolynomialCondition(lower = mapOf(0 to BigFraction.ZERO),
            upper = mapOf(0 to BigFraction.ONE))
        val wide = PolynomialCondition(lower = mapOf(0 to BigFraction.ZERO),
            upper = mapOf(0 to BigFraction.ofLong(2)))

        service.invocation(QfNraParams(cellRefinement = true, cellFeedback = true)).use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(narrow))

            val point = assertIs<QfNraResult.Satisfiable>(invocation.check(wide)).assignment.realValue(0)

            assertTrue(AlgebraicArithmetic().sign(point) > 0)
        }
    }

    @Test
    fun `closing a paused feedback query prevents continuation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= (* x x) 2) (= (* x y) 1)))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val invocation = service.invocation(QfNraParams(cellRefinement = true, cellFeedback = true))
        var checkedCells = 0
        invocation.checkedCellObserver = { checkedCells++ }
        val pending = invocation.prepareCheck()
        assertNull(pending.next())
        assertTrue(checkedCells > 0)

        invocation.close()

        assertFalse(pending.hasNext())
        assertFailsWith<IllegalStateException> { pending.next() }
    }
}
