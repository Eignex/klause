package com.eignex.klause.solver.pipeline

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
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.solver.validation.checkLinearSourceWitness
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IndependentLinearSourceTest {
    @Test
    fun `an unprojectable row should retain an independent exact refutation`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        for (sense in ObjectiveSense.entries) {
            val source = LinearSourceProblem(
                listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE),
                    LinearSourceVariable("y", false, SemanticRational.ZERO, SemanticRational.ONE)),
                listOf(
                    LinearSourceRow(listOf(LinearSourceTerm(0, tiny)),
                        lower = SemanticRational.of(bigIntOf(-1), bigIntOf(10))),
                    LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                        lower = SemanticRational.ofLong(2)),
                ),
                LinearSourceObjective(listOf(LinearSourceTerm(1, SemanticRational.ONE)), sense = sense),
                SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance()).resolve(),
            )
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED, maximize = sense == ObjectiveSense.MAXIMIZE))

            val result = SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { error("an unprojectable source cannot construct a finite consumer") }))

            val outcome = assertNotNull(result.linearSource)
            assertEquals(LinearSourceConclusion.UNSATISFIABLE, outcome.conclusion)
            assertNull(outcome.incumbent)
        }
    }

    @Test
    fun `a satisfied component should leave an unprojectable source undecided`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(0, tiny)),
                lower = SemanticRational.of(bigIntOf(-1), bigIntOf(10)))),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance()).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.MIXED))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation))

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.UNKNOWN, outcome.conclusion)
        assertNull(outcome.incumbent)
    }

    @Test
    fun `a coupled unprojectable row should keep its component unavailable`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(
                LinearSourceRow(listOf(LinearSourceTerm(0, tiny), LinearSourceTerm(1, SemanticRational.ONE)),
                    lower = SemanticRational.of(bigIntOf(-1), bigIntOf(10))),
                LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE)), lower = SemanticRational.ofLong(2)),
            ),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance()).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.MIXED))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation))

        assertEquals(TerminationReason.Unsupported, assertIs<SourceExecutionResult.Declined>(result).reason)
        assertEquals(LinearSourceConclusion.UNKNOWN, assertNotNull(result.linearSource).conclusion)
    }

    @Test
    fun `positive tolerance should withhold a nominal independent refutation`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(
                LinearSourceRow(listOf(LinearSourceTerm(0, tiny)),
                    lower = SemanticRational.of(bigIntOf(-1), bigIntOf(10))),
                LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                    lower = SemanticRational.of(bigIntOf(100_000_001), bigIntOf(100_000_000))),
            ),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance(
                SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)))).resolve(),
        )
        assertIs<LinearSourceCheck.Accepted>(checkLinearSourceWitness(source,
            listOf(BigFraction.ZERO, BigFraction.ONE), ExecutionControl.Never))
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.MIXED))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation))

        assertEquals(TerminationReason.Unsupported, assertIs<SourceExecutionResult.Declined>(result).reason)
        assertEquals(LinearSourceConclusion.UNKNOWN, assertNotNull(result.linearSource).conclusion)
    }

    @Test
    fun `cancellation should withhold an independent linear refutation`() {
        val tiny = SemanticRational.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE),
                LinearSourceVariable("y", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(
                LinearSourceRow(listOf(LinearSourceTerm(0, tiny)),
                    lower = SemanticRational.of(bigIntOf(-1), bigIntOf(10))),
                LinearSourceRow(listOf(LinearSourceTerm(1, SemanticRational.ONE)), lower = SemanticRational.ofLong(2)),
            ),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = SourceTolerance()).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.MIXED))

        val result = SourceSetup.execute(SourceExecutionRequest(invocation,
            theory = TheoryParams(cancellation = ExecutionControl { true })))

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.UNKNOWN, outcome.conclusion)
        assertNull(outcome.incumbent)
        val execution = assertIs<SourceExecutionResult.Linear>(result).result
        val verdict = assertIs<OpenTheoryResult.Unknown>(assertIs<OpenTheoryExecution.Satisfy>(execution).result)
        assertEquals(TerminationReason.Cancelled, verdict.reason)
    }
}
