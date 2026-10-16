package com.eignex.klause.cli

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LinearSourceSolvingTest(private val engine: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<String> = listOf("fixed", "cp", "mixed", "ls", "alns")
    }

    @Test
    fun `finite affine maximization should publish original fractional units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ofLong(3), SemanticRational.ofLong(3))),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                SemanticRational.of(BIG_ONE, bigIntOf(10)), ObjectiveSense.MAXIMIZE,
            ),
        )
        val common = CommonOptions().apply {
            this.engine = this@LinearSourceSolvingTest.engine
            parallel = 1
            randomSeed = 17L
            engineParams += "node-limit=32"
        }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            "n=${it.values.single()}"
        })
        val points = mutableListOf<Pair<String, BigFraction>>()
        var completed: Verdict? = null
        var retained: SourceExecutionResult? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                points += rendered to assertIs<SourceValue.Rational>(objective).value
                return true
            }
            override fun onLinearSourceResult(result: SourceExecutionResult) {
                retained = result
            }
            override fun onComplete(verdict: Verdict) {
                completed = verdict
            }
        }

        SolveCore.solve(solvable, common, output)

        assertEquals(listOf("n=3" to (BigFraction.ofLong(11) * BigFraction.ofLong(10).reciprocal())), points)
        assertTrue(completed == Verdict.OPTIMAL || completed == Verdict.BEST_FOUND)
        assertSame(source, retained?.linearSource?.source)
        assertSame(solvable.sourceInvocation, retained?.invocation)
    }
}
