package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.smtlib.SmtSourceCheck
import com.eignex.klause.formats.smtlib.SmtValue
import com.eignex.klause.formats.smtlib.withSourceWitnessValidation
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceWitnessValidationTest {
    @Test
    fun `source checking should charge the solve accounting owner`() {
        for (text in listOf("(declare-const b Bool) (assert b)",
            "(declare-const x Real) (assert (> x 0.0))")) {
            var charged = 0L
            var checked = 0L
            val control = ExecutionControl(accounting = WorkMeter { charged += it })
            val source = SmtLib.parse(text).source.withWitnessValidator { _, cancellation ->
                val before = charged
                cancellation.charge(7L)
                checked = charged - before
                SourceEvidenceCheck.Checked
            }
            val invocation = SourceSetup.prepare(SourceSetupRequest(source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

            SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { FiniteSolveRequest(it,
                    SourceFiniteControls.Backtrack(BacktrackParams(cancellation = control))) },
                theory = TheoryParams(cancellation = control), presolveConfig = PresolveConfig.NONE))

            assertEquals(7L, checked)
        }
    }

    @Test
    fun `rejecting source checks should withhold finite open and native witnesses`() {
        for (text in listOf("(declare-const b Bool) (assert b)",
            "(declare-const x Real) (assert (= x 1.0))",
            "(declare-const x Real) (assert (= (^ x 3) 1.0))")) {
            val parsed = SmtLib.parse(text).copy(sourceChecks = listOf(SmtSourceCheck { false }))
                .withSourceWitnessValidation()
            val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
            var publications = 0

            val result = SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
                presolveConfig = PresolveConfig.NONE), SourceExecutionCallbacks(
                finite = FiniteSolveCallbacks({ publications++ }, { publications++ }),
                onLinearIncumbent = { publications++ }, onQuadraticIncumbent = { publications++ }))

            if (result is SourceExecutionResult.Finite) {
                val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
                assertEquals(FiniteSolveVerdict.UNKNOWN, outcome.verdict)
                assertNull(outcome.bestSample)
                assertNull(result.result.nativeSourceResult)
                assertNull(result.result.nativeSourceMinimum)
            } else when (result) {
                is SourceExecutionResult.Linear -> assertIs<OpenTheoryResult.Unknown>(
                    assertIs<OpenTheoryExecution.Satisfy>(result.result).result)
                is SourceExecutionResult.Polynomial -> assertIs<OpenTheoryResult.Unknown>(result.result.result)
                else -> error("unexpected source result")
            }
            assertEquals(0, publications)
        }
    }

    @Test
    fun `interrupted source checks should withhold finite open and native witnesses`() {
        for (text in listOf("(declare-const b Bool) (assert b)",
            "(declare-const x Real) (assert (= x 1.0))",
            "(declare-const x Real) (assert (= (^ x 3) 1.0))")) {
            val parsed = SmtLib.parse(text).copy(sourceChecks = listOf(SmtSourceCheck {
                throw CancellationException("check interrupted")
            })).withSourceWitnessValidation()
            val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
            var publications = 0

            val result = SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
                presolveConfig = PresolveConfig.NONE), SourceExecutionCallbacks(
                finite = FiniteSolveCallbacks({ publications++ }, { publications++ })))

            if (result is SourceExecutionResult.Finite) {
                val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
                assertEquals(FiniteSolveVerdict.UNKNOWN, outcome.verdict)
            } else {
                val verdict = when (result) {
                    is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(result.result).result
                    is SourceExecutionResult.Polynomial -> result.result.result
                    else -> error("unexpected source result")
                }
                assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(verdict).reason)
            }
            assertEquals(0, publications)
        }
    }

    @Test
    fun `finite shape execution should enforce attachment checks without the source facade`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert b)")
        val attachment = parsed.source.withWitnessValidator { _, _ -> SourceEvidenceCheck.Rejected("invalid source") }
        val invocation = SourceSetup.prepare(SourceSetupRequest(attachment,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        var publications = 0

        val result = FinitePipeline.solve(FiniteSolveRequest(invocation.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams())),
            FiniteSolveCallbacks({ publications++ }, { publications++ }))

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
        assertEquals(0, publications)
    }

    @Test
    fun `rejected attachment checks should not contaminate another attachment over the same source`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert b)")
        val rejected = parsed.source.withWitnessValidator { _, _ -> SourceEvidenceCheck.Rejected("invalid source") }
        val accepted = parsed.source.withWitnessValidator { _, _ -> SourceEvidenceCheck.Checked }
        val first = SourceSetup.prepare(SourceSetupRequest(rejected,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))
        val second = SourceSetup.prepare(SourceSetupRequest(accepted,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val refused = FinitePipeline.solve(FiniteSolveRequest(first.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams())), FiniteSolveCallbacks({}, {}))
        val allowed = FinitePipeline.solve(FiniteSolveRequest(second.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams())), FiniteSolveCallbacks({}, {}))

        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(refused.outcome).verdict)
        assertEquals(FiniteSolveVerdict.SAT, assertIs<FiniteSolveOutcome.Completed>(allowed.outcome).verdict)
    }

    @Test
    fun `source checking should poll the solve allowance instead of the admission allowance`() {
        val parsed = SmtLib.parse("(declare-const b Bool) (assert b)")
        var stopped = false
        val checked = parsed.copy(sourceChecks = listOf(SmtSourceCheck { values ->
            stopped = true
            values.checkpoint()
            true
        })).withSourceWitnessValidation()
        val invocation = SourceSetup.prepare(SourceSetupRequest(checked.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val result = FinitePipeline.solve(FiniteSolveRequest(invocation.finiteShape(),
            SourceFiniteControls.Backtrack(BacktrackParams(cancellation = ExecutionControl { stopped }))),
            FiniteSolveCallbacks({}, {}))

        assertTrue(stopped)
        assertEquals(FiniteSolveVerdict.UNKNOWN, assertIs<FiniteSolveOutcome.Completed>(result.outcome).verdict)
    }

    @Test
    fun `rejecting source checks should withhold finite open and native optimum claims`() {
        for (text in listOf("(declare-const b Bool) (assert b)",
            "(declare-const x Real) (assert (>= x 0.0))",
            "(declare-const x Real) (assert (= (^ x 3) 1.0))")) {
            val parsed = SmtLib.parse(text).copy(sourceChecks = listOf(SmtSourceCheck { false }))
                .withSourceWitnessValidation()
            val objective = LinearObjective(boolWeights = LongArray(parsed.boolVarNames.size) { 1L },
                realCoefficients = DoubleArray(parsed.realVarNames.size) { 1.0 })
            val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
                ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK, objective))
            var publications = 0

            val result = SourceSetup.execute(SourceExecutionRequest(invocation,
                finiteRequest = { FiniteSolveRequest(it, SourceFiniteControls.Backtrack(BacktrackParams())) },
                presolveConfig = PresolveConfig.NONE), SourceExecutionCallbacks(
                finite = FiniteSolveCallbacks({ publications++ }, { publications++ }),
                onLinearIncumbent = { publications++ }, onQuadraticIncumbent = { publications++ }))

            if (result is SourceExecutionResult.Finite) {
                val outcome = assertIs<FiniteSolveOutcome.Completed>(result.result.outcome)
                assertEquals(FiniteSolveVerdict.UNKNOWN, outcome.verdict)
                assertNull(result.result.nativeSourceMinimum)
            } else when (result) {
                is SourceExecutionResult.Linear -> {
                    val optimum = assertIs<OpenTheoryOptimum.Bounded>(
                        assertIs<OpenTheoryExecution.Optimize>(result.result).result)
                    assertNull(optimum.incumbent)
                    assertNull(optimum.linearResult)
                }
                is SourceExecutionResult.Quadratic ->
                    assertIs<QuadraticOptimizationResult.Unknown>(result.result.result)
                else -> error("unexpected source result")
            }
            assertEquals(0, publications)
        }
    }

    @Test
    fun `source checks and reconstruction should read identical certified real terms`() {
        val cases = listOf(
            "(assert (= x (/ 1.0 2.0)))" to "(/ 1.0 2.0)",
            "(assert (> x 0.0)) (assert (= (* x x) 2.0))" to "(root-obj ",
            "(assert (= x (log 2.0)))" to "((_ klause-real log) ",
        )
        for ((assertions, prefix) in cases) {
            val observed = mutableListOf<String>()
            val parsed = SmtLib.parse("(declare-const x Real) $assertions")
                .copy(sourceChecks = listOf(SmtSourceCheck { values ->
                    val term = values.real("x")
                    observed += term
                    term.startsWith(prefix) && values.value("x") == SmtValue.Real(term)
                })).withSourceWitnessValidation()
            val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source,
                ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

            val result = SourceSetup.execute(SourceExecutionRequest(invocation, presolveConfig = PresolveConfig.NONE))

            val verdict = when (result) {
                is SourceExecutionResult.Linear -> assertIs<OpenTheoryExecution.Satisfy>(result.result).result
                is SourceExecutionResult.Polynomial -> result.result.result
                is SourceExecutionResult.Transcendental -> result.result.result
                else -> error("unexpected source result")
            }
            val witness = assertIs<OpenTheoryResult.Sat>(verdict).assignment
            assertTrue(observed.isNotEmpty())
            val model = parsed.reconstructModel(witness)
            assertEquals(SmtValue.Real(observed.last()), model.values.single().second)
        }
    }

    @Test
    fun `quadratic incumbent checks should retain algebraic sample coordinates`() {
        val observed = mutableListOf<String>()
        val parsed = SmtLib.parse("(declare-const x Real) (assert (> x 0.0)) " +
            "(assert (= (* x x) 2.0)) (minimize (* x x))")
            .copy(sourceChecks = listOf(SmtSourceCheck { values ->
                val term = values.real("x")
                observed += term
                term.startsWith("(root-obj ")
            })).withSourceWitnessValidation()
        val invocation = SourceSetup.prepare(SourceSetupRequest(parsed.source, FiniteEngine.BACKTRACK,
            requireNotNull(parsed.quadraticObjective)))
        var publications = 0

        val result = assertIs<SourceExecutionResult.Quadratic>(SourceSetup.execute(SourceExecutionRequest(invocation,
            presolveConfig = PresolveConfig.NONE), SourceExecutionCallbacks(onQuadraticIncumbent = { publications++ })))

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result.result.result)
        assertTrue(publications > 0)
        val model = parsed.reconstructModel(optimum.incumbent.sample)
        assertEquals(SmtValue.Real(observed.last()), model.values.single().second)
    }
}
