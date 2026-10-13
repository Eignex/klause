package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import java.io.File
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtLibModeTest(private val route: String, private val engine: String, private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} maximize={2}")
        fun variants(): List<Array<Any>> {
            val engines = listOf("cp", "fixed", "mixed", "ls", "alns")
            return listOf("ordinary", "quadratic").flatMap { route ->
                engines.flatMap { engine -> listOf(false, true).map { arrayOf<Any>(route, engine, it) } }
            }
        }
    }

    @Test
    fun `source receipts retain exact objective units through ordinary and native routing`() {
        val direction = if (maximize) "maximize" else "minimize"
        val objective = if (route == "ordinary") "(+ 7 (* 0.25 x))" else "(+ 0.3 (* 0.1 x))"
        val source = File.createTempFile("smt-source-objective", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* 3 x) 1)) " +
                "($direction $objective) (check-sat)")
        }
        val common = CommonOptions().apply {
            this.engine = this@SmtLibModeTest.engine
            parallel = 1
            randomSeed = 17
            presolve = "off"
            engineParams += "max-flips=12"
            engineParams += "alns-iterations=1"
        }
        val session = SmtLibMode.newSession()
        val values = mutableListOf<BigFraction>()
        var terminal = false
        var completed: Verdict? = null
        val output = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                assertTrue("(/ 1.0 3.0)" in rendered, rendered)
                values += if (route == "ordinary") assertIs<SourceValue.Rational>(objective).value
                else assertNotNull(assertIs<SourceValue.Algebraic>(objective).value.rational)
                return true
            }
            override fun onVerdictContext(context: VerdictContext) { terminal = true }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        try {
            val solvable = session.load(source.absolutePath, common)
            if (route == "ordinary") assertIs<SolvablePipeline.OpenTheory>(solvable.pipeline)
            else assertIs<SolvablePipeline.QuadraticTheory>(solvable.pipeline)
            SolveCore.solve(solvable, common, output)
        } finally {
            source.delete()
        }

        val expected = if (route == "ordinary") BigFraction.ofLong(85) * BigFraction.ofLong(12).reciprocal()
        else BigFraction.ofLong(3).reciprocal()
        assertEquals(listOf(expected), values)
        assertTrue(terminal)
        assertEquals(if (engine == "ls" || engine == "alns") Verdict.BEST_FOUND else Verdict.OPTIMAL, completed)
    }
}
