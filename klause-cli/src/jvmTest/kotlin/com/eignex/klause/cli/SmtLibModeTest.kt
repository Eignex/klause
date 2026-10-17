package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SmtLibModeTest(private val route: String, private val engine: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun variants(): List<Array<Any>> = listOf("ordinary", "quadratic").flatMap { route ->
            listOf("cp", "ls").map { arrayOf<Any>(route, it) }
        }
    }

    @Test
    fun `source receipts should retain exact objective units through ordinary and native routing`() {
        val objective = if (route == "ordinary") "(+ 7 (* 0.25 x))" else "(+ 0.3 (* 0.1 x))"
        val source = File.createTempFile("smt-source-objective", ".smt2").apply {
            writeText("(declare-const x Real) (assert (= (* 3 x) 1)) (minimize $objective) (check-sat)")
        }
        val common = CommonOptions().apply {
            this.engine = this@SmtLibModeTest.engine
            parallel = 1
            randomSeed = 17
            if (route == "ordinary") presolve = "off"
            engineParams += "max-flips=12"
        }
        val session = SmtLibMode.newSession()
        val values = mutableListOf<BigFraction>()
        var terminal = false
        var completed: Verdict? = null
        val output = object : OutputProtocol by session.output(common) {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                values += if (route == "ordinary") {
                    assertIs<SourceValue.Rational>(objective).value
                } else {
                    assertNotNull(assertIs<SourceValue.Algebraic>(objective).value.rational)
                }
                return true
            }
            override fun onVerdictContext(context: VerdictContext) {
                terminal = true
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

        val expected = if (route == "ordinary") {
            BigFraction.ofLong(85) * BigFraction.ofLong(12).reciprocal()
        } else {
            BigFraction.ofLong(3).reciprocal()
        }
        assertEquals(listOf(expected), values)
        assertTrue(terminal)
        assertEquals(if (engine == "ls") Verdict.BEST_FOUND else Verdict.OPTIMAL, completed)
    }
}
