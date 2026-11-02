package com.eignex.klause.bench.profile

import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.util.Cancellation

internal object ArithmeticLearningSourcePreparation {
    class Prepared(
        val original: Problem,
        val result: FinitePipelinePreparation,
        val maximize: Boolean,
        val declaredVariables: Int,
        val declaredIntegers: Int,
    )

    fun prepare(bytes: ByteArray, cancellation: Cancellation, recipe: String): Prepared? {
        require(bytes.size <= 1_048_576 && recipe in setOf("automatic", "lp-default"))
        if (cancellation()) return null
        val parsed = Mps.parse(bytes.decodeToString())
        require(parsed.variables.size <= 4096) { "declared source column limit" }
        val source = parsed.toLinearSourceProblem(cancellation = cancellation) ?: return null
        val maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()),
            if (source.objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
            FiniteEngine.BACKTRACK, maximize = maximize, cancellation = cancellation,
        ))
        if (cancellation() || SourceSetup.executionKind(invocation) != SourceExecutionKind.FINITE) return null
        val options = ExecutionOptions.parse(buildList {
            add("dry-run-presolve=true")
            if (recipe == "lp-default") addAll(listOf("bt-arm=lp-default", "arms=1", "pb-learning=false"))
        })
        val policy = SourceExecutionPolicy()
        var original: Problem? = null
        val execution = SourceSetup.execute(SourceExecutionRequest(
            invocation, policy, options, cores = 1, randomSeed = 1L,
            timeout = cancellation, cancellation = cancellation, presolveCancellation = cancellation,
            finiteRequest = { shape ->
                val model = shape.finiteProblem
                require(model.numIntVars + model.numBoolVars + model.numRealVars <= 4096) {
                    "working source column limit"
                }
                original = model
                policy.finiteRequest(shape, FiniteEngine.BACKTRACK, cancellation = cancellation,
                    executionOptions = options, randomSeed = 1L, prepareOnly = true,
                    onEvent = { error("source-only inspection cannot construct search workers") },
                    onPortfolioEvent = { _, _ -> error("source-only inspection cannot construct search workers") })
            },
        )) as? SourceExecutionResult.Finite ?: return null
        if (cancellation() || execution.result.outcome != FiniteSolveOutcome.PreparedOnly) return null
        return Prepared(requireNotNull(original), execution.result.preparation, maximize,
            source.variables.size, source.variables.count { it.integer })
    }
}
