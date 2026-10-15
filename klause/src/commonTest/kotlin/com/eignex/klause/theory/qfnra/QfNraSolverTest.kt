package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfNraSolverTest {
    private val algebraic = AlgebraicArithmetic()

    @Test
    fun `sparse dyadic weights preserve distant original ids within the coupled prefix`() {
        val declarations = (0 until 32).joinToString("\n") { "(declare-const x$it Real)" }
        val terms = (1..7).flatMap { x -> (1..8 - x).map { y -> x to y } }.take(19)
            .joinToString(" ") { (x, y) -> "(* (^ x3 $x) (^ x29 $y))" }
        val source = SmtLibPolynomial.parse("""
            $declarations
            (assert (> (+ $terms) x31))
        """.trimIndent())
        val fixed = mapOf(31 to BigFraction.ofLong(2))
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic(), fixed)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        val assignment = assertIs<QfNraAssignment>(work.probe {
            probe.probeWitness(source.realCount, fixed) { phase ->
                assertTrue(work.snapshot().workSpent <= 29000)
                work.charge(29000 - work.snapshot().workSpent)
                work.coupled(phase)
            }
        })

        assertTrue(work.snapshot().workSpent in 29001L until 32768L)
        assertEquals(1L, work.snapshot().dyadicBases)
        assertEquals(0L, work.snapshot().coupledWorkDeclines)
        assertEquals(1, algebraic.sign(assignment.realValue(3)))
        assertEquals(1, algebraic.sign(assignment.realValue(29)))
        assertEquals(BigFraction.ofLong(2), assignment.realValue(31).rational)
        for (id in 0 until source.realCount) {
            if (id != 3 && id != 29 && id != 31) assertEquals(BigFraction.ZERO, assignment.realValue(id).rational)
        }
        repeat(32) { assertEquals(emptyList(), probe.roots(Polynomial.ONE, 0, emptyMap())) }
        assertNull(probe.roots(Polynomial.ONE, 0, emptyMap()))
    }

    @Test
    fun `sparse signed weights retain strict rational bounds and unused source coordinates`() {
        for (negated in listOf(false, true)) {
            val declarations = (0 until 32).joinToString("\n") { "(declare-const x$it Real)" }
            val comparisons = if (negated) "(not (>= (* 3 x0) 1)) (not (<= x31 x0))"
                else "(< (* 3 x0) 1) (> x31 x0)"
            val source = SmtLibPolynomial.parse("""
                $declarations
                (assert (and (> x0 0) (> x31 0) (> (* x0 x31) 0) $comparisons))
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(probe.coupled(guides,
                List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                atomTermCounts = guides.map { it.polynomial.terms.size }))

            assertEquals(1, algebraic.sign(assignment.realValue(0)))
            assertEquals(1, algebraic.compare(assignment.realValue(31), assignment.realValue(0)))
            assertEquals(-1, algebraic.compare(algebraic.multiply(assignment.realValue(0),
                algebraic.rational(BigFraction.ofLong(3))), algebraic.rational(BigFraction.ONE)))
            for (id in 1 until 31) assertEquals(BigFraction.ZERO, assignment.realValue(id).rational)
        }
    }

    @Test
    fun `coupled source ids outside the seed decline before excluding fixed coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const outside Real)
            (assert (> (* x y outside) 1))
        """.trimIndent())
        for (fixed in listOf(emptyMap(), mapOf(2 to BigFraction.ONE))) {
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            assertNull(work.probe { work.coupled {
                probe.coupled(guides, List(2) { BigFraction.ZERO }, fixed,
                    atomTermCounts = guides.map { it.polynomial.terms.size })
            } })

            assertEquals(0L, work.snapshot().dyadicAttempts)
            assertTrue(work.snapshot().workSpent > 0)
        }
    }

    @Test
    fun `sparse and constant weights latch containing stops before publication`() {
        for ((comparison, stops) in listOf(
            ">" to listOf(5L, 6L, 8L, 14L), "<" to listOf(4L, 5L),
        )) for (stopAt in stops) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert ($comparison (* x y) 1))
            """.trimIndent())
            var stopped = false
            var triggered = false
            var afterExtraction = 0L
            var spent = 0L
            lateinit var work: QfNraWork
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = stopped.also { stopped = false }
                override fun workMeter(): WorkMeter = WorkMeter { units ->
                    spent += units
                    if (work.dyadicAttempts == 1L && !work.dyadicPreExtraction) {
                        afterExtraction += units
                        if (!triggered && afterExtraction >= stopAt) {
                            triggered = true
                            stopped = true
                        }
                    }
                }
            }
            work = QfNraWork(QfNraParams(cancellation))
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            assertFailsWith<PolynomialInterrupted> { work.probe { work.coupled {
                probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                    atomTermCounts = guides.map { it.polynomial.terms.size })
            } } }

            assertTrue(triggered)
            assertTrue(spent > 0)
            assertEquals(spent, work.snapshot().workSpent)
            assertEquals(0L, work.snapshot().dyadicBases)
            assertEquals(0L, work.snapshot().coupledWorkDeclines)
        }
    }

    @Test
    fun `completed admission reuses terms without refunding native debt`() {
        val terms = (1..7).flatMap { x -> (1..8 - x).map { y -> x to y } }.take(19)
            .joinToString(" ") { (x, y) -> "(* (^ x $x) (^ y $y))" }
        val unused = (0..5).joinToString("\n") { "(declare-const unused$it Real)" }
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            $unused
            (assert (> (+ $terms) 1))
        """.trimIndent())
        val spent = ArrayList<Long>()
        val points = ArrayList<List<BigFraction?>>()
        for (production in listOf(false, true)) {
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val indices = ArrayList<Int>()
            val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128, indices))
            val counts = guides.map { it.polynomial.terms.size }
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            val assignment = assertIs<QfNraAssignment>(work.probe {
                if (production) probe.probeWitness(source.realCount, emptyMap()) { phase ->
                    assertTrue(work.snapshot().workSpent <= 29000)
                    work.charge(29000 - work.snapshot().workSpent)
                    work.coupled(phase)
                } else {
                    work.charge(29000)
                    work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                        emptyMap(), indices, counts) }
                }
            })

            assertTrue(work.snapshot().workSpent in 29001L until 32768L)
            assertEquals(1L, work.snapshot().dyadicBases)
            assertEquals(0L, work.snapshot().coupledWorkDeclines)
            assertEquals(1, algebraic.sign(assignment.realValue(0)))
            assertEquals(1, algebraic.sign(assignment.realValue(1)))
            for (id in 2 until source.realCount) assertEquals(BigFraction.ZERO, assignment.realValue(id).rational)
            spent.add(work.snapshot().workSpent)
            points.add(assignment.reals.map { it.rational })
        }
        assertTrue(spent[1] < spent[0])
        assertEquals(points[0], points[1])
    }

    @Test
    fun `caller recreated guides cannot borrow a private admission payload`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
        """.trimIndent())
        val debits = ArrayList<Long>()
        for (production in listOf(false, true)) {
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val indices = ArrayList<Int>()
            val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128, indices))
                .map { PolynomialAtom(it.polynomial, it.relation) }
            val counts = guides.map { it.polynomial.terms.size }
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)
            var before = 0L

            val assignment = assertIs<QfNraAssignment>(work.probe {
                if (production) probe.probeWitness(source.realCount, emptyMap()) {
                    before = work.snapshot().workSpent
                    work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                        emptyMap(), indices, counts) }
                } else {
                    before = work.snapshot().workSpent
                    work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                        emptyMap(), indices, counts) }
                }
            })

            assertEquals(1L, work.snapshot().dyadicBases)
            assertEquals(1, algebraic.compare(algebraic.multiply(assignment.realValue(0),
                assignment.realValue(1)), algebraic.rational(BigFraction.ONE)))
            debits.add(work.snapshot().workSpent - before)
        }
        assertTrue(debits[1] >= debits[0])
    }

    @Test
    fun `completed admission payload is unavailable to a later direct coupled call`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
        """.trimIndent())
        val debits = ArrayList<Long>()
        for (production in listOf(false, true)) {
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val indices = ArrayList<Int>()
            val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128, indices))
            val counts = guides.map { it.polynomial.terms.size }
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)
            var before = 0L

            val assignment = assertIs<QfNraAssignment>(work.probe {
                if (production) assertIs<QfNraAssignment>(probe.probeWitness(source.realCount,
                    emptyMap(), work::coupled))
                before = work.snapshot().workSpent
                work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                    emptyMap(), indices, counts) }
            })

            assertEquals(if (production) 2L else 1L, work.snapshot().dyadicBases)
            assertEquals(1, algebraic.compare(algebraic.multiply(assignment.realValue(0),
                assignment.realValue(1)), algebraic.rational(BigFraction.ONE)))
            debits.add(work.snapshot().workSpent - before)
        }
        assertEquals(debits[0], debits[1])
    }

    @Test
    fun `private admission reuse observes a containing stop before dyadic extraction`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
        """.trimIndent())
        lateinit var work: QfNraWork
        work = QfNraWork(QfNraParams(Cancellation { work.dyadicPreExtraction }))
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        assertFailsWith<PolynomialInterrupted> {
            work.probe { probe.probeWitness(source.realCount, emptyMap(), work::coupled) }
        }

        assertEquals(1L, work.snapshot().dyadicAttempts)
        assertEquals(0L, work.snapshot().dyadicBases)
        assertEquals(0L, work.snapshot().coupledWorkDeclines)
    }

    @Test
    fun `admission snapshots retain complements and fresh fixed query values`() {
        for (negated in listOf(false, true)) {
            val comparison = if (negated) "(not (<= (* x y) fixed))" else "(> (* x y) fixed)"
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const fixed Real)
                (declare-const unused Real)
                (assert (and $comparison (not (<= x 0)) (not (<= y 0))))
            """.trimIndent())
            val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
            service.invocation().use { invocation ->
                for (value in listOf(BigFraction.ONE, BigFraction.ofLong(2))) {
                    val result = assertIs<QfNraResult.Satisfiable>(invocation.check(PolynomialCondition(
                        lower = mapOf(2 to value), upper = mapOf(2 to value),
                    )))
                    val point = result.assignment

                    assertEquals(value, point.realValue(2).rational)
                    assertEquals(BigFraction.ZERO, point.realValue(3).rational)
                    assertEquals(1, algebraic.compare(algebraic.multiply(point.realValue(0), point.realValue(1)),
                        algebraic.rational(value)))
                    assertEquals(1L, result.stats.dyadicBases)
                }
                assertEquals(2L, invocation.queries)
                assertEquals(2L, invocation.stats.dyadicBases)
            }
        }
    }

    @Test
    fun `dyadic observations add no cancellation polls or native charges`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (> (* x y) 1) (> x 0) (> y 0)))
        """.trimIndent())
        val readings = ArrayList<Pair<Long, Int>>()
        val points = ArrayList<List<BigFraction?>>()
        for (observe in listOf(false, true)) {
            var polls = 0
            var charged = 0L
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean { polls++; return false }
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }
            val work = QfNraWork(QfNraParams(cancellation))
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val counts = guides.map { it.polynomial.terms.size }
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic),
                if (observe) work else null)

            val assignment = assertIs<QfNraAssignment>(work.probe { work.coupled {
                probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                    atomTermCounts = counts)
            } })
            val beforeSnapshot = polls
            val stats = work.snapshot()

            assertEquals(beforeSnapshot, polls)
            assertEquals(charged, stats.workSpent)
            assertEquals(if (observe) 1L else 0L, stats.dyadicAttempts)
            assertEquals(if (observe) 1L else 0L, stats.dyadicBases)
            readings.add(charged to polls)
            points.add(assignment.reals.map { it.rational })
        }
        assertEquals(readings[0], readings[1])
        assertEquals(points[0], points[1])
    }

    @Test
    fun `private pre extraction decline retains spent debt and scalar remainder`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
        """.trimIndent())
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
        val counts = guides.map { it.polynomial.terms.size }
        lateinit var work: QfNraWork
        var charged = 0L
        var filled = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter {
                charged += it
                if (!filled && work.dyadicPreExtraction) {
                    filled = true
                    work.charge(32768 - charged)
                }
            }
        }
        work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        work.probe {
            assertNull(work.coupled { probe.coupled(guides,
                List(source.realCount) { BigFraction.ZERO }, emptyMap(), atomTermCounts = counts) })
            arithmetic.step()
            null
        }

        val stats = work.snapshot()
        assertEquals(32769L, charged)
        assertEquals(charged, stats.workSpent)
        assertEquals(1L, stats.dyadicAttempts)
        assertEquals(0L, stats.dyadicBases)
        assertEquals(1L, stats.coupledWorkDeclines)
        assertEquals(1L, stats.dyadicPreExtractionDeclines)
    }

    @Test
    fun `containing dyadic interruption is not a private work decline`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (> (* x y) 1))
        """.trimIndent())
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
        val counts = guides.map { it.polynomial.terms.size }
        lateinit var work: QfNraWork
        work = QfNraWork(QfNraParams(Cancellation { work.dyadicPreExtraction }))
        val arithmetic = PolynomialArithmetic(work)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        assertFailsWith<PolynomialInterrupted> { work.probe { work.coupled {
            probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                atomTermCounts = counts)
        } } }

        val stats = work.snapshot()
        assertEquals(1L, stats.dyadicAttempts)
        assertEquals(0L, stats.dyadicBases)
        assertEquals(0L, stats.coupledWorkDeclines)
        assertEquals(0L, stats.dyadicPreExtractionDeclines)
    }

    @Test
    fun `dyadic observations distinguish missing metadata and unresolved equality`() {
        for (metadata in listOf(false, true)) for (equality in listOf(false, true)) {
            val extra = if (equality) "(= (+ x y) 3)" else ""
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> (* x y) 1) (> x 0) (> y 0) $extra))
            """.trimIndent())
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val counts = if (metadata) guides.map { it.polynomial.terms.size } else null
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            assertIs<QfNraAssignment>(work.probe { work.coupled {
                probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                    atomTermCounts = counts)
            } })

            val expected = if (metadata && !equality) 1L else 0L
            val snapshot = work.snapshot()
            assertEquals(expected, snapshot.dyadicAttempts)
            assertEquals(expected, snapshot.dyadicBases)
            assertEquals(snapshot.dyadicAttempts, snapshot.copy(workSpent = 0).dyadicAttempts)
        }
    }

    @Test
    fun `cached comparison pricing leaves construction headroom without refunding admission debt`() {
        val terms = (1..7).flatMap { x -> (1..8 - x).map { y -> x to y } }.take(19)
            .joinToString(" ") { (x, y) -> "(* (^ x $x) (^ y $y))" }
        val unused = (0..5).joinToString("\n") { "(declare-const unused$it Real)" }
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            $unused
            (assert (> (+ $terms) 1))
        """.trimIndent())
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val guides = requireNotNull(formula.conjunctiveAtoms(PolynomialArithmetic(), 128))
        val counts = guides.map { it.polynomial.terms.size }
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

        val assignment = assertIs<QfNraAssignment>(work.probe {
            work.charge(29000)
            work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                emptyMap(), atomTermCounts = counts) }
        })

        assertTrue(charged > 29000)
        assertTrue(charged < 32768)
        assertEquals(charged, work.snapshot().workSpent)
        assertEquals(1L, work.snapshot().dyadicBases)
        assertEquals(0L, work.snapshot().coupledWorkDeclines)
        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
        for (id in 2 until source.realCount) assertEquals(BigFraction.ZERO, assignment.realValue(id).rational)
    }

    @Test
    fun `incomplete source term counts cannot establish a constructed dyadic base`() {
        for (counts in listOf(emptyList(), listOf(0))) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (> (* x y) 1))
            """.trimIndent())
            val work = QfNraWork(QfNraParams())
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic), work)

            val assignment = assertIs<QfNraAssignment>(work.probe { work.coupled {
                probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                    atomTermCounts = counts)
            } })

            assertEquals(1L, work.snapshot().dyadicAttempts)
            assertEquals(0L, work.snapshot().dyadicBases)
            assertEquals(1, algebraic.compare(algebraic.multiply(assignment.realValue(0),
                assignment.realValue(1)), algebraic.rational(BigFraction.ONE)))
        }
    }

    @Test
    fun `rejected coupled progress completes scalar repair within the same remaining query slots`() {
        for ((negatedGuides, redundantBound) in listOf(
            false to false, false to true, true to false, true to true,
        )) for (retain in listOf(false, true)) {
            val declarations = if (negatedGuides) """
                (declare-const w Real)
                (declare-const fixed Real)
                (declare-const unused Real)
            """.trimIndent() else ""
            val guards = if (negatedGuides) {
                "(> w 0) (= fixed 1) " + (1..8).joinToString(" ") { "(not (= (+ z $it) $it))" }
            } else ""
            val weakBound = if (redundantBound) "(>= x 0)" else ""
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                $declarations
                (assert (and (> x 0) $weakBound (> y 0) (> z 0) (> y z) (> (* y z) 0) (> x (+ z 1)) $guards))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val fixed = if (negatedGuides) mapOf(4 to BigFraction.ONE) else emptyMap()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val indices = ArrayList<Int>()
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128, indices))
            val algebraic = AlgebraicArithmetic(arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            val constant = Polynomial.constant(BigFraction.ONE)
            repeat(14) { probe.roots(constant, 0, emptyMap()) }

            assertNull(probe.coupled(guides, List(source.realCount) { fixed[it] ?: BigFraction.ZERO },
                fixed, if (retain) indices else null, guides.map { it.polynomial.terms.size }))
            repeat(14) { probe.roots(constant, 0, emptyMap()) }
            val termCounts = formula.atoms.associate { it.polynomial to it.polynomial.terms.size }
            val assignment = probeInequalityWitness(guides, source.realCount, arithmetic, algebraic, fixed,
                formula.atoms.sumOf { it.polynomial.terms.size }, termCounts, probe)

            if (!retain) assertNull(assignment) else {
                val accepted = assertIs<QfNraAssignment>(assignment)
                val x = accepted.realValue(0)
                val y = accepted.realValue(1)
                val z = accepted.realValue(2)
                assertEquals(1, algebraic.sign(x))
                assertEquals(1, algebraic.sign(y))
                assertEquals(1, algebraic.sign(z))
                assertEquals(1, algebraic.compare(y, z))
                assertEquals(1, algebraic.sign(algebraic.multiply(y, z)))
                assertEquals(1, algebraic.compare(x, algebraic.add(z, algebraic.rational(BigFraction.ONE))))
                if (negatedGuides) {
                    assertEquals(1, algebraic.sign(accepted.realValue(3)))
                    assertEquals(BigFraction.ONE, accepted.realValue(4).rational)
                    assertEquals(BigFraction.ZERO, accepted.realValue(5).rational)
                }
            }
        }
    }

    @Test
    fun `source monomial scales complete a multiplicative hierarchy without root queries`() {
        for (negated in listOf(false, true)) {
            val comparisons = if (negated) {
                "(not (<= x (* y z))) (not (<= y z)) (not (< z 1))"
            } else "(> x (* y z)) (> y z) (>= z 1)"
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (declare-const fixed Real)
                (declare-const unused Real)
                (assert (and (> x 0) (> y 0) (> z 0) $comparisons (= fixed 2)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val fixed = mapOf(3 to BigFraction.ofLong(2))
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val seed = List(source.realCount) { fixed[it] ?: BigFraction.ZERO }
            val oldCenters = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))
            assertNull(oldCenters.coupled(guides, seed, fixed))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(probe.coupled(guides, seed, fixed,
                atomTermCounts = guides.map { it.polynomial.terms.size }))

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            val z = assignment.realValue(2)
            assertEquals(1, algebraic.compare(x, algebraic.multiply(y, z)))
            assertEquals(1, algebraic.compare(y, z))
            assertTrue(algebraic.compare(z, algebraic.rational(BigFraction.ONE)) >= 0)
            assertEquals(BigFraction.ofLong(2), assignment.realValue(3).rational)
            assertEquals(BigFraction.ZERO, assignment.realValue(4).rational)
            repeat(32) {
                assertEquals(emptyList(), probe.roots(Polynomial.ONE, 0, emptyMap()))
            }
            assertNull(probe.roots(Polynomial.ONE, 0, emptyMap()))
        }
    }

    @Test
    fun `negative monomial scales retain strict rational upper bounds`() {
        for (negated in listOf(false, true)) {
            val comparisons = if (negated) "(not (>= (* 3 x) 1)) (not (<= y x))"
                else "(< (* 3 x) 1) (> y x)"
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> x 0) (> y 0) (> (* x y) 0) $comparisons))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(probe.coupled(guides,
                List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                atomTermCounts = guides.map { it.polynomial.terms.size }))

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            assertEquals(1, algebraic.sign(x))
            assertEquals(1, algebraic.compare(y, x))
            assertEquals(-1, algebraic.compare(algebraic.multiply(x,
                algebraic.rational(BigFraction.ofLong(3))), algebraic.rational(BigFraction.ONE)))
        }
    }

    @Test
    fun `incomplete monomial domination retains the existing positive center`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (> (* x y) 0) (> x (/ 1 2)) (< x 2)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(guides,
            List(source.realCount) { BigFraction.ZERO }, emptyMap(),
            atomTermCounts = guides.map { it.polynomial.terms.size }))

        assertEquals(0, algebraic.compare(assignment.realValue(0), algebraic.rational(BigFraction.ONE)))
        assertEquals(0, algebraic.compare(assignment.realValue(1), algebraic.rational(BigFraction.ONE)))
    }

    @Test
    fun `unresolved equalities retain affine completion instead of monomial domination`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 3) (> x y) (> y 0) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(guides,
            List(source.realCount) { BigFraction.ZERO }, emptyMap(),
            atomTermCounts = guides.map { it.polynomial.terms.size }))

        val x = assignment.realValue(0)
        val y = assignment.realValue(1)
        assertEquals(0, algebraic.compare(algebraic.add(x, y), algebraic.rational(BigFraction.ofLong(3))))
        assertEquals(1, algebraic.compare(x, y))
        assertEquals(1, algebraic.sign(y))
    }

    @Test
    fun `conflicting effective relations reject every monomial proposal`() {
        for (negated in listOf(false, true)) {
            val upper = if (negated) "(not (> (* x y) 1))" else "(<= (* x y) 1)"
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> (* x y) 1) $upper))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            assertNull(probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                atomTermCounts = guides.map { it.polynomial.terms.size }))
        }
    }

    @Test
    fun `monomial construction cannot publish after a containing stop or renew private work`() {
        for (parent in listOf(false, true)) {
            val constraints = (1..20).joinToString(" ") { "(> (* $it (- (* x y) 1)) 0)" }
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and $constraints))
                (check-sat)
            """.trimIndent())
            var stopped = false
            var spent = 0L
            var stopAt = Long.MAX_VALUE
            var triggered = false
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = stopped.also { stopped = false }
                override fun workMeter(): WorkMeter = WorkMeter {
                    spent += it
                    if (parent && !triggered && spent >= stopAt) {
                        triggered = true
                        stopped = true
                    }
                }
            }
            val work = QfNraWork(QfNraParams(cancellation))
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))
            val before = spent
            val attempt = {
                repeat(31768) { arithmetic.step() }
                stopAt = spent + 900
                assertNull(work.coupled {
                    probe.coupled(guides, List(source.realCount) { BigFraction.ZERO }, emptyMap(),
                        atomTermCounts = guides.map { it.polynomial.terms.size })
                })
                val termCounts = formula.atoms.associate { it.polynomial to it.polynomial.terms.size }
                probeInequalityWitness(guides, source.realCount, arithmetic, AlgebraicArithmetic(arithmetic),
                    emptyMap(), formula.atoms.sumOf { it.polynomial.terms.size }, termCounts, probe)
            }

            if (parent) {
                assertFailsWith<PolynomialInterrupted> { work.probe(attempt) }
                assertTrue(triggered)
            }
            else {
                val assignment = assertIs<QfNraAssignment>(work.probe(attempt))
                assertEquals(1, algebraic.compare(algebraic.multiply(assignment.realValue(0),
                    assignment.realValue(1)), algebraic.rational(BigFraction.ONE)))
                assertTrue(spent - before >= 32768)
                assertTrue(spent - before <= 65536)
            }
        }
    }

    @Test
    fun `shared boundaries follow each changed point and coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (> (* x y) 2) (>= (* x y) 2)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128))
            .map { it to it.polynomial.terms.size }
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
        val point = linkedMapOf(0 to algebraic.rational(BigFraction.ZERO),
            1 to algebraic.rational(BigFraction.ONE))

        val first = requireNotNull(probe.boundaries(guides, 0, point)).single()
        point[1] = algebraic.rational(BigFraction.ofLong(2))
        val changedPoint = requireNotNull(probe.boundaries(guides, 0, point)).single()
        point[0] = algebraic.rational(BigFraction.ofLong(4))
        val changedCoordinate = requireNotNull(probe.boundaries(guides, 1, point)).single()

        assertEquals(0, algebraic.compare(first, algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(0, algebraic.compare(changedPoint, algebraic.rational(BigFraction.ONE)))
        assertEquals(0, algebraic.compare(changedCoordinate,
            algebraic.rational(BigFraction.of(bigIntOf(1), bigIntOf(2)))))
    }

    @Test
    fun `declined or interrupted conjunction extraction cannot publish partial source indices`() {
        for (alternative in listOf(false, true)) {
            val tail = if (alternative) "(or (> y 0) (< y 0))" else "(> y 0)"
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (> x 0) $tail))
                (check-sat)
            """.trimIndent())
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val indices = arrayListOf(77)

            assertNull(formula.conjunctiveAtoms(PolynomialArithmetic(), if (alternative) 128 else 1, indices))

            assertEquals(listOf(77), indices)
            var spent = 0L
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= 5
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                formula.conjunctiveAtoms(PolynomialArithmetic(cancellation), 128, indices)
            }
            assertEquals(listOf(77), indices)
        }
    }

    @Test
    fun `cached rejected points cannot renew exhausted slots or bypass a containing stop`() {
        for (stop in listOf("none", "queries", "proposals", "parent")) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (assert (and (> x 0) (> y 0) (> z 0) (> y z) (> (* y z) 0) (> x (+ z 1))))
                (check-sat)
            """.trimIndent())
            var stopped = false
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = stopped
            }
            val work = QfNraWork(QfNraParams(cancellation))
            val arithmetic = PolynomialArithmetic(work)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val indices = ArrayList<Int>()
            val guides = requireNotNull(formula.conjunctiveAtoms(arithmetic, 128, indices))
            val algebraic = AlgebraicArithmetic(arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            val constant = Polynomial.constant(BigFraction.ONE)
            val zero = (0 until source.realCount).associateWith { algebraic.rational(BigFraction.ZERO) }
            val consume = {
                repeat(14) { probe.roots(constant, 0, emptyMap()) }
                assertNull(work.coupled { probe.coupled(guides, List(source.realCount) { BigFraction.ZERO },
                    emptyMap(), indices) })
                when (stop) {
                    "queries" -> repeat(16) { probe.roots(constant, 0, emptyMap()) }
                    "proposals" -> while (!probe.exhausted) assertNull(probe.candidate(zero))
                    "parent" -> stopped = true
                }
                val retained = probe.takeRejectedInequalityPoint(source.realCount, emptyMap())
                if (stop == "none") {
                    val point = requireNotNull(retained)
                    assertEquals(1, algebraic.compare(point.getValue(1), point.getValue(2)))
                    assertNull(probe.takeRejectedInequalityPoint(source.realCount, emptyMap()))
                    val fresh = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
                    assertNull(fresh.takeRejectedInequalityPoint(source.realCount, emptyMap()))
                } else assertNull(retained)
                null
            }

            if (stop == "parent") assertFailsWith<PolynomialInterrupted> { work.probe(consume) }
            else assertNull(work.probe(consume))
        }
    }

    @Test
    fun `nonzero coupled sectors preserve positive coordinates while breaking symmetry`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (declare-const unused Real)
            (assert (and (> x 0) (> y 0) (> z 0) (> x y) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(source.realCount) { BigFraction.ZERO }, emptyMap(),
        ))

        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
        assertEquals(1, algebraic.sign(assignment.realValue(2)))
        assertEquals(1, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
        assertEquals(BigFraction.ZERO, assignment.realValue(3).rational)
    }

    @Test
    fun `coupled affine completion retains nonzero fixed constants and coefficients`() {
        for ((fixedValue, expression, total) in listOf(
            Triple(1L, "(+ x z y)", 4L), Triple(2L, "(+ (* y x) z)", 5L),
        )) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (assert (and (= $expression $total) (> x z) (> z 0) (> (* x z) 0)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val fixed = mapOf(1 to BigFraction.ofLong(fixedValue))
            val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = assertIs<QfNraAssignment>(probe.coupled(
                requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                List(source.realCount) { fixed[it] ?: BigFraction.ZERO }, fixed,
            ))

            val x = assignment.realValue(0)
            val y = assignment.realValue(1)
            val z = assignment.realValue(2)
            val value = if (fixedValue == 1L) algebraic.add(algebraic.add(x, z), y)
                else algebraic.add(algebraic.multiply(y, x), z)
            assertEquals(0, algebraic.compare(value, algebraic.rational(BigFraction.ofLong(total))))
            assertEquals(BigFraction.ofLong(fixedValue), y.rational)
            assertEquals(1, algebraic.compare(x, z))
            assertEquals(1, algebraic.sign(z))
        }
    }

    @Test
    fun `degree ineligible guides remain mandatory in coupled point checks`() {
        for (relation in listOf(">=", "<")) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (declare-const z Real)
                (assert (and ($relation (^ x 9) 0) (> (* x y) 0) (> x y) (> x 0) (> y 0) (> z 0)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

            val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                List(source.realCount) { BigFraction.ZERO }, emptyMap())

            if (relation == "<") assertNull(assignment) else {
                val accepted = assertIs<QfNraAssignment>(assignment)
                assertEquals(1, algebraic.sign(accepted.realValue(0)))
                assertEquals(1, algebraic.sign(accepted.realValue(1)))
                assertEquals(1, algebraic.sign(accepted.realValue(2)))
                assertEquals(1, algebraic.compare(accepted.realValue(0), accepted.realValue(1)))
            }
        }
    }

    @Test
    fun `duplicate product guides leave root slots for a symmetry breaking boundary`() {
        val duplicates = (1..24).joinToString(" ") { "(> (+ (* x y) (* $it (- z x))) 0)" }
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and $duplicates (> x 0) (> y 0) (> z 0) (> x y)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(source.realCount) { BigFraction.ZERO }, emptyMap(),
        ))

        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
        assertEquals(1, algebraic.sign(assignment.realValue(2)))
        assertEquals(1, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
    }

    @Test
    fun `informative coupled guides precede coordinate bounds and distant root cells`() {
        val bounds = (5..8).joinToString(" ") { "(< x $it)" }
        val factors = (1..8).joinToString(" ") { "(+ x $it)" }
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and $bounds (> (* $factors) 0) (> (* x y) 0) (> x y) (> x 0) (> y 0) (> z 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(source.realCount) { BigFraction.ZERO }, emptyMap(),
        ))

        assertEquals(1, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
        assertEquals(1, algebraic.sign(assignment.realValue(2)))
        assertEquals(1, algebraic.compare(assignment.realValue(0), assignment.realValue(1)))
        assertTrue(algebraic.compare(assignment.realValue(0), algebraic.rational(BigFraction.ofLong(5))) < 0)
    }

    @Test
    fun `coupled centers cannot renew exhausted half allowances but scalar checks retain their remainder`() {
        for (exhaustProposals in listOf(false, true)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val algebraic = AlgebraicArithmetic(arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            val zero = mapOf(0 to algebraic.rational(BigFraction.ZERO), 1 to algebraic.rational(BigFraction.ZERO))
            if (exhaustProposals) repeat(32) { assertNull(probe.candidate(zero)) }
            else repeat(16) { probe.roots(formula.atoms.first().polynomial, 0, zero) }

            val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap())
            val fallback = probe.candidate(mapOf(0 to algebraic.rational(BigFraction.ONE),
                1 to algebraic.rational(BigFraction.ONE)))

            assertNull(assignment)
            assertIs<QfNraAssignment>(fallback)
        }
    }

    @Test
    fun `coupled sector proposals move both coordinates from a zero product`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = assertIs<QfNraAssignment>(probe.coupled(
            requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap(),
        ))

        val x = assignment.realValue(0)
        val y = assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertTrue(algebraic.compare(algebraic.multiply(x, y), algebraic.rational(BigFraction.ONE)) >= 0)
    }

    @Test
    fun `affine nullspace proposals preserve a relation while entering its positive interior`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        val x = result.assignment.realValue(0)
        val y = result.assignment.realValue(1)
        assertEquals(1, algebraic.sign(x))
        assertEquals(1, algebraic.sign(y))
        assertEquals(BigFraction.ONE, algebraic.add(x, y).rational)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `coupled proposals cannot release a fixed zero coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (>= (* x y) 1) (> y 0) (> z 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val fixed = mapOf(0 to BigFraction.ZERO)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic, fixed)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, AlgebraicArithmetic(arithmetic))

        val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            List(3) { BigFraction.ZERO }, fixed)

        assertNull(assignment)
    }

    @Test
    fun `guarded affine equalities do not restrict another satisfying alternative`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (or (= (+ x y) 0) (= (+ x y) 2)) (> (* x y) 0) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
        assertEquals(BigFraction.ofLong(2), algebraic.add(
            result.assignment.realValue(0), result.assignment.realValue(1),
        ).rational)
    }

    @Test
    fun `containing work limit withholds a coupled witness without replenishing its allowance`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ x y) 1) (> x 0) (> y 0) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(cancellation, maximumWork = limit))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(limit, spent)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `coupled repairs share query and proposal exhaustion with coordinate repairs`() {
        for (exhaustProposals in listOf(false, true)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
                (check-sat)
            """.trimIndent())
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val algebraic = AlgebraicArithmetic(arithmetic)
            val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
            val zero = mapOf(0 to algebraic.rational(BigFraction.ZERO), 1 to algebraic.rational(BigFraction.ZERO))
            if (exhaustProposals) repeat(64) { assertNull(probe.candidate(zero)) }
            else repeat(32) { probe.roots(formula.atoms.first().polynomial, 0, zero) }

            val assignment = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
                listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap())

            assertNull(assignment)
        }
    }

    @Test
    fun `coupled phase exhaustion leaves charged scalar fallback work available`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (>= (* x y) 1) (> x 0) (> y 0)))
            (check-sat)
        """.trimIndent())
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)
        val before = spent

        val assignment = work.probe {
            assertNull(work.coupled {
                repeat(65_536) { arithmetic.step() }
                null
            })
            assertEquals(before + 32_768, spent)
            probe.candidate(mapOf(0 to algebraic.rational(BigFraction.ONE), 1 to algebraic.rational(BigFraction.ONE)))
        }

        assertIs<QfNraAssignment>(assignment)
        assertTrue(spent > before + 32_768)
        assertTrue(spent < before + 65_536)
        assertEquals(spent, work.snapshot().workSpent)
    }

    @Test
    fun `an observed containing stop inside a coupled phase cannot publish a fallback witness`() {
        var spent = 0L
        var stopped = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (spent < 4 || stopped) return false
                stopped = true
                return true
            }
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val work = QfNraWork(QfNraParams(cancellation))
        val arithmetic = PolynomialArithmetic(work)
        var fallbackReached = false

        assertFailsWith<PolynomialInterrupted> {
            work.probe {
                work.coupled {
                    repeat(8) { arithmetic.step() }
                    null
                }
                fallbackReached = true
                null
            }
        }

        assertEquals(false, fallbackReached)
        assertEquals(4L, spent)
        assertEquals(spent, work.snapshot().workSpent)
    }

    @Test
    fun `oversized affine intermediates decline without disabling complete point checks`() {
        val coefficient = "1" + "0".repeat(1000)
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* $coefficient x) y) 1) (= (+ x (* $coefficient y)) 1) (> (* x y) 0)))
            (check-sat)
        """.trimIndent())
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val algebraic = AlgebraicArithmetic(arithmetic)
        val probe = WitnessProbe(formula, source.boolCount, arithmetic, algebraic)

        val coupled = probe.coupled(requireNotNull(formula.conjunctiveAtoms(arithmetic, 128)),
            listOf(BigFraction.ZERO, BigFraction.ZERO), emptyMap())
        val value = (BigFraction.of(parseBigInt(coefficient), bigIntOf(1)) + BigFraction.ONE).reciprocal()
        val fallback = probe.candidate(mapOf(0 to algebraic.rational(value), 1 to algebraic.rational(value)))

        assertNull(coupled)
        assertIs<QfNraAssignment>(fallback)
    }

    @Test
    fun `disequality guards admit a narrow rational witness without matrix storage`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 2) (< x 3) (not (= (* x x) 0))))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)),
        ))

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), x))
        assertTrue(x.rational != null)
    }

    @Test
    fun `negated inequality guides preserve their source direction`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> (* x x) 4) (< x 3) (> x 0) (not (> x (/ 11 4)))))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount,
        ))

        val x = result.assignment.realValue(0)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), x))
        assertTrue(algebraic.compare(x, algebraic.rational(BigFraction.of(bigIntOf(11), bigIntOf(4)))) <= 0)
    }

    @Test
    fun `inequality proposals retain fixed and unused original coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const unused Real)
            (assert (and (> (* x y) 2) (< (* x y) 3) (not (= (* x y) 0))))
            (check-sat)
        """.trimIndent())
        val condition = PolynomialCondition(lower = mapOf(1 to BigFraction.ONE),
            upper = mapOf(1 to BigFraction.ONE))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount, condition = condition,
        ))

        assertEquals(3, result.assignment.realCount)
        assertEquals(BigFraction.ONE, result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-2, 1), result.assignment.realValue(0)))
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-3, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `contradictory disequality and boundary return unsatisfiable`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (not (= x 2)) (<= (* (- x 2) (- x 2)) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `containing cancellation withholds an inequality proposal witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 2) (< x 3) (not (= (* x x) 0))))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `rational repairs capture every source coordinate before projection`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const unused Real)
            (assert (= x 2))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(1, result.assignment.boolCount)
        assertEquals(2, result.assignment.realCount)
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(0).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(1).rational)
    }

    @Test
    fun `later equality repairs retain an exact algebraic trajectory`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const a Real)
            (declare-const x Real)
            (assert (and (= (* x x) (* 2 a)) (= (* 3 a) 1) (> a 0) (> x 0)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(BigFraction.of(bigIntOf(1), bigIntOf(3)), result.assignment.realValue(0).rational)
        assertEquals(0, algebraic.signAt(RationalPolynomial(listOf(
            BigFraction.of(bigIntOf(-2), bigIntOf(3)), BigFraction.ZERO, BigFraction.ONE,
        )), result.assignment.realValue(1)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(1)))
    }

    @Test
    fun `equality proposals cannot replace strict or disequality checks`() {
        for (constraint in listOf("(> x 1)", "(distinct (* x x) 1)")) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (assert (and (= (* x x) 1) $constraint))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
        }
    }

    @Test
    fun `negated equality proposals retain satisfying sectors`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (not (= (* x x) 1)) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount, source.realCount))

        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), result.assignment.realValue(0)))
    }

    @Test
    fun `conditioned repairs preserve Boolean pins and unused coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const k Real)
            (declare-const unused Real)
            (assert (and (or b (= (* x x) k)) (> x 0)))
            (check-sat)
        """.trimIndent())
        val condition = PolynomialCondition(bools = mapOf(0 to false),
            lower = mapOf(1 to BigFraction.ofLong(2)), upper = mapOf(1 to BigFraction.ofLong(2)))

        val result = assertIs<QfNraResult.Satisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount, condition = condition,
        ))

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(false, result.assignment.boolValue(0))
        assertEquals(BigFraction.ofLong(2), result.assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, result.assignment.realValue(2).rational)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), result.assignment.realValue(0)))
        assertEquals(1, algebraic.sign(result.assignment.realValue(0)))
    }

    @Test
    fun `parent cancellation withholds a completed probe witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertEquals(0L, unknown.stats.projectedPolynomials)
        assertEquals(spent, unknown.stats.workSpent)
    }

    @Test
    fun `an observed parent stop cannot become private probe exhaustion`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 2
        var spent = 0L
        var observed = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (observed || spent < limit) return false
                observed = true
                return true
            }
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        assertTrue(observed)
        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(spent, result.stats.workSpent)
    }

    @Test
    fun `probe work remains cumulative between invocation queries`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val required = solveQfNra(source.formula, source.boolCount, source.realCount).stats.workSpent
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        service.invocation(QfNraParams(cancellation, maximumWork = required + 1)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val second = invocation.check()

            assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(second).reason)
            assertEquals(spent, invocation.stats.workSpent)
        }
    }

    @Test
    fun `skipped high degree repairs retain complete refutation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (^ x 9) 0) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assertIs<QfNraResult.Unsatisfiable>(result).stats.projectedPolynomials > 0)
    }

    @Test
    fun `checked rational points do not require matrix storage`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (* y y) x))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertEquals(0L, assertIs<QfNraResult.Satisfiable>(result).stats.projectedPolynomials)
    }

    @Test
    fun `finds an exact irrational satisfying section`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.sign(value))
    }

    @Test
    fun `lifts an exact witness above an irrational coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) x) (> y 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.compare(algebraic.power(assignment.realValue(1), 2), assignment.realValue(0)))
        assertEquals(1, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `refutes a genuinely coupled multivariate system`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `solves a coupled irrational intersection`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (+ (* x x) (* y y)) 1) (= (+ x y) 0) (> x 0) (< y 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-1, 0, 2), assignment.realValue(0)))
        assertEquals(0, algebraic.sign(algebraic.add(assignment.realValue(0), assignment.realValue(1))))
    }

    @Test
    fun `selected irrational coordinates cannot satisfy false disequality`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x x) 2) (> x 0) (= (* y y) 2) (> y 0) (distinct x y)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `zero specialization retains its exact constraint truth`() {
        for ((target, satisfiable) in listOf(0 to true, 1 to false)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (declare-const y Real)
                (assert (and (= x 0) (= (* x y) $target)))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `nullification on a positive dimensional base retains feasible sectors`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (declare-const z Real)
            (assert (and (= x 0) (= (* x y z) 0) (> y 1) (> z 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(1)))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), assignment.realValue(2)))
    }

    @Test
    fun `lifting handles a vanished leading coefficient`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= x 0) (= (+ (* x y y) y (- 1)) 0)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ONE, assignment.realValue(1).rational)
    }

    @Test
    fun `retains a lower dimensional singular witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (= (+ (* x x) (* y y)) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, algebraic.sign(assignment.realValue(0)))
        assertEquals(0, algebraic.sign(assignment.realValue(1)))
    }

    @Test
    fun `strict inequalities exclude a shared boundary`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (<= (* x x) 1) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `strict feasible sectors have exact interior samples`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (< (* x x) 2) (> x 1)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(-1, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
        assertEquals(1, algebraic.signAt(RationalPolynomial.of(-1, 1), value))
    }

    @Test
    fun `repeated roots remain satisfying sections`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (= (* (- x 1) (- x 1)) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertEquals(BigFraction.ONE, assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0).rational)
    }

    @Test
    fun `Boolean alternatives retain an irrational satisfying disjunct`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (or (= (+ (* x x) 1) 0) (= (* x x) 2)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), value))
    }

    @Test
    fun `source Boolean references preserve correlation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (or b (= (* x x) 2)) (not b) (not (= (* x x) 2))))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `all six polynomial relations retain equality boundary semantics`() {
        for ((relation, satisfiable) in listOf("=" to true, "distinct" to false, "<" to false,
            "<=" to true, ">" to false, ">=" to true)) {
            val source = SmtLibPolynomial.parse("""
                (declare-const x Real)
                (assert (and (= x 0) ($relation (* x x) 0)))
                (check-sat)
            """.trimIndent())

            val result = solveQfNra(source.formula, source.boolCount, source.realCount)

            if (satisfiable) assertIs<QfNraResult.Satisfiable>(result)
            else assertIs<QfNraResult.Unsatisfiable>(result)
        }
    }

    @Test
    fun `unused source declarations receive complete coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (declare-const y Real)
            (assert (>= (* x x) 0))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(1, assignment.boolCount)
        assertEquals(2, assignment.realCount)
        assertEquals(BigFraction.ZERO, assignment.realValue(1).rational)
    }

    @Test
    fun `numeric conditionals retain every auxiliary coordinate`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and (not b) (= (ite b (* x x) 2) 2)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(source.realCount > source.sourceRealCount)
        assertEquals(source.realCount, assignment.realCount)
        assertEquals(false, assignment.boolValue(0))
    }

    @Test
    fun `constant contradictions need no real dimensions`() {
        val source = SmtLibPolynomial.parse("(assert (= (* 2 2) 5)) (check-sat)")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `Boolean only formulas receive a complete satisfying assignment`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const c Bool)
            (assert (xor b c))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(0, assignment.realCount)
        assertEquals(2, assignment.boolCount)
        assertTrue(assignment.boolValue(0) != assignment.boolValue(1))
    }

    @Test
    fun `wide rational coefficients retain their exact witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (= x (/ 18446744073709551617 3)))
            (check-sat)
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val value = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        assertEquals(BigFraction.of(parseBigInt("18446744073709551617"), bigIntOf(3)), value.rational)
    }

    @Test
    fun `pre cancelled calls publish unknown without work`() {
        val source = SmtLibPolynomial.parse("(assert false) (check-sat)")

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(cancellation = Cancellation { true }))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertEquals(0L, unknown.stats.workSpent)
    }

    @Test
    fun `a zero work allowance cannot refute a contradiction`() {
        val source = SmtLibPolynomial.parse("(assert false) (check-sat)")

        val result = solveQfNra(source.formula, 0, 0, QfNraParams(maximumWork = 0))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `exhaustion before refutation publication returns unknown`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (< (* x x) 0)) (check-sat)")
        val completed = solveQfNra(source.formula, source.boolCount, source.realCount)
        val limit = assertIs<QfNraResult.Unsatisfiable>(completed).stats.workSpent - 1

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(maximumWork = limit))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }

    @Test
    fun `caller interruption after lifting publishes no witness`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (> x 1) (< x 2)))
            (check-sat)
        """.trimIndent())
        val completed = assertIs<QfNraResult.Satisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )
        val limit = completed.stats.workSpent - 1
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, QfNraParams(cancellation))

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.Interrupted, unknown.reason)
        assertTrue(unknown.stats.liftedCells > 0)
        assertEquals(spent, unknown.stats.workSpent)
    }

    @Test
    fun `matrix allowance stops never supply a refutation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const y Real)
            (assert (and (= (* x y) 1) (= (+ x y) 0)))
            (check-sat)
        """.trimIndent())
        val params = QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount, params)

        val unknown = assertIs<QfNraResult.Unknown>(result)
        assertEquals(QfNraReason.ResourceLimit, unknown.reason)
        assertEquals(PolynomialResource.MATRIX_CELLS, unknown.resource)
    }

    @Test
    fun `returned witnesses survive buffer mutation and another invocation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (and b (= (* x x) 2) (> x 0)))
            (check-sat)
        """.trimIndent())
        val result = solveQfNra(source.formula, source.boolCount, source.realCount)
        val assignment = assertIs<QfNraResult.Satisfiable>(result).assignment

        assignment.bools[0] = false
        runCatching { (assignment.reals as MutableList<*>).clear() }
        solveQfNra(source.formula, source.boolCount, source.realCount)

        assertTrue(assignment.boolValue(0))
        assertEquals(0, algebraic.signAt(RationalPolynomial.of(-2, 0, 1), assignment.realValue(0)))
    }
}
