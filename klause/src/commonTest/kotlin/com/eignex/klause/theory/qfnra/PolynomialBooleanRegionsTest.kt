package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialBooleanRegionsTest {
    @Test
    fun `disjoint pairs retain their ordered regions through repeated backtracking`() {
        for (length in listOf(1, 2, 3)) {
            val declarations = (0 until 2 * length).joinToString(" ") { "(declare-const b$it Bool)" }
            val pairs = (0 until length).joinToString(" ") { "(or b${2 * it} b${2 * it + 1})" }
            val source = SmtLibPolynomial.parse("$declarations (assert (and $pairs))")
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

            val expected = (0 until (1 shl length)).map { mask ->
                (0 until length).associate { pair ->
                    val first = mask and (1 shl (length - pair - 1)) != 0
                    (2 * pair + if (first) 0 else 1) to true
                }
            }
            assertEquals(expected, regions.map { it.bools })
            assertTrue(regions.all { it.atoms.isEmpty() })
        }
    }

    @Test
    fun `numeric pairs retain ordered regions through signed premise retraction`() {
        for (length in listOf(1, 2)) {
            val declarations = (0 until 2 * length).joinToString(" ") { "(declare-const x$it Real)" }
            val pairs = (0 until length).joinToString(" ") { "(or (> x${2 * it} 0) (> x${2 * it + 1} 0))" }
            val source = SmtLibPolynomial.parse("$declarations (assert (and $pairs))")
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

            val expected = (0 until (1 shl length)).map { mask ->
                (0 until length).associate { pair ->
                    val first = mask and (1 shl (length - pair - 1)) != 0
                    (2 * pair + if (first) 0 else 1) to true
                }
            }
            assertEquals(expected, regions.map { it.atoms })
            assertTrue(regions.all { it.bools.isEmpty() })
        }
    }

    @Test
    fun `charged first region work grows linearly across disjoint pair families`() {
        val work = listOf(8, 16, 32, 64).map { length ->
            val declarations = (0 until 2 * length).joinToString(" ") { "(declare-const b$it Bool)" }
            val pairs = (0 until length).joinToString(" ") { "(or b${2 * it} b${2 * it + 1})" }
            val source = SmtLibPolynomial.parse("$declarations (assert (and $pairs))")
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            var spent = 0L
            val meter = WorkMeter { spent += it }
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = meter
            }

            val first = formula.booleanRegions(PolynomialArithmetic(cancellation))
                .asSequence().filterNotNull().first()

            assertEquals((0 until length).associate { (2 * it + 1) to true }, first.bools)
            spent
        }

        for ((smaller, larger) in work.zipWithNext()) {
            assertTrue(larger <= 2 * smaller + 32, "first region work: $work")
        }
        assertTrue(work.last() < 10_000, "first region work: $work")
    }

    @Test
    fun `shared compound supports retain their first child tie order after retraction`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (declare-const d Bool) " +
                "(assert (or (and (or a b) c) (and (or a b) d)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(listOf(mapOf(1 to true, 3 to true), mapOf(1 to true, 2 to true),
            mapOf(0 to true, 3 to true), mapOf(0 to true, 2 to true)), regions.map { it.bools })
    }

    @Test
    fun `negated conjunction support retains false premises through retraction`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Bool) (declare-const b Bool) (assert (not (and a b)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(listOf(mapOf(0 to false), mapOf(1 to false)), regions.map { it.bools })
    }

    @Test
    fun `simultaneously supported children retain their first source premise`() {
        for ((assertion, truth) in listOf(
            "(or (< (- 1) 0) (> 2 0))" to true,
            "(not (and (= 1 0) (< 2 0)))" to false,
        )) {
            val source = SmtLibPolynomial.parse("(assert $assertion)")
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

            assertEquals(listOf(PolynomialBooleanRegion(mapOf(0 to truth), emptyMap())), regions)
        }
    }

    @Test
    fun `Boolean leaves retain priority over earlier numeric leaves`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (or (> x 0) b))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(listOf(PolynomialBooleanRegion(mapOf(0 to true), emptyMap()),
            PolynomialBooleanRegion(emptyMap(), mapOf(0 to true))), regions)
    }

    @Test
    fun `cancelling pending witness work leaves fresh region traversal intact`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Bool) (declare-const b Bool) (declare-const c Bool) (declare-const d Bool) " +
                "(assert (and (or a b) (or c d)))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val pending = formula.booleanRegions(PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
        val regions = formula.booleanRegions(PolynomialArithmetic()).asSequence().filterNotNull().toList()
        assertEquals(listOf(mapOf(1 to true, 3 to true), mapOf(1 to true, 2 to true),
            mapOf(0 to true, 3 to true), mapOf(0 to true, 2 to true)), regions.map { it.bools })
    }

    @Test
    fun `numeric sign retraction retains guarded zero and positive regions`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const gate Bool) " +
                "(assert (or (and gate (> x 0)) (and (not gate) (= x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        val algebraic = AlgebraicArithmetic()
        for ((gate, value) in listOf(false to BigFraction.ZERO, true to BigFraction.ONE)) {
            val point = mapOf(0 to algebraic.rational(value))
            assertTrue(regions.any { region ->
                region.bools[0] == gate && region.atoms.all { (index, required) ->
                    val atom = formula.atoms[index]
                    atom.holds(algebraic.signAt(atom.polynomial, point)) == required
                }
            })
        }
    }

    @Test
    fun `partial numeric conflicts exclude a guarded branch before its unrelated obligations`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(declare-const gate Bool) (declare-const a Bool) (declare-const b Bool) " +
                "(assert (and (or gate (and (not (<= x 0)) (> y 0) (not (>= (* x y) 0)))) (or a b)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val progress = PolynomialQueryProgress()
        val conflicts = ArrayList<List<PolynomialAtom>>()

        val regions = formula.booleanRegions(arithmetic, progress) { conflicts.add(it) }
            .asSequence().filterNotNull().toList()

        assertTrue(regions.isNotEmpty())
        assertTrue(regions.all { it.bools.getValue(0) && it.atoms.isEmpty() })
        assertEquals(1L, progress.partialConflicts)
        assertEquals(mapOf(PolynomialRelation.GT to 2, PolynomialRelation.LT to 1),
            conflicts.single().groupingBy { it.relation }.eachCount())
    }

    @Test
    fun `numeric exclusions are reused across unrelated Boolean prefixes`() {
        for (length in listOf(1, 2, 4)) {
            val declarations = (0 until length).joinToString(" ") {
                "(declare-const a$it Bool) (declare-const b$it Bool)"
            }
            val prefix = (0 until length).joinToString(" ") { "(or a$it b$it)" }
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) $declarations (declare-const gate Bool) " +
                    "(assert (and $prefix (or gate (and (> x 0) (> y 0) (< (* x y) 0)))))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val progress = PolynomialQueryProgress()

            val regions = formula.booleanRegions(arithmetic, progress).asSequence().filterNotNull().toList()

            assertTrue(regions.isNotEmpty())
            assertTrue(regions.all { it.bools.getValue(2 * length) })
            assertEquals(1L, progress.partialChecks)
            assertEquals(1L, progress.partialConflicts)
            assertTrue(progress.partialExclusionReuses > 0)
        }
    }

    @Test
    fun `coupled section exclusions refute every unrelated Boolean prefix before a region`() {
        for (length in listOf(1, 2, 4)) {
            val declarations = (0 until length).joinToString(" ") {
                "(declare-const a$it Bool) (declare-const b$it Bool)"
            }
            val prefix = (0 until length).joinToString(" ") { "(or a$it b$it)" }
            val source = SmtLibPolynomial.parse(
                "(declare-const x1 Real) (declare-const x2 Real) $declarations " +
                    "(assert (and $prefix (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1)))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val progress = PolynomialQueryProgress()
            val coupled = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

            val regions = formula.booleanRegions(arithmetic, progress, coupled = coupled)
                .asSequence().filterNotNull().toList()

            assertTrue(regions.isEmpty())
            assertEquals(1L, progress.coupledConflicts)
            assertEquals(3L, progress.coupledConflictPremises)
            assertTrue(progress.coupledExclusionReuses > 0)
        }
    }

    @Test
    fun `a coupled section conflict retracts to its satisfiable Boolean alternative`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const gate Bool) (declare-const x1 Real) (declare-const x2 Real) " +
                "(assert (or gate (and (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val progress = PolynomialQueryProgress()
        val conflicts = ArrayList<List<PolynomialAtom>>()
        val coupled = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

        val regions = formula.booleanRegions(arithmetic, progress, coupled = coupled) { conflicts.add(it) }
            .asSequence().filterNotNull().toList()

        assertTrue(regions.isNotEmpty())
        assertTrue(regions.all { it.bools[0] == true && it.atoms.isEmpty() })
        assertEquals(1L, progress.coupledConflicts)
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `replacement snapshots discard exclusions over reused atom identities`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const gate Bool) " +
                "(assert (or gate (and (> x 0) (> y 0) (< (* x y) 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val progress = PolynomialQueryProgress()
        val original = formula.booleanRegions(arithmetic, progress).asSequence().filterNotNull().toList()
        val replacement = formula.withAtoms(formula.atoms.map { atom ->
            if (atom.relation == PolynomialRelation.LT) atom.copy(relation = PolynomialRelation.GT) else atom
        }, arithmetic)

        val regions = replacement.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(1L, progress.partialConflicts)
        assertTrue(original.all { it.atoms.isEmpty() && it.bools.getValue(0) })
        assertTrue(regions.any { it.atoms.isNotEmpty() })
        assertTrue(regions.any { it.bools[0] == true })
        assertTrue(formula.atoms.any { it.relation == PolynomialRelation.LT })
    }

    @Test
    fun `partial conflicts can exhaust both numeric guard alternatives`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const gate Bool) " +
                "(assert (or (and (not gate) (> x 0) (> y 0) (< (* x y) 0)) " +
                "(and gate (< x 0) (< y 0) (< (* x y) 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val progress = PolynomialQueryProgress()

        val regions = formula.booleanRegions(arithmetic, progress).asSequence().filterNotNull().toList()

        assertTrue(regions.isEmpty())
        assertTrue(progress.partialConflicts > 0)
    }

    @Test
    fun `interrupted partial sign checks publish no numeric exclusion`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const gate Bool) " +
                "(assert (or gate (and (> x 0) (> y 0) (< (* x y) 0))))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val progress = PolynomialQueryProgress()
        val pending = formula.booleanRegions(arithmetic, progress)
        while (progress.partialChecks == 0L) assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
        assertEquals(0L, progress.partialConflicts)
        assertEquals(0L, progress.partialExclusionUses)
    }

    @Test
    fun `partial exclusions retain a zero boundary through retracted guard pins`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const gate Bool) " +
                "(assert (or (and (not gate) (> x0 0) (> x1 0) (< (* x0 x1) 0)) " +
                "(and gate (= x0 0) (= x1 (- 1)))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            val unconditioned = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment
            assertTrue(unconditioned.boolValue(0))
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                if (pin) {
                    val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                    assertTrue(witness.boolValue(0))
                    assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
                    assertEquals(BigFraction.MINUS_ONE, witness.realValue(1).rational)
                    assertEquals(33, witness.realCount)
                } else assertIs<QfNraResult.Unsatisfiable>(result)
            }
        }
    }

    @Test
    fun `observing partial conflict premises preserves native work`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const gate Bool) " +
                "(assert (or gate (and (> x0 0) (> x1 0) (< (* x0 x1) 0))))",
        )
        val plain = solveQfNra(source.formula, source.boolCount, source.realCount)
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount,
            QfNraParams(), PolynomialCondition())
        val conflicts = ArrayList<List<PolynomialAtom>>()
        query.partialConflictObserver = { conflicts.add(it) }

        var observed: QfNraResult? = null
        while (observed == null) observed = query.next()

        assertIs<QfNraResult.Satisfiable>(observed)
        assertEquals(plain.stats, observed.stats)
        assertTrue(conflicts.isNotEmpty())
    }

    @Test
    fun `graph substitutions expose strict nonzero factors within the matrix allowance`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        for ((predicate, sign) in listOf(
            "(distinct x2 0)" to 0, "(> x2 0)" to 1, "(< x2 0)" to -1,
            "(not (= x2 0))" to 0, "(not (<= x2 0))" to 1, "(not (>= x2 0))" to -1,
        )) {
            val source = SmtLibPolynomial.parse(
                """
                $declarations
                (assert (and (= x2 (* x0 x1)) $predicate (= (* x0 x3 x3) 1)
                             (distinct (+ (* x3 x3 x3) (* x0 x0 x0)) 0)
                             (distinct (- (* x3 x3 x3) (* x0 x0 x0)) 0)))
                """.trimIndent(),
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 25)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
            val x = witness.realValue(0)
            val y = witness.realValue(1)
            val z = witness.realValue(2)
            val u = witness.realValue(3)
            val xx = algebraic.multiply(x, x)
            val uu = algebraic.multiply(u, u)
            val xxx = algebraic.multiply(xx, x)
            val uuu = algebraic.multiply(uu, u)
            assertEquals(0, algebraic.compare(z, algebraic.multiply(x, y)))
            assertEquals(0, algebraic.compare(algebraic.multiply(x, uu), algebraic.rational(BigFraction.ONE)))
            if (sign == 0) assertTrue(algebraic.sign(z) != 0) else assertEquals(sign, algebraic.sign(z))
            assertTrue(algebraic.sign(algebraic.add(uuu, xxx)) != 0)
            assertTrue(algebraic.sign(algebraic.subtract(uuu, xxx)) != 0)
            assertEquals(33, witness.realCount)
        }
    }

    @Test
    fun `a nonzero rewritten sum retains its zero coordinate solutions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (+ x0 x1)) (distinct x2 0) (= (* x0 x3 x3) 0)
                         (= (* x3 x3) 1) (= (* x1 x1) 1) (> x1 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.compare(witness.realValue(2), witness.realValue(1)))
        assertTrue(algebraic.sign(witness.realValue(2)) > 0)
    }

    @Test
    fun `a weak rewritten predicate retains its zero factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (* x0 x1)) (>= x2 0) (= (* x0 x3 x3) 0)
                         (= (* x3 x3) 1) (= (* x1 x1) 1)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(2)))
    }

    @Test
    fun `graph derived nonzero factors stay within their Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x2 (* x0 x1)) (distinct x2 0)
                             (distinct x3 0) (= (* x0 x3 x3) 0))
                        (and b (= x0 0) (= x1 3) (= x2 0) (= x3 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
        assertEquals(BigFraction.ZERO, witness.realValue(2).rational)
    }

    @Test
    fun `retracted pins remove graph derived nonzero factors`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (and (= x2 (* x0 x1)) (distinct x2 0) (= (* x0 x3 x3) 1)))
                         (=> (not b) (and (= x0 0) (= x1 3) (= x2 0) (= x3 1)))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
                assertEquals(pin, witness.boolValue(0))
                if (pin) {
                    assertTrue(algebraic.sign(witness.realValue(2)) != 0)
                    assertEquals(0, algebraic.compare(witness.realValue(2),
                        algebraic.multiply(witness.realValue(0), witness.realValue(1))))
                } else {
                    assertEquals(0, algebraic.sign(witness.realValue(0)))
                    assertEquals(0, algebraic.sign(witness.realValue(2)))
                }
            }
        }
    }

    @Test
    fun `a rewritten nonzero contradiction refutes without exploring cells`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= x2 (+ (* x0 x1) 1)) (distinct (- x2 (* x0 x1) 1) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, result.stats.liftedCells)
    }

    @Test
    fun `zero resultants do not accept a noncommon required root`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* x0 x0) 1) (= (* (- x0 1) (- x0 2)) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `required root projection fits the source matrix allowance`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (distinct (+ (* x1 x1 x1) (* x0 x0 x0)) 0)
                         (distinct (- (* x1 x1 x1) (* x0 x0 x0)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 25)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        val x = witness.realValue(0)
        val y = witness.realValue(1)
        val xx = algebraic.multiply(x, x)
        val yy = algebraic.multiply(y, y)
        val xxx = algebraic.multiply(xx, x)
        val yyy = algebraic.multiply(yy, y)
        assertEquals(0, algebraic.compare(algebraic.add(xx, yy), algebraic.rational(BigFraction.ONE)))
        assertTrue(algebraic.sign(algebraic.add(yyy, xxx)) != 0)
        assertTrue(algebraic.sign(algebraic.subtract(yyy, xxx)) != 0)
        assertEquals(33, witness.realCount)
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
    }

    @Test
    fun `nonzero root premises stay within their source Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (> x0 0) (= (* x0 x1) 1) (< x1 0))
                        (and b (= x0 0) (= x1 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
        assertEquals(BigFraction.ofLong(3), witness.realValue(1).rational)
    }

    @Test
    fun `retracted source pins remove nonzero root premises`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (> x0 0)) (=> b (= (* x0 x1) 1))
                         (=> (not b) (= x0 0)) (=> (not b) (= x1 3))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                val x = assertNotNull(witness.realValue(0).rational)
                val y = assertNotNull(witness.realValue(1).rational)
                assertEquals(pin, witness.boolValue(0))
                if (pin) {
                    assertTrue(x > BigFraction.ZERO)
                    assertEquals(BigFraction.ONE, x * y)
                } else {
                    assertEquals(BigFraction.ZERO, x)
                    assertEquals(BigFraction.ofLong(3), y)
                }
            }
        }
    }

    @Test
    fun `unproved leading coefficients retain their zero fibers`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= (* (- x0 1) x1 x1) 0) (> x0 2) (< (* x1 x1) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(assertNotNull(witness.realValue(0).rational) > BigFraction.ofLong(2))
        assertEquals(BigFraction.ZERO, witness.realValue(1).rational)
    }

    @Test
    fun `shared zero factors satisfy weak inequalities on required roots`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (>= (* (- (+ (* x0 x0) (* x1 x1)) 1) (+ x1 x0 1)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        val xx = algebraic.multiply(witness.realValue(0), witness.realValue(0))
        val yy = algebraic.multiply(witness.realValue(1), witness.realValue(1))
        assertEquals(0, algebraic.compare(algebraic.add(xx, yy), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `shared zero factors cannot satisfy strict inequalities on required roots`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (assert (and (= (+ (* x0 x0) (* x1 x1)) 1)
                         (> (* (- (+ (* x0 x0) (* x1 x1)) 1) (+ x1 x0 1)) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `required repeated roots retain their exact algebraic coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* (- (* x0 x0) 2) (- (* x0 x0) 2)) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(algebraic.multiply(witness.realValue(0), witness.realValue(0)),
            algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(33, witness.realCount)
    }

    @Test
    fun `a required polynomial without real roots refutes its complete region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (+ (* x0 x0) 1) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `degree ordered complete projection retains original inequality coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (> x0 (* x1 x1)) (> x1 0) (> x0 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val x = assertNotNull(witness.realValue(0).rational)
        val y = assertNotNull(witness.realValue(1).rational)
        assertEquals(33, witness.realCount)
        assertTrue(y > BigFraction.ZERO)
        assertTrue(x > y * y)
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
    }

    @Test
    fun `containing work exhaustion withholds a completed equality conflict`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (+ x1 x2)) (= x0 (+ x1 x2 1))))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 33))

        val result = solveQfNra(source.formula, 0, 33,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `conditional equality sections reconstruct all original coordinates after a refuted region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 x1) (= x0 (+ x1 1)))
                        (and b (= x0 (* x1 x1)) (= (* x1 x1) 2))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(33, witness.realCount)
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ofLong(2))))
        for (variable in 2..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
        assertEquals(2L, result.stats.booleanTheoryRegions)
        assertEquals(2L, result.stats.equationalSections)
    }

    @Test
    fun `negated disequalities select exact zero sections within their region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (not (distinct x0 (* x1 x1))) (= (* x1 x1) 2)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(1L, result.stats.equationalSections)
    }

    @Test
    fun `required zero products retain their singular coordinate sections`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 0) (= (* x0 (+ (* x1 x1) (* x2 x2) (- 1))) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        for (variable in 0..32) assertEquals(BigFraction.ZERO, witness.realValue(variable).rational)
        assertEquals(1L, result.stats.equationalSections)
    }

    @Test
    fun `Boolean pin retraction rebuilds conditional equality sections`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (=> b (= x0 (* x1 x1)))
                         (=> (not b) (= x0 (+ (* x1 x1) 1))) (= (* x1 x1) 2)))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            for (pin in listOf(false, true, false)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(pin, witness.boolValue(0))
                val algebraic = AlgebraicArithmetic(PolynomialArithmetic())
                assertEquals(0, algebraic.compare(witness.realValue(0),
                    algebraic.rational(BigFraction.ofLong(if (pin) 2L else 3L))))
            }
        }
    }

    @Test
    fun `constant numeric truth excludes impossible Boolean alternatives before regions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or (not (= x0 0)) (and b (= x1 3))))",
        )
        val fixed = mapOf(0 to BigFraction.ZERO)

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
            PolynomialCondition(lower = fixed, upper = fixed))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ofLong(3), witness.realValue(1).rational)
        assertEquals(0L, result.stats.booleanTheoryConstantRefutations)
    }

    @Test
    fun `false constant requirements refute without issuing a numeric region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and (> x0 0) (or b (not b))))",
        )
        val fixed = mapOf(0 to BigFraction.ZERO)

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            condition = PolynomialCondition(lower = fixed, upper = fixed))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertNull(result.stats.booleanTheoryRegions)
    }

    @Test
    fun `constant atom truth preserves strict disequal and negated comparison polarity`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val fixed = mapOf(0 to BigFraction.ZERO)
        for ((operator, rhs, truth) in listOf(
            Triple("=", "0", true), Triple("distinct", "0", false),
            Triple("<", "1", true), Triple("<=", "(- 1)", false),
            Triple(">", "(- 1)", true), Triple(">=", "1", false),
        )) {
            val source = SmtLibPolynomial.parse(
                "$declarations (declare-const b Bool) (assert (or (not ($operator x0 $rhs)) (and b (= x1 3))))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
                PolynomialCondition(lower = fixed, upper = fixed))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(truth, witness.boolValue(0), operator)
            assertEquals(BigFraction.ofLong(if (truth) 3L else 0L), witness.realValue(1).rational, operator)
        }
    }

    @Test
    fun `constant facts retract with numeric source pins`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or (not (= x0 0)) (and b (= x1 3))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for ((pin, coordinate, bool) in listOf(Triple(0L, 3L, true), Triple(1L, 0L, false), Triple(0L, 3L, true))) {
                val fixed = mapOf(0 to BigFraction.ofLong(pin))
                val result = invocation.check(PolynomialCondition(lower = fixed, upper = fixed))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                assertEquals(BigFraction.ofLong(coordinate), witness.realValue(1).rational)
                assertEquals(bool, witness.boolValue(0))
            }
        }
    }

    @Test
    fun `a refuted first region does not hide a later numeric root`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
    }

    @Test
    fun `all numeric regions must be refuted before source exhaustion`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (> x0 0) (< x0 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `an indeterminate numeric region withholds source exhaustion`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= (+ (* x0 x0) (* x1 x1)) 1))
                        (and b (> x0 0) (< x0 0))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val stopped = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.ResourceLimit, stopped.reason)
        assertEquals(PolynomialResource.MATRIX_CELLS, stopped.resource)
    }

    @Test
    fun `a refused first region permits a later checked numeric witness`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= (+ (* x0 x0) (* x1 x1)) 1)) (and b (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
        assertEquals(1L, result.stats.booleanTheoryResourceRefusals)
        assertEquals(PolynomialResource.MATRIX_CELLS, result.stats.lastBooleanTheoryResource)
    }

    @Test
    fun `numeric disjunctions preserve alternatives without source Boolean variables`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (= x0 0) (= x0 1)) (= x0 3)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(BigFraction.ofLong(3), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `shared predicates retain their correlation across Boolean regions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (or b (= x0 3)) (or (not b) (= x0 3)) (not (= x0 3))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `region checkpoints resume without repeating native work`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val complete = service.invocation().use { it.check() }

        service.invocation().use { invocation ->
            val pending = invocation.prepareCheck()
            var result: QfNraResult? = null
            while (result == null) result = pending.next()

            assertIs<QfNraResult.Satisfiable>(result)
            assertEquals(complete.stats, result.stats)
        }
    }

    @Test
    fun `numeric regions share the containing work limit`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (or (and (not b) (= x0 0) (= x0 1)) (and b (= x0 3))))
            """.trimIndent(),
        )
        val complete = solveQfNra(source.formula, source.boolCount, source.realCount)

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = complete.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

    @Test
    fun `empty Boolean gates preserve their source truth`() {
        for ((formula, feasible) in listOf(
            PolynomialRealFormula.And(emptyList()) to true,
            PolynomialRealFormula.Or(emptyList()) to false,
        )) {
            val result = solveQfNra(formula, 0, 33)

            if (feasible) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `fixed guards remove inactive boundaries while retaining every source coordinate`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" } +
            (0..64).joinToString(" ") { "(declare-const b$it Bool)" }
        for (truth in listOf(false, true)) {
            val guard = if (truth) "b0" else "(not b0)"
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (and $guard (or $guard (= (+ (* x0 x0) (* x1 x1)) 1))))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

            val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(33, witness.realCount)
            assertEquals(65, witness.boolCount)
            assertEquals(truth, witness.boolValue(0))
            assertTrue(result.stats.liftedCells > 0)
        }
    }

    @Test
    fun `negated compounds can entail Boolean guards without choosing a branch`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const a Bool) (declare-const b Bool)
            (assert (and (not (or (not a) (not b))) (or a (= (+ (* x0 x0) (* x1 x1)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(witness.boolValue(0))
        assertTrue(witness.boolValue(1))
    }

    @Test
    fun `a refused numeric region does not suppress a feasible Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertTrue(assertIs<QfNraResult.Satisfiable>(result).assignment.boolValue(0))
    }

    @Test
    fun `a required compound retains its unresolved numeric constraint`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (or (not b) (= (+ (* x0 x0) (* x1 x1)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `negative numeric requirements retain their projection boundaries`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (not (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `shared numeric predicates remain boundaries for their active occurrences`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1)) (= (+ (* x0 x0) (* x1 x1)) 1)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(result).resource)
    }

    @Test
    fun `complete Boolean contradiction refutes before numeric projection`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (not b) (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `reduced projection still solves every active numeric source constraint`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (= x0 3) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(BigFraction.ofLong(3), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `Boolean facts derived from numeric requirements retain those requirements`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and (= x0 3) (= b (= x0 3)) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ofLong(3), witness.realValue(0).rational)
        assertTrue(witness.boolValue(0))
    }

    @Test
    fun `active numeric contradictions remain refutable after inactive boundaries are removed`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations (declare-const b Bool)
            (assert (and b (> x0 0) (< x0 0) (or b (= (+ (* x1 x1) (* x2 x2)) 1))))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `retracted Boolean pins restore the guarded projection family`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (= (+ (* x0 x0) (* x1 x1)) 1)))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to true))))
            val unmasked = invocation.check(PolynomialCondition(bools = mapOf(0 to false)))
            assertEquals(PolynomialResource.MATRIX_CELLS, assertIs<QfNraResult.Unknown>(unmasked).resource)
            assertIs<QfNraResult.Satisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to true))))
        }
    }

    @Test
    fun `inactive boundaries do not discard fixed source coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val fixed = mapOf(1 to BigFraction.ofLong(7))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
            PolynomialCondition(lower = fixed, upper = fixed))

        assertEquals(BigFraction.ofLong(7), assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(1).rational)
    }

    @Test
    fun `interruption exposes no incomplete region coverage`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const b Bool) (declare-const x Real) (assert (and b (or b (= (* x x) 2))))",
        )
        val normalized = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val stopped = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { normalized.booleanRegions(stopped).hasNext() }
    }

    @Test
    fun `the containing work limit withholds a reduced projection witness`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val complete = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        val stopped = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = complete.stats.workSpent - 1,
                polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

    @Test
    fun `invocation observations retain completed regions across compatible witness reuse`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and b (or b (= (+ (* x0 x0) (* x1 x1)) 1))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            assertIs<QfNraResult.Satisfiable>(invocation.check())

            assertEquals(1L, invocation.stats.booleanTheoryRegions)
            assertEquals(2L, invocation.queries)
        }
    }

    @Test
    fun `numeric conjunctions retain a single complete region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse("$declarations (assert (= x0 3))")
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(1, regions.size)
        assertEquals(mapOf(0 to true), regions.single().atoms)
    }
}
