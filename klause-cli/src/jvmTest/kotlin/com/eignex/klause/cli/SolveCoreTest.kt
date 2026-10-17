package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SolveCoreTest(private val route: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun routes(): List<String> = listOf("quadratic-polynomial", "quadratic-integer", "polynomial", "open-linear")
    }

    @Test
    fun `source improvements should reach the output before terminal reporting without a duplicate final`() {
        val source = File.createTempFile("solve-core-stream", if (route == "polynomial") ".fzn" else ".smt2")
        source.writeText(sourceText())
        val common = CommonOptions().apply {
            engine = "cp"
            parallel = 1
            randomSeed = 17
            exact = true
        }
        if (route == "open-linear") common.presolve = "off"
        if (route == "quadratic-integer") {
            common.engine = "ls"
            common.engineParams += "max-flips=100000"
            common.engineParams += "nia-moves=2"
        }
        val session = if (route == "polynomial") MiniZincMode.newSession() else SmtLibMode.newSession()
        val values = mutableListOf<BigFraction>()
        var terminal = false
        var completed: Verdict? = null
        val output = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal, rendered)
                values += if (route == "open-linear") {
                    assertIs<SourceValue.Rational>(objective).value
                } else {
                    requireNotNull(assertIs<SourceValue.Algebraic>(objective).value.rational)
                }
                return true
            }
            override fun onVerdictContext(context: VerdictContext) {
                terminal = true
            }
            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
                assertEquals(null, rendered)
            }
            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        try {
            SolveCore.solve(session.load(source.absolutePath, common), common, output)
        } finally {
            source.delete()
        }

        val expectedLast = when (route) {
            "quadratic-integer" -> 3L
            "open-linear" -> 7L
            else -> 6L
        }
        assertEquals(BigFraction.ofLong(expectedLast), values.last())
        assertTrue(values.zipWithNext().all { (previous, next) -> next < previous })
        assertTrue(terminal)
        assertEquals(if (route == "quadratic-integer") Verdict.BEST_FOUND else Verdict.OPTIMAL, completed)
    }

    private fun sourceText(): String = when (route) {
        "quadratic-polynomial" -> """
            (declare-const b Bool) (declare-const x Real)
            (assert (= x (ite b 0 1))) (minimize (- 7 (* x x))) (check-sat)
        """.trimIndent()

        "open-linear" -> """
            (declare-const b Bool) (declare-const x Real) (declare-const z Int)
            (assert (>= z 0))
            (assert (= (* 3 x) (ite b 0 1)))
            (minimize (+ 7 x)) (check-sat)
        """.trimIndent()

        "quadratic-integer" -> """
            (declare-const i Int) (assert (>= i 0))
            (minimize (- 7 (* i i))) (check-sat)
        """.trimIndent()

        else -> """
            var 0..1: i :: output_var;
            var 6..7: cost :: output_var;
            var float: source_cost :: output_var;
            var float: r :: output_var;
            var float: x :: output_var;
            var float: y :: output_var;
            constraint int_lin_eq([1,1],[i,cost],7);
            constraint int2float(i,r);
            constraint int2float(cost,source_cost);
            constraint float_times(r,x,y);
            constraint float_le(0.0,x);
            constraint float_le(x,1.0);
            solve minimize source_cost;
        """.trimIndent()
    }
}
