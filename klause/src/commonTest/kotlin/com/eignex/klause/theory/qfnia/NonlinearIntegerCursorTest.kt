package com.eignex.klause.theory.qfnia

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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NonlinearIntegerCursorTest {
    @Test
    fun `complement steering should preserve charged attempts and reach new allowed tuples`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        val params = NonlinearIntegerParams(arithmetic = QfNraParams(cellRefinement = true),
            strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 6, reuseSourceCells = true)
        val baseline = solveNonlinearIntegers(source, params)

        val steered = solveNonlinearIntegers(source, params.copy(steerSourceCells = true))

        assertIs<QfNraResult.Unknown>(steered.result)
        assertEquals(baseline.stats.candidates, steered.stats.candidates)
        assertTrue(steered.stats.cells.steered > 0)
        assertTrue(steered.stats.cells.allowed > baseline.stats.cells.allowed)
    }

    @Test
    fun `steering should preserve slices and disposal for traversal and real repairs`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const q Int) (declare-const r Real) " +
                "(assert (= (* n n) 2)) (assert (= (* r r) 0))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        for (strategy in NonlinearIntegerStrategy.entries) {
            val params = NonlinearIntegerParams(arithmetic = QfNraParams(cellRefinement = true),
                strategy = strategy, maximumCandidates = 4, reuseSourceCells = true, steerSourceCells = true)
            val whole = solveNonlinearIntegers(source, params)
            val cursor = NonlinearIntegerCursor(source, params, PolynomialCondition())
            assertNull(cursor.runSlice(maximumCandidates = 2))
            val before = cursor.stats
            assertNull(cursor.runSlice(Cancellation { true }, 2))
            assertEquals(before, cursor.stats)

            val sliced = requireNotNull(cursor.runSlice(maximumCandidates = 2))
            cursor.close()

            assertEquals(whole.stats, sliced.stats)
            assertIs<QfNraResult.Unknown>(sliced.result)
            assertFailsWith<IllegalStateException> { cursor.runSlice() }
        }
    }

    @Test
    fun `source cells should avoid completions of unvisited tuples with repeated support`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        val params = NonlinearIntegerParams(arithmetic = QfNraParams(cellRefinement = true),
            strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 12)
        val baseline = solveNonlinearIntegers(source, params)

        val reused = solveNonlinearIntegers(source, params.copy(reuseSourceCells = true))

        assertIs<QfNraResult.Unknown>(baseline.result)
        assertIs<QfNraResult.Unknown>(reused.result)
        assertTrue(reused.stats.cells.excluded > 0)
        assertTrue(reused.stats.cells.repeated > 0)
        assertEquals(baseline.stats.checks - reused.stats.checks, reused.stats.cells.excluded)
        assertEquals(baseline.stats.candidates, reused.stats.candidates)
    }

    @Test
    fun `source cells should preserve an integer root beside irrational roots`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const r Real) (declare-const n Int) " +
                "(assert (= (* (- (* n n) 2) (- n 2)) 0)) (assert (= (* r r) 0))",
            allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)

        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(
            arithmetic = QfNraParams(cellRefinement = true), strategy = NonlinearIntegerStrategy.SEARCH,
            maximumCandidates = 8, reuseSourceCells = true,
        ))

        val assignment = assertIs<QfNraResult.Satisfiable>(execution.result).assignment
        assertEquals(BigFraction.ofLong(2), assignment.realValue(1).rational)
        assertEquals(BigFraction.ZERO, assignment.realValue(0).rational)
        assertTrue(execution.stats.cells.excluded > 0)
    }

    @Test
    fun `conditional cells should preserve the opposite Boolean branch in a replacement cursor`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const b Bool) " +
                "(assert (or (and b (= (* n n) 2)) (and (not b) (= (* n n) 4))))",
            allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        val params = NonlinearIntegerParams(arithmetic = QfNraParams(cellRefinement = true),
            strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 8, reuseSourceCells = true,
            steerSourceCells = true)
        val first = solveNonlinearIntegers(source, params, PolynomialCondition(bools = mapOf(0 to true)))
        assertIs<QfNraResult.Unknown>(first.result)
        assertTrue(first.stats.cells.declined > 0)
        assertEquals(0L, first.stats.cells.excluded)

        val replacement = solveNonlinearIntegers(source, params, PolynomialCondition(bools = mapOf(0 to false)))

        val assignment = assertIs<QfNraResult.Satisfiable>(replacement.result).assignment
        assertEquals(false, assignment.boolValue(0))
        val value = requireNotNull(assignment.realValue(0).rational)
        assertEquals(BigFraction.ofLong(4), value * value)
    }

    @Test
    fun `producer and integer hull work should charge the containing meter once across completions`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        var charged = 0L
        val lifetime = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val execution = solveNonlinearIntegers(source, NonlinearIntegerParams(
            arithmetic = QfNraParams(cancellation = lifetime, maximumWork = 8_000, cellRefinement = true),
            strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 12, reuseSourceCells = true,
            steerSourceCells = true,
        ))

        assertEquals(execution.stats.polynomial.workSpent, charged)
        assertTrue(charged > 0)
        assertIs<QfNraResult.Unknown>(execution.result)
    }

    @Test
    fun `cell reuse should preserve slicing and a cancelled turn without resetting work`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const q Int) (assert (= (* n n) 2))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames)
        val params = NonlinearIntegerParams(arithmetic = QfNraParams(cellRefinement = true),
            strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 12, reuseSourceCells = true)
        val whole = solveNonlinearIntegers(source, params)
        NonlinearIntegerCursor(source, params, PolynomialCondition()).use { cursor ->
            assertNull(cursor.runSlice(maximumCandidates = 6))
            val before = cursor.stats
            assertNull(cursor.runSlice(Cancellation { true }, 6))
            assertEquals(before, cursor.stats)

            val sliced = requireNotNull(cursor.runSlice(maximumCandidates = 6))

            assertEquals(whole.stats, sliced.stats)
            assertTrue(sliced.stats.cells.excluded > 0)
            assertIs<QfNraResult.Unknown>(sliced.result)
        }
    }

    @Test
    fun `integer window exhaustion should remain unknown when its real relaxation is satisfiable`() {
        val parsed = SmtLibPolynomial.parse("(declare-const n Int) (assert (= (* n n) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        val condition = PolynomialCondition(
            lower = mapOf(0 to BigFraction.ZERO),
            upper = mapOf(0 to BigFraction.ofLong(2)),
        )

        val execution = solveNonlinearIntegers(
            source,
            NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 4),
            condition,
        )

        assertIs<QfNraResult.Unknown>(execution.result)
    }

    @Test
    fun `wider condition should preserve a witness outside an earlier refuted window`() {
        val parsed = SmtLibPolynomial.parse("(declare-const n Int) (assert (= (* n n) 4))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        val params = NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH)
        val lower = mapOf(0 to BigFraction.ZERO)
        val narrow = solveNonlinearIntegers(
            source,
            params,
            PolynomialCondition(
                lower = lower,
                upper = mapOf(0 to BigFraction.ONE),
            ),
        )
        assertIs<QfNraResult.Unsatisfiable>(narrow.result)

        val widened = solveNonlinearIntegers(
            source,
            params,
            PolynomialCondition(
                lower = lower,
                upper = mapOf(0 to BigFraction.ofLong(2)),
            ),
        )

        val witness = assertIs<QfNraResult.Satisfiable>(widened.result).assignment
        assertEquals(BigFraction.ofLong(2), witness.realValue(0).rational)
    }

    @Test
    fun `failed tuple should preserve satisfiable neighbors under opposite Boolean conditions`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const r Real) (declare-const b Bool) " +
                "(assert (= (* n n) 1)) (assert (= (* r r) 0)) " +
                "(assert (or (and b (< n 0)) (and (not b) (> n 0))))",
            allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        for ((pin, expected) in listOf(true to BigFraction.MINUS_ONE, false to BigFraction.ONE)) {
            NonlinearIntegerCursor(
                source,
                NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.LOCAL_SEARCH, maximumCandidates = 2),
                PolynomialCondition(bools = mapOf(0 to pin)),
            ).use { cursor ->
                assertNull(cursor.runSlice(maximumCandidates = 1))
                val witness = assertIs<QfNraResult.Satisfiable>(requireNotNull(cursor.runSlice()).result).assignment

                assertEquals(expected, witness.realValue(0).rational)
                assertEquals(BigFraction.ZERO, witness.realValue(1).rational)
                assertEquals(pin, witness.boolValue(0))
            }
        }
    }

    @Test
    fun `candidate slices should preserve traversal and cumulative arithmetic work`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        val params = NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 3)
        val whole = solveNonlinearIntegers(source, params)

        NonlinearIntegerCursor(source, params, PolynomialCondition()).use { cursor ->
            assertNull(cursor.runSlice(maximumCandidates = 1))
            assertEquals(1L, cursor.stats.candidates)
            assertNull(cursor.runSlice(maximumCandidates = 1))
            val sliced = requireNotNull(cursor.runSlice(maximumCandidates = 1))

            assertIs<QfNraResult.Unknown>(sliced.result)
            assertEquals(whole.stats, sliced.stats)
            assertEquals(sliced, cursor.runSlice())
        }
    }

    @Test
    fun `cancelled sibling turn should preserve the pending integer tuple`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        val params = NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH, maximumCandidates = 2)
        val whole = solveNonlinearIntegers(source, params)

        NonlinearIntegerCursor(source, params, PolynomialCondition()).use { cursor ->
            assertNull(cursor.runSlice(maximumCandidates = 1))
            val before = cursor.stats
            assertNull(cursor.runSlice(Cancellation { true }, 1))
            assertEquals(before, cursor.stats)
            val resumed = requireNotNull(cursor.runSlice(maximumCandidates = 1))

            assertEquals(whole.stats, resumed.stats)
            assertIs<QfNraResult.Unknown>(resumed.result)
        }
    }

    @Test
    fun `paused heuristic should retain proposals and random state`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        for (strategy in listOf(NonlinearIntegerStrategy.LOCAL_SEARCH, NonlinearIntegerStrategy.ALNS)) {
            val params = NonlinearIntegerParams(strategy = strategy, maximumCandidates = 2, seed = 7)
            val whole = solveNonlinearIntegers(source, params)

            NonlinearIntegerCursor(source, params, PolynomialCondition()).use { cursor ->
                assertNull(cursor.runSlice(maximumCandidates = 1))
                val sliced = requireNotNull(cursor.runSlice(maximumCandidates = 1))

                assertIs<QfNraResult.Unknown>(sliced.result)
                assertEquals(whole.stats, sliced.stats)
                assertTrue(sliced.stats.moves > 0)
            }
        }
    }

    @Test
    fun `zero candidate slice should not construct arithmetic state`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 4))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        var charged = 0L
        val lifetime = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val cursor = NonlinearIntegerCursor(
            source,
            NonlinearIntegerParams(
                arithmetic = QfNraParams(cancellation = lifetime),
                strategy = NonlinearIntegerStrategy.SEARCH,
            ),
            PolynomialCondition(),
        )

        assertNull(cursor.runSlice(maximumCandidates = 0))
        assertEquals(0L, charged)
        cursor.close()
        cursor.close()
        assertFailsWith<IllegalStateException> { cursor.runSlice() }
    }

    @Test
    fun `closing completed cursor should preserve captured exact witness`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= x 100000000000000000000))",
            allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
        val cursor = NonlinearIntegerCursor(
            source,
            NonlinearIntegerParams(
                strategy = NonlinearIntegerStrategy.SEARCH,
            ),
            PolynomialCondition(),
        )
        val witness = assertIs<QfNraResult.Satisfiable>(requireNotNull(cursor.runSlice()).result).assignment

        cursor.close()

        assertEquals("100000000000000000000", witness.realValue(0).rational.toString())
    }
}
