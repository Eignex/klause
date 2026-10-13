package com.eignex.klause.cli

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class SolveCoreOpenLocalSearchTest(private val maximize: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "maximize={0}")
        fun variants(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }

    @Test
    fun `open local search publishes its original exact cost before incomplete terminal reporting`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val original = LinearObjective(realCoefficients = doubleArrayOf(1.0), constant = 7)
        val solvable = openLocalSearchSolvable(model, {
            renderOpenTheoryModel(emptyMap(), emptyMap(), mapOf("x" to 0), it)
        }, objective = if (maximize) original.negated() else original, maximize = maximize)
        val common = CommonOptions().apply {
            engine = "ls"
            parallel = 1
            engineParams += "max-flips=12"
        }
        val values = mutableListOf<BigFraction>()
        var terminal = false
        var completed: Verdict? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                assertTrue("(/ 1.0 3.0)" in rendered, rendered)
                values += assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onVerdictContext(context: VerdictContext) {
                assertFalse(context.completePool)
                terminal = true
            }
            override fun onComplete(verdict: Verdict) { completed = verdict }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(listOf(BigFraction.ofLong(22) * BigFraction.ofLong(3).reciprocal()), values)
        assertTrue(terminal)
        assertEquals(Verdict.BEST_FOUND, completed)
    }
}
