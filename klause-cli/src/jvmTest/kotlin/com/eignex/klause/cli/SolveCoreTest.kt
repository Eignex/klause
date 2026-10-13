package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import java.io.File
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SolveCoreTest(private val route: String, private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} maximize={1}")
        fun variants(): List<Array<Any>> {
            val routes = listOf("quadratic-polynomial", "quadratic-integer", "polynomial", "open-linear")
            return routes.flatMap { route -> listOf(false, true).map { arrayOf<Any>(route, it) } }
        }
    }

    @Test
    fun `source improvements reach the output before terminal reporting without a duplicate final`() {
        val direction = if (maximize) "maximize" else "minimize"
        val objective = if (maximize) "(+ 7 (* x x))" else "(- 7 (* x x))"
        val source = File.createTempFile("solve-core-stream", if (route == "polynomial") ".fzn" else ".smt2")
        source.writeText(when (route) {
            "quadratic-polynomial" -> """
                (declare-const b Bool) (declare-const x Real)
                (assert (= x (ite b 0 1))) ($direction $objective) (check-sat)
            """.trimIndent()
            "open-linear" -> """
                (declare-const b Bool) (declare-const x Real)
                (assert (= (* 3 x) (ite b 0 1)))
                ($direction ${if (maximize) "(- 7 x)" else "(+ 7 x)"}) (check-sat)
            """.trimIndent()
            "quadratic-integer" -> """
                (declare-const i Int) (assert (and (>= i 0) (<= i 100)))
                ($direction ${if (maximize) "(+ 7 (* i i))" else "(- 7 (* i i))"}) (check-sat)
            """.trimIndent()
            else -> """
                var 0..1: i :: output_var;
                var 6..8: cost :: output_var;
                var float: r :: output_var;
                var float: x :: output_var;
                var float: y :: output_var;
                constraint int_lin_eq([${if (maximize) -1 else 1},1],[i,cost],7);
                constraint int2float(i,r);
                constraint float_times(r,x,y);
                constraint float_le(-2.0,x);
                constraint float_le(x,2.0);
                solve $direction cost;
            """.trimIndent()
        })
        val common = CommonOptions().apply { engine = "cp"; parallel = 1; randomSeed = 17; exact = true }
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
                assertTrue(rendered.isNotBlank())
                values += if (route == "open-linear") assertIs<SourceValue.Rational>(objective).value
                else requireNotNull(assertIs<SourceValue.Algebraic>(objective).value.rational)
                return true
            }
            override fun onVerdictContext(context: VerdictContext) { terminal = true }
            override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
                assertEquals(null, rendered)
            }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        try {
            val solvable = session.load(source.absolutePath, common)
            if (route == "polynomial") assertIs<SolvablePipeline.PolynomialTheory>(solvable.pipeline)
            else if (route == "open-linear") assertIs<SolvablePipeline.OpenTheory>(solvable.pipeline)
            else {
                val pipeline = assertIs<SolvablePipeline.QuadraticTheory>(solvable.pipeline)
                if (route == "quadratic-integer") assertIs<QuadraticRequest.Integer>(pipeline.request)
                else assertIs<QuadraticRequest.Polynomial>(pipeline.request)
            }
            SolveCore.solve(solvable, common, output)
        } finally {
            source.delete()
        }

        val expected = if (route == "quadratic-integer") {
            if (maximize) listOf(8L, 11L) else listOf(6L, 3L)
        } else listOf(7L, if (maximize) 8L else 6L)
        val expectedValues = if (route == "open-linear") {
            val third = BigFraction.ofLong(3).reciprocal()
            listOf(BigFraction.ofLong(7) + if (maximize) third.negated() else third, BigFraction.ofLong(7))
        } else expected.map(BigFraction::ofLong)
        assertEquals(expectedValues, values)
        assertTrue(terminal)
        assertEquals(if (route == "quadratic-integer") Verdict.BEST_FOUND else Verdict.OPTIMAL, completed)
    }
}
