package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.FiniteSolveVerdict
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceFiniteControls
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SmtArithmeticReductionTest {
    private var diagnostic: String? = null

    @Test
    fun `variable integer division should keep euclidean signs`() {
        val input = SmtLib.parse("(declare-const n Int) (declare-const d Int) " +
            "(assert (= n (- 7))) (assert (= d (- 3))) " +
            "(assert (= (div n d) 3)) (assert (= (mod n d) 2))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `variable integer division should refute a wrong remainder`() {
        val input = SmtLib.parse("(declare-const n Int) (declare-const d Int) " +
            "(assert (= n (- 7))) (assert (= d 3)) (assert (= (mod n d) (- 1)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `variable integer division should refute a wrong quotient`() {
        val input = SmtLib.parse("(declare-const n Int) (declare-const d Int) " +
            "(assert (= n (- 7))) (assert (= d 3)) (assert (= (div n d) (- 2)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `zero integer division should preserve evaluated argument congruence`() {
        val input = SmtLib.parse("(declare-const x Int) (declare-const y Int) " +
            "(assert (= x y)) (assert (distinct (div (+ x 1) 0) (div (+ 1 y) 0)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `zero quotient and remainder should have independent total values`() {
        val input = SmtLib.parse("(assert (= (div 4 0) 12)) (assert (= (mod 4 0) (- 9)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `variable real division should preserve its exact rational value`() {
        val input = SmtLib.parse("(declare-const n Real) (declare-const d Real) " +
            "(assert (= n 1.0)) (assert (= d 2.0)) (assert (= (/ n d) 0.5))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `zero real division should preserve evaluated argument congruence`() {
        val input = SmtLib.parse("(declare-const x Real) (declare-const y Real) " +
            "(assert (= x y)) (assert (distinct (/ (+ x 1.0) 0.0) (/ (+ 1.0 y) 0.0)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `variable real division should refute an incorrect quotient`() {
        val input = SmtLib.parse("(declare-const n Real) (declare-const d Real) " +
            "(assert (= n 1.0)) (assert (= d 2.0)) (assert (= (/ n d) 0.6))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `nonlinear floor should round negative arguments down`() {
        val input = SmtLib.parse("(declare-const x Real) (assert (= x 1.5)) " +
            "(assert (= (to_int (- (* x x))) (- 3)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `nonlinear integrality should reject a fractional square`() {
        val input = SmtLib.parse("(declare-const x Real) (assert (= x 0.5)) (assert (is_int (* x x)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `nonlinear floor should refute rounding toward zero`() {
        val input = SmtLib.parse("(declare-const x Real) (assert (= x 1.5)) " +
            "(assert (= (to_int (- (* x x))) (- 2)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `nonlinear integrality should accept an integer square`() {
        val input = SmtLib.parse("(declare-const x Real) (assert (= x 2.0)) (assert (is_int (* x x)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `indexed divisibility should accept arbitrary width indices`() {
        val input = SmtLib.parse("(declare-const x Int) (assert (= x 18446744073709551618)) " +
            "(assert ((_ divisible 18446744073709551618) x))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `indexed divisibility should refute a nonmultiple`() {
        val input = SmtLib.parse("(declare-const x Int) (assert (= x 7)) (assert ((_ divisible 3) x))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `symbolic integer powers should have exact integer witnesses`() {
        val input = SmtLib.parse("(declare-const b Int) (declare-const e Int) " +
            "(assert (= b 2)) (assert (= e 3)) (assert (= (** b e) 8))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `negative integer powers should use the specified integer convention`() {
        val input = SmtLib.parse("(assert (= (** (- 1) (- 3)) (- 1)))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `symbolic integer powers should refute an incorrect value`() {
        val input = SmtLib.parse("(declare-const b Int) (declare-const e Int) " +
            "(assert (= b 2)) (assert (= e 3)) (assert (= (** b e) 9))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `integer exponentiation should reject real exponents in dead branches`() {
        assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(assert (or true (= (** 2 1.5) 2)))")
        }
    }

    @Test
    fun `divisibility should reject invalid indexes in dead branches`() {
        for (operator in listOf("(_ divisible 0)", "(_ divisible |3|)", "(|_| divisible 3)")) {
            assertFailsWith<UnsupportedSmtException> {
                SmtLib.parse("(assert (or true ($operator 2)))")
            }
        }
    }

    @Test
    fun `qualified indexed divisibility should retain its Boolean result sort`() {
        val input = SmtLib.parse("(assert ((as (_ divisible 3) Bool) 6))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `quoted indexed divisibility should accept multiples`() {
        for (operator in listOf("(_ |divisible| 3)", "(as (_ |divisible| 3) Bool)")) {
            val input = SmtLib.parse("(assert ($operator 6))")

            val result = solve(input)

            assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
        }
    }

    @Test
    fun `quoted indexed divisibility should refute nonmultiples`() {
        for (operator in listOf("(_ |divisible| 3)", "(as (_ |divisible| 3) Bool)")) {
            val input = SmtLib.parse("(assert ($operator 7))")

            val result = solve(input)

            assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
        }
    }

    @Test
    fun `qualified integer division should retain its integer result sort`() {
        val input = SmtLib.parse("(assert (= ((as div Int) 7 3) 2))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `floor checks should retain native algebraic authority`() {
        val input = SmtLib.parse("(declare-const x Real) " +
            "(assert (= (* x x) 2.0)) (assert (> x 0.0)) (assert (= (to_int x) 1))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `source arithmetic rejection should withhold library satisfaction`() {
        val original = SmtLib.parse("(declare-const b Int) (declare-const e Int) " +
            "(assert (= b 2)) (assert (= e 3)) (assert (= (** b 3) 8))")
        val input = original.copy(sourceChecks = original.sourceChecks +
            SmtArithmeticCheck(SmtArithmeticOperation.POWER, listOf("b", "e", "b")))
            .withSourceWitnessValidation()

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNKNOWN, result, diagnostic)
    }

    @Test
    fun `inactive arithmetic operands should preserve logarithm domains`() {
        val input = SmtLib.parse("(assert (= (ite false (to_int (log (- 1.0))) 0) 0))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `active floor obligations should survive an earlier inactive occurrence`() {
        val input = SmtLib.parse("(declare-const p Bool) (assert (not p)) " +
            "(assert (= (ite p (to_int (log 2.0)) 0) 0)) " +
            "(assert (= (ite (not p) (to_int (log 2.0)) 0) 1))")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
    }

    @Test
    fun `source integer power checks should reject an incorrect candidate`() {
        val values = object : SmtModelValues {
            override val cancellation = com.eignex.klause.util.ExecutionControl.Never
            override fun checkpoint(units: Long) = Unit
            override fun integer(name: String): String = mapOf("b" to "2", "e" to "3", "r" to "7").getValue(name)
            override fun boolean(name: String): Boolean = error("unexpected Boolean")
            override fun real(name: String): String = error("unexpected real")
            override fun value(name: String): SmtValue = SmtValue.Integer(integer(name))
        }

        val accepted = SmtArithmeticCheck(SmtArithmeticOperation.POWER, listOf("b", "e", "r")).check(values)

        assertEquals(false, accepted)
    }

    @Test
    fun `zero division interpretation should retain the selected result`() {
        val original = SmtLib.parse("(assert (= (div 4 0) 12))")
        var interpretation: SmtValue.Function? = null
        val input = original.copy(sourceChecks = original.sourceChecks + SmtSourceCheck { values ->
            interpretation = values.value("@operation:div") as SmtValue.Function
            true
        }).withSourceWitnessValidation()

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
        assertEquals(listOf(listOf(SmtValue.Integer("4"), SmtValue.Integer("0")) to SmtValue.Integer("12")),
            interpretation?.entries)
    }

    @Test
    fun `unobserved zero division arguments should have one total default`() {
        val original = SmtLib.parse("(declare-const |operation:div| Int) (assert (= |operation:div| 5))")
        var interpretation: SmtValue.Function? = null
        val input = original.copy(sourceChecks = original.sourceChecks + SmtSourceCheck { values ->
            interpretation = values.value("@operation:div") as SmtValue.Function
            values.integer("operation:div") == "5"
        }).withSourceWitnessValidation()

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
        assertEquals(SmtValue.Integer("0"), interpretation?.default)
        assertEquals(emptyList(), interpretation?.entries)
    }

    @Test
    fun `lexical literals should remain independent of quoted coordinate fixings`() {
        val scripts = listOf(
            "(declare-const |1| Int) (assert (= 1 2))",
            "(declare-const |1| Int) (assert (= |1| 2)) (assert (= 1 2))",
            "(declare-const |1.5| Real) (assert (= 1.5 2.5))",
            "(declare-const |1.5| Real) (assert (= |1.5| 2.5)) (assert (= 1.5 2.5))",
            "(declare-const |#b1| Int) (assert (= |#b1| 2)) (assert (= #b1 #b0))")

        for (script in scripts) {
            val result = solve(SmtLib.parse("$script (assert (= (** 1 0) 1))"))

            assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
        }
    }

    @Test
    fun `quoted numeric coordinates should retain their independently checked source value`() {
        val original = SmtLib.parse("(declare-const |1| Int) (declare-const |2| Int) " +
            "(assert (= |1| 2)) (assert (= |2| 3)) (assert (= (+ 1 |1| |2|) 6)) " +
            "(assert (= (** 1 0) 1))")
        val input = original.copy(sourceChecks = original.sourceChecks + SmtSourceCheck { values ->
            values.integer("1") == "2" && values.integer("2") == "3"
        }).withSourceWitnessValidation()

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    @Test
    fun `general division should retain independently refuted integer premises`() {
        for (term in listOf("(/ x x)", "(div (* x x) 2)")) {
            val input = SmtLib.parse("(declare-const x Int) (declare-const y Int) " +
                "(assert (= (* 2 x) (+ (* 2 y) 1))) (assert (= $term 0))")

            val result = solve(input)

            assertEquals(FiniteSolveVerdict.UNSAT, result, diagnostic)
        }
    }

    @Test
    fun `zero real division should remain total in an absorbed Boolean branch`() {
        val input = SmtLib.parse("(declare-const b Bool) " +
            "(assert (= b (or true (> (/ 1.0 0.0) 0.0)))) (assert b)")

        val result = solve(input)

        assertEquals(FiniteSolveVerdict.SAT, result, diagnostic)
    }

    private fun solve(input: SmtLibProblem): FiniteSolveVerdict {
        val invocation = SourceSetup.prepare(SourceSetupRequest(input.source, ExecutionOperation.SATISFY,
            FiniteEngine.BACKTRACK))
        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
            presolveConfig = PresolveConfig.NONE))
        val native = when (result) {
            is SourceExecutionResult.Finite ->
                return assertIs<FiniteSolveOutcome.Completed>(result.result.outcome).verdict
            is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(result.result).result
            is SourceExecutionResult.Polynomial -> result.result.result
            is SourceExecutionResult.Transcendental -> result.result.result
            is SourceExecutionResult.Refuted -> return FiniteSolveVerdict.UNSAT
            is SourceExecutionResult.Declined -> return FiniteSolveVerdict.UNKNOWN
            else -> error("unexpected arithmetic execution")
        }
        diagnostic = native.toString()
        return when (native) {
            is OpenTheoryResult.Sat -> FiniteSolveVerdict.SAT
            is OpenTheoryResult.Unsat -> FiniteSolveVerdict.UNSAT
            is OpenTheoryResult.Unknown -> FiniteSolveVerdict.UNKNOWN
        }
    }
}
