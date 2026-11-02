package com.eignex.klause.cli

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.FinitePipelinePreparation
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.executionKind

/** Source-only inspection through the JVM CLI's loading, routing and finite preparation controls. */
object CliSourceInspection {
    /** A completed preparation without a search worker or source verdict. */
    class Prepared internal constructor(
        /** Finite source model before presolve. */
        val original: Problem,
        /** Actual prepared model and reconstruction from the CLI invocation. */
        val result: FinitePipelinePreparation,
        /** Original source objective direction. */
        val maximize: Boolean,
        /** Number of declared source columns. */
        val declaredVariables: Int,
        /** Number of declared integer source columns. */
        val declaredIntegers: Int,
        /** Shared routing and presolve work allowance, absent when explicitly unlimited. */
        val presolveWorkAllowance: Long?,
        /** Work charged to that shared allowance, absent when explicitly unlimited. */
        val presolveWorkSpent: Long?,
    )

    /**
     * Prepare an MPS source under process CLI configuration and the named finite [recipe].
     * [timeLimitMs] determines the ordinary presolve share; [remainingMs] retains the caller's
     * containing deadline after earlier input work. Only completed finite preparations are returned.
     */
    fun prepareMps(path: String, timeLimitMs: Long, remainingMs: Long, recipe: String = "automatic"): Prepared? {
        require(timeLimitMs > 0L && remainingMs in 0..timeLimitMs)
        require(recipe in setOf("automatic", "lp-default"))
        if (remainingMs == 0L) return null
        val deadline = nowMillis() + remainingMs
        val common = CommonOptions().apply {
            config = installCliConfig()
            engine = "cp"
            this.timeLimitMs = timeLimitMs
            deadlineAtMs = deadline
            parallel = 1
            randomSeed = 1L
            engineParams.add("dry-run-presolve=true")
            if (recipe == "lp-default") engineParams.addAll(listOf("bt-arm=lp-default", "arms=1", "pb-learning=false"))
        }
        val session = MpsMode.newSession()
        val loaded = session.load(path, common)
        val rendering = loaded.rendering as? SourceRendering.LinearSource ?: return null
        if (nowMillis() >= deadline || SourceSetup.executionKind(rendering.invocation) != SourceExecutionKind.FINITE) {
            return null
        }
        val source = rendering.invocation.source.originalSource as LinearSourceProblem
        var captured: Prepared? = null
        val observer = SourcePreparationObserver(
            before = { model ->
                require(model.numIntVars + model.numBoolVars + model.numRealVars <= 4096) {
                    "working source column limit"
                }
            },
            after = { original, preparation ->
                check(captured == null)
                captured = Prepared(original, preparation, loaded.maximize, source.variables.size,
                    source.variables.count { it.integer }, common.presolveBudget?.allowance,
                    common.presolveBudget?.spent())
            },
        )
        SolveCore.solve(loaded, common, session.output(common).sourceQualified(common.sourceSemantics, "c"), observer)
        return captured.takeIf { nowMillis() < deadline }
    }
}
