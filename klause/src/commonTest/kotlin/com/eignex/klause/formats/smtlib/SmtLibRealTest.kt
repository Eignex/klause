package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryCompletion
import com.eignex.klause.solver.search.ClauseSearchComponent
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtLibRealTest {
    @Test
    fun `a false closed equality comparison should force its opposite without arithmetic`() {
        val source = SmtLib.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (= b (= x 0.0))) (assert (not b))",
        )
        val comparisons = source.model.factors.filterIsInstance<ExactLinear>()
            .filter { it.activator != LinearRow.ALWAYS }
        assertEquals(2, comparisons.size)
        val session = SearchSession(listOf(ClauseSearchComponent(source.model.factors.filterIsInstance<Clause>())))
        assertIs<ComponentResult.Consistent>(session.initialize())

        for (first in comparisons.indices) {
            val other = comparisons[1 - first].activator
            assertNull(session.boolValue(other))

            assertIs<ComponentResult.Consistent>(
                session.push(SearchDecision.Bool(Lit.make(comparisons[first].activator, false))),
            )

            assertEquals(true, session.boolValue(other))
            session.popTo(0)
            assertNull(session.boolValue(other))
        }
    }

    @Test
    fun `equality should permit both closed comparisons at its boundary`() {
        val source = SmtLib.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (= b (= x 0.0))) (assert b) (assert (= x 0.0))",
        )
        val comparisons = source.model.factors.filterIsInstance<ExactLinear>()
            .filter { it.activator != LinearRow.ALWAYS }
        val session = SearchSession(listOf(ClauseSearchComponent(source.model.factors.filterIsInstance<Clause>())))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(2, comparisons.size)
        comparisons.forEach { assertEquals(true, session.boolValue(it.activator)) }
        assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve())
    }

    @Test
    fun `negated real equality should retain values on either side of its boundary`() {
        for (value in listOf("(- 1.0)", "1.0")) {
            val source = SmtLib.parse(
                "(declare-const x Real) (declare-const b Bool) " +
                    "(assert (= b (= x 0.0))) (assert (not b)) (assert (= x $value))",
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }

    @Test
    fun `real equality helpers should retain definition and local binding scopes`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (define-fun zero () Bool (= x 0.0))
                (assert (= x 1.0))
                (assert (= y 0.0))
                (assert (let ((x y)) (= a zero)))
                (assert (let ((x y)) (= b (= x 0.0))))
                (assert (not a))
                (assert b)
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `conditional real equality should retain the selected branch truth`() {
        for (condition in listOf(false, true)) {
            val source = SmtLib.parse(
                """
                    (declare-const x Real)
                    (declare-const y Real)
                    (declare-const c Bool)
                    (declare-const b Bool)
                    (assert (= x 0.0))
                    (assert (= y 1.0))
                    (assert (= c $condition))
                    (assert (= b (ite c (= x 0.0) (= y 0.0))))
                    (assert (= b $condition))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }

    @Test
    fun `mixed and private real equality terms should retain their Boolean relaxation`() {
        for (term in listOf("(+ x (to_real n))", "(ite c x y)")) {
            val source = SmtLib.parse(
                "(declare-const x Real) (declare-const y Real) (declare-const n Int) " +
                    "(declare-const c Bool) (declare-const b Bool) " +
                    "(assert (= b (= $term 0.0))) (assert (not b))",
            )
            val comparisons = source.model.factors.filterIsInstance<ExactLinear>()
                .filter { it.activator != LinearRow.ALWAYS }
            assertEquals(2, comparisons.size)
            val session = SearchSession(listOf(ClauseSearchComponent(source.model.factors.filterIsInstance<Clause>())))
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<ComponentResult.Consistent>(
                session.push(SearchDecision.Bool(Lit.make(comparisons[0].activator, false))),
            )

            assertNull(session.boolValue(comparisons[1].activator))
        }
    }

    @Test
    fun `a real equality cover should not retain an unused integer conditional`() {
        val source = SmtLib.parse(
            "(declare-const x Real) " +
                "(assert (let ((s (ite (= x 0.0) 1 2))) (= (ite true 1 s) 1))) " +
                "(assert (= x 1.0))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertTrue(source.model.factors.all { it.intVars.isEmpty() })
        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `source work exhaustion should withhold the real equality cover`() {
        for (limit in listOf(4L, 6L)) {
            var work = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = work > limit
                override fun workMeter(): WorkMeter = WorkMeter { work += it }
            }
            val builder = Compiler.Builder(DEFAULT_UNBOUNDED_INT_LO, DEFAULT_UNBOUNDED_INT_HI,
                strictBounds = false, sourceCancellation = stop)
            builder.command(requireNotNull(SExprReader(StringCharSource("(declare-const x Real)")).readCommandOrNull()))
            val term = RealComb(emptyMap(), mapOf(0 to BigFraction.ONE), BigFraction.ZERO)
            val zero = RealComb(emptyMap(), emptyMap(), BigFraction.ZERO)

            assertFailsWith<PolynomialSourceInterrupted> {
                builder.reifyRealRel("=", term, zero)
            }

            assertTrue(work > limit)
            assertTrue(builder.factors.filterIsInstance<Clause>().isEmpty())
            assertEquals(2, builder.factors.filterIsInstance<ExactLinear>().size)
        }
    }

    @Test
    fun `identical folded real comparisons should share one predicate literal`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= a (<= x 1.0)))
                (assert (= b (<= (+ x 0.0) 1.0)))
            """.trimIndent(),
        )

        val predicates = source.model.factors.filterIsInstance<ExactLinear>()
            .filter { it.activator != LinearRow.ALWAYS }

        assertEquals(1, predicates.size)
    }

    @Test
    fun `repeated real predicates should agree in either Boolean polarity`() {
        for (truth in listOf(false, true)) {
            val source = SmtLib.parse(
                """
                    (declare-const x Real)
                    (declare-const a Bool)
                    (declare-const b Bool)
                    (assert (= a (> x 0.0)))
                    (assert (= b (> x 0.0)))
                    (assert (= a $truth))
                    (assert (= b ${!truth}))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `strict and closed real predicates should retain different boundary truth`() {
        for ((closed, strict) in listOf("<=" to "<", ">=" to ">")) {
            val source = SmtLib.parse(
                """
                    (declare-const x Real)
                    (declare-const a Bool)
                    (declare-const b Bool)
                    (assert (= x 0.0))
                    (assert (= a ($closed x 0.0)))
                    (assert (= b ($strict x 0.0)))
                    (assert a)
                    (assert (not b))
                """.trimIndent(),
            )

            val result = BacktrackSolver.source(source.model).solve()

            assertIs<OpenTheoryResult.Sat>(result)
        }
    }

    @Test
    fun `opposite real comparison directions should retain different truth`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= x 1.0))
                (assert (= a (< x 0.0)))
                (assert (= b (> x 0.0)))
                (assert (not a))
                (assert b)
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `integer and real predicate coordinates should retain separate namespaces`() {
        val source = SmtLib.parse(
            """
                (declare-const n Int)
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= n 0))
                (assert (= x 1.0))
                (assert (= a (< (to_real n) 0.5)))
                (assert (= b (< x 0.5)))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `nearby wide rational predicates should retain exact truth`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (assert (= x 1.0))
                (assert (= a (< x (/ 1152921504606846977.0 1152921504606846976.0))))
                (assert (= b (< x 1.0)))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `shared real predicates should retain helper and local coordinate scopes`() {
        val source = SmtLib.parse(
            """
                (declare-const x Real)
                (declare-const y Real)
                (declare-const a Bool)
                (declare-const b Bool)
                (define-fun positive () Bool (> x 0.0))
                (assert (= x 1.0))
                (assert (= y (- 1.0)))
                (assert (let ((x y)) (= a positive)))
                (assert (let ((x y)) (= b (> x 0.0))))
                (assert a)
                (assert (not b))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `each source parse should allocate its own real predicate names`() {
        SmtLib.parse("(declare-const x Real) (assert (or (> x 0.0) false))")
        val source = SmtLib.parse(
            """
                (declare-const y Real)
                (declare-const a Bool)
                (assert (not a))
                (assert (= y 1.0))
                (assert (or (> y 0.0) false))
            """.trimIndent(),
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Sat>(result)
    }

    @Test
    fun `wide real coefficients should retain original coordinates and exact witness values`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* $coefficient x) 1))")

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(
            BigFraction.of(BIG_ONE, parseBigInt(coefficient)),
            TheoryCompletion.parseRational(point.realValue(0)),
        )
        assertEquals(1, source.model.numRealVars)
        assertEquals(1, source.model.factors.filterIsInstance<ExactLinear>().size)
    }

    @Test
    fun `wide integer bindings should retain exact coefficients in real comparisons`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse(
            "(declare-const n Int) (declare-const x Real) (assert (= n 1)) " +
                "(assert (let ((wide (* $coefficient n))) (= wide x)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(BigFraction.of(parseBigInt(coefficient), BIG_ONE),
            TheoryCompletion.parseRational(point.realValue(source.realVarNames.getValue("x"))))
        assertEquals(1, source.model.numRealVars)
    }

    @Test
    fun `wide reified strict comparisons should preserve an excluded boundary`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse(
            "(declare-const x Real) (assert (= (* $coefficient x) 1)) " +
                "(assert (or (< (* $coefficient x) 0) (> (* $coefficient x) 1)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(1, source.model.numRealVars)
    }

    @Test
    fun `wide rational integer comparisons should preserve reified lattice truth`() {
        val numerator = "100000000000000000000000000000000000000000000000001"
        val denominator = "100000000000000000000000000000000000000000000000000"
        val source = SmtLib.parse(
            "(declare-const n Int) (declare-const b Bool) (assert (= n 1)) " +
                "(assert (= b (< (/ (* $numerator (to_real n)) $denominator) 1))) (assert (not b))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("1", point.intValue(source.intVarNames.getValue("n")))
        assertEquals(false, point.boolValue(source.boolVarNames.getValue("b")))
        assertEquals(0, source.model.numRealVars)
    }
}
