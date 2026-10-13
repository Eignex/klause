package com.eignex.klause.cli

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SolveCoreCancellationTest {
    @Test
    fun `ordinary source publication cancellation preserves its original exception without terminal claims`() {
        val model = Problem(2, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(boolWeights = longArrayOf(-2, -1), constant = 7)
        val solvable = openTheorySolvable(OpenTheoryRequest(model, objective), {
            renderOpenTheoryModel(emptyMap(), mapOf("a" to 0, "b" to 1), mapOf("x" to 0), it)
        })
        val common = CommonOptions().apply { engine = "cp"; parallel = 1; randomSeed = 17; presolve = "off" }
        val expected = CancellationException("linear consumer cancelled")
        val values = mutableListOf<BigFraction>()
        var terminal = false
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                assertFalse(terminal)
                assertTrue("(/ 1.0 3.0)" in rendered, rendered)
                values += assertIs<SourceValue.Rational>(objective).value
                if (values.size == 2) throw expected
                return true
            }
            override fun onVerdictContext(context: VerdictContext) { terminal = true }
            override fun onComplete(verdict: Verdict) = error("cancelled publication cannot emit a terminal claim")
        }

        val thrown = assertFailsWith<CancellationException> { SolveCore.solve(solvable, common, output) }

        assertSame(expected, thrown)
        assertEquals(listOf(BigFraction.ofLong(7), BigFraction.ofLong(6)), values)
        assertFalse(terminal)
    }

}
