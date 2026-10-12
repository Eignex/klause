package com.eignex.klause.theory.qfnia

import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraReason
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NonlinearIntegerSearchTest {
    @Test
    fun `search discovers exact integer witnesses outside machine words`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= x 100000000000000000000)) " +
                "(assert (= (* x x) 10000000000000000000000000000000000000000))",
            allowIntegers = true,
        )
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )

        val execution = solveNonlinearIntegers(problem, NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH))

        val witness = assertIs<QfNraResult.Satisfiable>(execution.result).assignment
        assertEquals("100000000000000000000", witness.realValue(0).rational.toString())
    }

    @Test
    fun `unsatisfiable integer candidate exhaustion never refutes an open source`() {
        val source = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )

        val execution = solveNonlinearIntegers(problem, NonlinearIntegerParams(maximumCandidates = 2))

        assertIs<QfNraResult.Unknown>(execution.result)
        assertTrue(execution.stats.candidates > 0)
    }

    @Test
    fun `heuristic strategies search checked integer candidates`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= (* x x) 4))", allowIntegers = true,
        )
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )

        for (strategy in listOf(NonlinearIntegerStrategy.LOCAL_SEARCH, NonlinearIntegerStrategy.ALNS)) {
            val execution = solveNonlinearIntegers(problem, NonlinearIntegerParams(strategy = strategy, maximumCandidates = 4))

            assertIs<QfNraResult.Satisfiable>(execution.result)
            assertTrue(execution.stats.candidates > 0)
            assertTrue(execution.stats.moves > 0)
        }
    }

    @Test
    fun `mixed source completion preserves exact irrational real witnesses`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const n Int) (declare-const x Real) (assert (= n 2)) " +
                "(assert (= (* x x) (to_real n)))", allowIntegers = true,
        )
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )

        val execution = solveNonlinearIntegers(problem, NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH))

        val witness = assertIs<QfNraResult.Satisfiable>(execution.result).assignment
        assertEquals("2", witness.realValue(0).rational.toString())
        assertEquals(null, witness.realValue(1).rational)
    }

    @Test
    fun `whole real relaxation refutation belongs only to the search strategy`() {
        val source = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) (- 1)))", allowIntegers = true)
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )

        val search = solveNonlinearIntegers(problem, NonlinearIntegerParams(strategy = NonlinearIntegerStrategy.SEARCH))
        val heuristic = solveNonlinearIntegers(problem, NonlinearIntegerParams(
            strategy = NonlinearIntegerStrategy.LOCAL_SEARCH, maximumCandidates = 2,
        ))

        assertIs<QfNraResult.Unsatisfiable>(search.result)
        assertIs<QfNraResult.Unknown>(heuristic.result)
    }

    @Test
    fun `one work allowance covers candidate completion and original source checking`() {
        val source = SmtLibPolynomial.parse("(declare-const x Int) (assert (= (* x x) 2))", allowIntegers = true)
        val problem = NonlinearIntegerProblem(
            source.formula, source.realCount, source.boolCount, source.intVarNames, source.realVarNames, source.boolVarNames,
        )
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val execution = solveNonlinearIntegers(problem, NonlinearIntegerParams(
            arithmetic = QfNraParams(cancellation = cancellation, maximumWork = 3),
        ))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(execution.result).reason)
        assertEquals(3L, charged)
        assertEquals(charged, execution.stats.polynomial.workSpent)
    }
}
