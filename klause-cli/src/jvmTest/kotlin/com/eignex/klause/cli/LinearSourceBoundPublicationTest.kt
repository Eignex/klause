package com.eignex.klause.cli

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

@RunWith(Parameterized::class)
class LinearSourceBoundPublicationTest(private val sense: ObjectiveSense) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeSolver() {
            LinearSourceBoundPublicationTest(ObjectiveSense.MINIMIZE)
                .`finite output should publish a nominal bound in original objective units`()
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun senses(): List<ObjectiveSense> = ObjectiveSense.entries
    }

    @Test
    fun `an absolute envelope should publish its accepted set bound without a second tolerance band`() {
        val slack = BigFraction.ofLong(10_000_000).reciprocal()
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE)), sense = sense),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(slack.num, slack.den))).resolve(),
        )
        val common = CommonOptions().apply {
            engine = "mixed"
            parallel = 1
            statistics = true
            presolve = "off"
            sourceOptions.linearToleranceEnvelope = true
        }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, { "point" })
        val pairs = mutableMapOf<String, String>()
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onAllowanceStatistics(values: List<Pair<String, String>>) {
                pairs.putAll(values)
            }
        }

        SolveCore.solve(solvable, common, output)

        val expected = if (sense == ObjectiveSense.MINIMIZE) BigFraction.ONE - slack
        else BigFraction.ofLong(2) + slack
        assertEquals(expected.toString(), pairs["nativeAcceptedObjectiveBound"])
        assertEquals("absolute-tolerance-envelope", pairs["nativeAcceptedObjectiveBoundScope"])
        assertEquals("true", pairs["nativeAcceptedObjectiveBoundCoversTolerance"])
        assertFalse("nativeNominalObjectiveBound" in pairs)
    }

    @Test
    fun `finite output should publish a nominal bound in original objective units`() {
        val third = SemanticRational.of(BIG_ONE, bigIntOf(3))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("r", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(1, SemanticRational.ONE)),
                lower = SemanticRational.of(BIG_ONE, bigIntOf(2)))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, third), LinearSourceTerm(1, third)),
                SemanticRational.of(BIG_ONE, bigIntOf(10)), sense),
        )
        val common = CommonOptions().apply {
            engine = "mixed"
            parallel = 1
            statistics = true
            presolve = "off"
        }
        val solvable = linearSourceSolvable(SourceAttachment(source, ProblemSettings()), common, { "point" })
        val pairs = mutableMapOf<String, String>()
        var result: SourceExecutionResult? = null
        val output = object : OutputProtocol by SmtLibOutput() {
            override fun onLinearSourceResult(value: SourceExecutionResult) {
                result = value
            }
            override fun onAllowanceStatistics(values: List<Pair<String, String>>) {
                pairs.putAll(values)
            }
        }

        SolveCore.solve(solvable, common, output)

        assertIs<SourceExecutionResult.Finite>(result)
        assertEquals(if (sense == ObjectiveSense.MINIMIZE) "4/15" else "23/30",
            pairs["nativeNominalObjectiveBound"])
        assertEquals(if (sense == ObjectiveSense.MINIMIZE) "lower" else "upper",
            pairs["nativeNominalObjectiveBoundDirection"])
        assertEquals("false", pairs["nativeNominalObjectiveBoundStrict"])
        assertEquals("nominal-source", pairs["nativeNominalObjectiveBoundScope"])
        assertEquals("true", pairs["nativeNominalObjectiveBoundCoversTolerance"])
    }
}
