package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SchedulingCancellation
import com.eignex.klause.util.workScheduled
import kotlin.uuid.Uuid

internal class BooleanProofPortfolio(
    private val problem: BakedProblem,
    private val context: BooleanProofContext,
    splitVariables: List<Int>,
    parameters: List<BacktrackParams>,
    private val reservations: List<(Cancellation, () -> ResumableSolve) -> ResumableSolve>? = null,
) : AutoCloseable {
    private val openingTokens = Array(parameters.size) { Cancellation.Never }
    private val scheduler = BooleanProofScheduler(problem, context, splitVariables,
        parameters.mapIndexed { worker, params ->
            params.withCancellation(object : SchedulingCancellation {
                override val workScheduled: Boolean get() = openingTokens[worker].workScheduled()
                override fun isCancelled(): Boolean = openingTokens[worker]()
                override fun deadline() = openingTokens[worker].deadline()
                override fun workMeter() = openingTokens[worker].workMeter()
            })
        }, true)
    private val handles = arrayOfNulls<Lane>(parameters.size)
    private var closed = false
    val size: Int get() = scheduler.size
    val completed: Int get() = scheduler.completed
    val work: Long get() = scheduler.work

    fun available(worker: Int): Boolean = !closed && handles[worker]?.closed != true && scheduler.hasTask(worker)

    fun open(worker: Int, cancellation: Cancellation): ResumableSolve {
        check(!closed && handles[worker] == null)
        openingTokens[worker] = cancellation
        val open = { Lane(worker).also { handles[worker] = it } }
        return reservations?.get(worker)?.invoke(cancellation, open) ?: open()
    }

    private inner class Lane(private val worker: Int) : ResumableSolve {
        var closed = false
            private set
        private var done: SolveResult? = null
        override val isDone: Boolean get() = done != null
        override val work: Long get() = scheduler.workerWork(worker)
        override val stats: SolveStats get() = scheduler.workerStats(worker)

        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
            done?.let { return it }
            check(!closed && !this@BooleanProofPortfolio.closed) { "search is closed" }
            if (global()) return finish(SolveResult.Unknown(TerminationReason.Cancelled, semantics = problem.semantics))
            if (sliceMillis <= 0L || sliceNodes == 0L || !available(worker)) return null
            val step = scheduler.runSlice(worker, global, sliceMillis, if (sliceNodes < 0L) 5000L else sliceNodes)
            step.sample?.let { return finish(SolveResult.Sat(it, semantics = problem.semantics)) }
            if (scheduler.nativeCoverageComplete) return finish(SolveResult.Unsat(
                assumptionCore = Assumptions(bools = context.scope.condition.bools), semantics = problem.semantics,
            ))
            if (step.reason != null && step.reason != TerminationReason.SearchExhausted) return finish(
                SolveResult.Unknown(step.reason, semantics = problem.semantics),
            )
            return null
        }

        private fun finish(result: SolveResult): SolveResult {
            close()
            return when (result) {
                is SolveResult.Sat -> result.copy(stats = stats)
                is SolveResult.Unsat -> result.copy(stats = stats)
                is SolveResult.Unknown -> result.copy(stats = stats)
            }.also { done = it }
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!this@BooleanProofPortfolio.closed) scheduler.loseWorker(worker)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scheduler.close()
    }

    companion object {
        fun forWorkers(workers: List<PortfolioWorker>): BooleanProofPortfolio? {
            if (workers.size != 2 || workers.any { !it.permitsSourceProofs || it.improvementOnly ||
                    it.auxiliaryProof || it.startAfterIncumbent || it.firstWitnessOnly }) return null
            val recipes = workers.map { it.booleanProofRecipe ?: return null }
            val problem = recipes.first().problem
            if (recipes.any { it.problem !== problem || !it.deterministicClauseQuery() }) return null
            if (problem.numBoolVars !in 2..4096 || problem.numIntVars != 0 || problem.numRealVars != 0 ||
                problem.hasSymmetryBreaking || problem.rootDeductions is PropagationResult.Unsat ||
                problem.factors.any { it !is Clause }) return null
            val invocation = workers.first().evidenceInvocation ?: return null
            if (workers.any { worker ->
                    worker.evidenceInvocation?.let(invocation::matches) != true ||
                        worker.evidenceModel?.matches(ModelIdentity.of(problem)) != true
                }) return null
            val plan = invocation.preparedPlan
            if (invocation.hasSourceObjective || invocation.executionObjective != null ||
                invocation.hasUncapturedSourceRestrictions || plan.model !== problem ||
                plan.sourceBoolCount != problem.numBoolVars || plan.sourceIntCount != 0 || plan.sourceRealCount != 0
            ) return null
            val condition = invocation.executionCondition
            if (condition != invocation.sourceCondition || condition.lower.isNotEmpty() ||
                condition.upper.isNotEmpty() || condition.integerRows.isNotEmpty()) return null
            val params = recipes.map { recipe ->
                if (recipe.params.assumptions.numInts != 0 || !recipe.params.assumptions.deductions.isEmpty ||
                    recipe.params.assumptions.bools != condition.bools) return null
                recipe.conditioned() ?: return null
            }
            val splits = (0 until problem.numBoolVars).filter { it !in condition.bools }.take(2)
            if (splits.size != 2) return null
            // Runtime bindings own source identity; fresh board ids prevent cross-invocation leases.
            val identity = Uuid.random().toString()
            val scope = invocation.scope?.copy(condition = condition) ?: SourceScope(
                SourceRevision(identity, "klause-ir-clauses-v1"), condition,
            )
            return BooleanProofPortfolio(problem, BooleanProofContext(scope, identity + "/identity-bool-v1"),
                splits, params, recipes.map { it.reserve })
        }
    }
}
