package com.eignex.klause.theory.qfnia

import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
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
    fun `candidate slices preserve traversal and cumulative arithmetic work`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames,
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
    fun `cancelled sibling turn preserves the pending integer tuple`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames,
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
    fun `paused heuristic retains proposals and random state`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames,
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
    fun `zero candidate slice does not construct arithmetic state`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 4))", allowIntegers = true)
        val source = NonlinearIntegerProblem(
            parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames,
        )
        var charged = 0L
        val lifetime = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val cursor = NonlinearIntegerCursor(source, NonlinearIntegerParams(
            arithmetic = QfNraParams(cancellation = lifetime), strategy = NonlinearIntegerStrategy.SEARCH,
        ), PolynomialCondition())

        assertNull(cursor.runSlice(maximumCandidates = 0))
        assertEquals(0L, charged)
        cursor.close()
        cursor.close()
        assertFailsWith<IllegalStateException> { cursor.runSlice() }
    }

    @Test
    fun `closing completed cursor preserves captured exact witness`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= x 100000000000000000000))", allowIntegers = true,
        )
        val source = NonlinearIntegerProblem(
            parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.intVarNames, parsed.realVarNames, parsed.boolVarNames,
        )
        val cursor = NonlinearIntegerCursor(source, NonlinearIntegerParams(
            strategy = NonlinearIntegerStrategy.SEARCH,
        ), PolynomialCondition())
        val witness = assertIs<QfNraResult.Satisfiable>(requireNotNull(cursor.runSlice()).result).assignment

        cursor.close()

        assertEquals("100000000000000000000", witness.realValue(0).rational.toString())
    }
}
