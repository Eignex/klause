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
import com.eignex.klause.solver.result.SolveStats
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LinearSourceAttributionTest(
    private val maximize: Boolean,
    private val statistics: Boolean,
    private val accepted: Boolean,
    private val parallel: Int,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "maximize={0}, statistics={1}, accepted={2}, parallel={3}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf(false, true, true, 1),
            arrayOf(true, true, true, 1),
            arrayOf(false, false, true, 1),
            arrayOf(false, true, false, 1),
            arrayOf(false, true, true, 2),
            arrayOf(true, true, true, 2),
        )
    }

    @Test
    fun `finite source attribution should follow accepted publication in original units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                SemanticRational.of(BIG_ONE, bigIntOf(10)),
                if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
            ),
        )
        val common = CommonOptions().apply {
            engine = "cp"
            this.parallel = this@LinearSourceAttributionTest.parallel
            this.statistics = this@LinearSourceAttributionTest.statistics
            randomSeed = 17L
            presolve = "off"
            lp = "off"
            engineParams += listOf("arms=$parallel", "node-limit=32")
        }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, {
            "n=${it.values.single()}"
        })
        val points = mutableListOf<BigFraction>()
        val attributed = mutableListOf<BigFraction>()
        var retained: SourceExecutionResult? = null
        val output = object : OutputProtocol by MpsOutput() {
            override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
                points += assertIs<SourceValue.Rational>(objective).value
                return accepted
            }

            override fun onImprovement(arm: String, objective: BigFraction, elapsedMs: Long) {
                assertTrue(arm.isNotBlank())
                assertTrue(elapsedMs >= 0L)
                assertEquals(points.last(), objective)
                attributed += objective
            }

            override fun onLinearSourceResult(result: SourceExecutionResult) { retained = result }
            override fun onComplete(verdict: Verdict) = Unit
            override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) = Unit
        }

        SolveCore.solve(solvable, common, output)

        assertIs<SourceExecutionResult.Finite>(retained)
        assertTrue(points.isNotEmpty())
        assertEquals(if (statistics && accepted) points.distinct() else emptyList(), attributed)
        assertTrue(points.all { it == BigFraction.ofLong(13) * BigFraction.ofLong(30).reciprocal() ||
            it == BigFraction.ofLong(23) * BigFraction.ofLong(30).reciprocal() })
    }
}
