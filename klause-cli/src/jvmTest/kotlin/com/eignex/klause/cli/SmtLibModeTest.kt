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
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtLibModeTest(private val engine: String, private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} maximize={1}")
        fun variants(): List<Array<Any>> {
            val engines = listOf("cp", "fixed", "mixed", "ls", "alns")
            return engines.flatMap { engine -> listOf(false, true).map { arrayOf<Any>(engine, it) } }
        }
    }

    @Test
    fun `ordinary source receipts retain exact fractional coefficients and constants in either direction`() {
        val direction = if (maximize) "maximize" else "minimize"
        val source = File.createTempFile("smt-source-objective", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* 3 x) 1)) " +
                "($direction (+ 0.3 (* 0.1 x))) (check-sat)")
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
                values += assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onVerdictContext(context: VerdictContext) { terminal = true }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        try {
            val solvable = session.load(source.absolutePath, common)
            assertIs<SolvablePipeline.OpenTheory>(solvable.pipeline)
            SolveCore.solve(solvable, common, output)
        } finally {
            source.delete()
        }

        assertEquals(listOf(BigFraction.ofLong(3).reciprocal()), values)
        assertTrue(terminal)
        assertEquals(if (engine == "ls" || engine == "alns") Verdict.BEST_FOUND else Verdict.OPTIMAL, completed)
    }
}
