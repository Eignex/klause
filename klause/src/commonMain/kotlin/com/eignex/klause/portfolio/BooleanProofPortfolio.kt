package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.BooleanSatisfactionRepair
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SchedulingCancellation
import com.eignex.klause.util.workScheduled
import kotlin.uuid.Uuid

@Suppress("TooGenericExceptionCaught") // preserve primary failures while releasing retained worker state
internal class BooleanProofPortfolio(
    private val problem: BakedProblem,
    private val context: BooleanProofContext,
    splitVariables: List<Int>,
    parameters: List<BacktrackParams>,
    private val reservations: List<(Cancellation, () -> ResumableSolve) -> ResumableSolve>? = null,
    private val probeFirst: Boolean = false,
    private val solvers: List<BacktrackSolver>? = null,
) : AutoCloseable {
    private val openingTokens = Array(parameters.size) { Cancellation.Never }
    private val parameters = parameters.mapIndexed { worker, params ->
        params.withCancellation(object : SchedulingCancellation {
            override val workScheduled: Boolean get() = openingTokens[worker].workScheduled()
            override fun isCancelled(): Boolean = openingTokens[worker]()
            override fun deadline() = openingTokens[worker].deadline()
            override fun workMeter() = openingTokens[worker].workMeter()
        })
    }
    private var scheduler: BooleanProofScheduler? = if (probeFirst) null else board(splitVariables)
    private val probes = arrayOfNulls<BooleanSatisfactionRepair>(parameters.size)
    private val retiredWork = LongArray(parameters.size)
    private val retiredStats = Array(parameters.size) { SolveStats.EMPTY }
    private var probeOwner: Int? = null
    private var terminal = false
    var probeWork: Long = 0L
        private set
    var probeSegments: Int = 0
        private set
    var partitionLiteral: Int? = null
        private set
    private val handles = arrayOfNulls<Lane>(parameters.size)
    private var closed = false
    val size: Int get() = scheduler?.size ?: 0
    val completed: Int get() = scheduler?.completed ?: 0
    val work: Long get() = this.parameters.indices.sumOf(::workerWork)

    init {
        require(parameters.size in 1..64)
        require(solvers == null || (solvers.size == parameters.size && solvers.all { it.problem === problem }))
        require(reservations == null || reservations.size == parameters.size)
        require(context.scope.objective == null && context.cutoff == null)
        val condition = context.scope.condition
        require(condition.lower.isEmpty() && condition.upper.isEmpty() && condition.integerRows.isEmpty())
        require(condition.bools.keys.all { it in 0 until problem.numBoolVars })
        require(parameters.all { it.assumptions.numInts == 0 && it.assumptions.deductions.isEmpty &&
            it.assumptions.bools == condition.bools })
    }

    private fun board(splits: List<Int>) =
        BooleanProofScheduler(problem, context, splits, parameters, true, solvers)

    private fun workerWork(worker: Int): Long = retiredWork[worker] +
        (scheduler?.workerWork(worker) ?: probes[worker]?.work ?: 0L)

    private fun workerStats(worker: Int): SolveStats = retiredStats[worker].mergedWith(
        scheduler?.workerStats(worker) ?: probes[worker]?.stats ?: SolveStats.EMPTY,
    )

    fun available(worker: Int): Boolean = !closed && !terminal && handles[worker]?.closed != true &&
        (scheduler?.hasTask(worker) ?: (probeOwner == null || probeOwner == worker))

    fun open(worker: Int, cancellation: Cancellation): ResumableSolve {
        check(!closed && handles[worker] == null)
        openingTokens[worker] = cancellation
        val open = { Lane(worker).also { handles[worker] = it } }
        return reservations?.get(worker)?.invoke(cancellation, open) ?: open()
    }

    private fun retireProbe(worker: Int) {
        val search = probes[worker] ?: return
        probes[worker] = null
        try {
            search.close()
        } finally {
            retiredWork[worker] += search.work
            retiredStats[worker] = retiredStats[worker].mergedWith(search.stats)
        }
    }

    private inner class Lane(private val worker: Int) : ResumableSolve {
        var closed = false
            private set
        private var done: SolveResult? = null
        override val isDone: Boolean get() = done != null
        override val work: Long get() = workerWork(worker)
        override val stats: SolveStats get() = workerStats(worker)

        override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
            done?.let { return it }
            check(!closed && !this@BooleanProofPortfolio.closed) { "search is closed" }
            if (global()) return finish(SolveResult.Unknown(TerminationReason.Cancelled, semantics = problem.semantics))
            if (sliceMillis <= 0L || sliceNodes == 0L || !available(worker)) return null
            val board = scheduler ?: return probe(global, sliceMillis, if (sliceNodes < 0L) 5000L else sliceNodes)
            val step = board.runSlice(worker, global, sliceMillis, if (sliceNodes < 0L) 5000L else sliceNodes)
            step.sample?.let { return finish(SolveResult.Sat(it, semantics = problem.semantics)) }
            if (board.nativeCoverageComplete) return finish(SolveResult.Unsat(
                assumptionCore = Assumptions(bools = context.scope.condition.bools), semantics = problem.semantics,
            ))
            if (step.reason != null && step.reason != TerminationReason.SearchExhausted) return finish(
                SolveResult.Unknown(step.reason, semantics = problem.semantics),
            )
            return null
        }

        private fun probe(global: Cancellation, millis: Long, work: Long): SolveResult? {
            val before = workerWork(worker)
            probeSegments++
            return try {
                probeStep(global, millis, work)
            } finally {
                probeWork += (workerWork(worker) - before).coerceAtLeast(0L)
            }
        }

        private fun probeStep(global: Cancellation, millis: Long, work: Long): SolveResult? {
            if (probeOwner == null) probeOwner = worker
            val search = probes[worker] ?: try {
                val params = parameters[worker].copy(assumptions = Assumptions.None)
                (solvers?.get(worker) ?: BacktrackSolver(problem)).openBooleanProof(params).also {
                    probes[worker] = it
                    it.begin(Assumptions(bools = context.scope.condition.bools), params.maxDecisions,
                        Double.POSITIVE_INFINITY, global)
                }
            } catch (failure: SearchInitializationCancelled) {
                if (probes[worker] == null) {
                    retiredWork[worker] += failure.work
                    retiredStats[worker] = retiredStats[worker].mergedWith(failure.stats)
                }
                throw failure
            }
            val step = search.runSlice(global, millis, work)
            step.sample?.let { sample ->
                val verification = finiteWitnessVerifier(problem, null,
                    Assumptions(bools = context.scope.condition.bools), global).verify(Candidate(sample, null))
                if (verification is Verification.Accepted) return finish(
                    SolveResult.Sat(sample, semantics = problem.semantics),
                )
                check(verification !is Verification.Rejected) { "source probe produced an invalid witness" }
                return finish(SolveResult.Unknown(
                    if (global()) TerminationReason.Cancelled else TerminationReason.BudgetExhausted,
                    semantics = problem.semantics,
                ))
            }
            if (step.reason != null) return finish(
                if (step.reason == TerminationReason.SearchExhausted) SolveResult.Unsat(
                    assumptionCore = Assumptions(bools = context.scope.condition.bools), semantics = problem.semantics,
                ) else SolveResult.Unknown(step.reason, semantics = problem.semantics),
            )
            if (!global()) search.splitLiteral()?.let { literal ->
                val replacement = board(listOf(Lit.variable(literal)))
                try {
                    replacement.adopt(worker, search, literal)
                } catch (failure: Throwable) {
                    runCatching { replacement.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
                scheduler = replacement
                partitionLiteral = literal
                probes[worker] = null
                probeOwner = null
            }
            return null
        }

        private fun finish(result: SolveResult): SolveResult {
            if (result is SolveResult.Sat || result is SolveResult.Unsat) terminal = true
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
            if (!this@BooleanProofPortfolio.closed) {
                scheduler?.loseWorker(worker)
                retireProbe(worker)
                if (probeOwner == worker) probeOwner = null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val results = parameters.indices.map { worker -> runCatching { retireProbe(worker) } } +
            runCatching { scheduler?.close() }
        val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null) {
            results.mapNotNull { it.exceptionOrNull() }.filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        fun forWorkers(
            workers: List<PortfolioWorker>,
            probeFirst: Boolean = false,
            declined: (String) -> Unit = {},
        ): BooleanProofPortfolio? {
            fun decline(reason: String): BooleanProofPortfolio? {
                declined(reason)
                return null
            }
            if (workers.size != 2 || workers.any { !it.permitsSourceProofs || it.improvementOnly ||
                    it.auxiliaryProof || it.startAfterIncumbent || it.firstWitnessOnly })
                return decline("workers-not-two-canonical-complete")
            val recipes = workers.map { it.booleanProofRecipe ?: return decline("builder-ownership-unavailable") }
            val problem = recipes.first().problem
            if (recipes.any { it.problem !== problem || !it.deterministicClauseQuery() ||
                    !it.matches(recipes.first()) })
                return decline("worker-model-or-recipe-mismatch")
            if (problem.numBoolVars !in 2..4096 || problem.numIntVars != 0 || problem.numRealVars != 0 ||
                problem.hasSymmetryBreaking || problem.rootDeductions is PropagationResult.Unsat ||
                problem.factors.any { it !is Clause }) return decline("model-ineligible")
            val invocation = workers.first().evidenceInvocation ?: return decline("runtime-invocation-unavailable")
            if (workers.any { worker ->
                    worker.evidenceInvocation?.let(invocation::matches) != true ||
                        worker.evidenceModel?.matches(ModelIdentity.of(problem)) != true
                }) return decline("runtime-invocation-mismatch")
            val plan = invocation.preparedPlan
            if (invocation.hasSourceObjective || invocation.executionObjective != null ||
                invocation.hasUncapturedSourceRestrictions || plan.model !== problem ||
                plan.sourceBoolCount != problem.numBoolVars || plan.sourceIntCount != 0 || plan.sourceRealCount != 0
            ) return decline("source-plan-or-objective-ineligible")
            val condition = invocation.executionCondition
            if (condition != invocation.sourceCondition || condition.lower.isNotEmpty() ||
                condition.upper.isNotEmpty() || condition.integerRows.isNotEmpty())
                return decline("uncaptured-worker-condition")
            val params = recipes.map { recipe ->
                if (recipe.params.assumptions.numInts != 0 || !recipe.params.assumptions.deductions.isEmpty ||
                    recipe.params.assumptions.bools != condition.bools) return decline("worker-assumption-mismatch")
                recipe.conditioned() ?: return decline("conditioned-sharing-unavailable")
            }
            val count = if (probeFirst) 1 else 2
            val splits = (0 until problem.numBoolVars).filter { it !in condition.bools }.take(count)
            if (splits.size != count) return decline("insufficient-free-booleans")
            // Runtime bindings own source identity; fresh board ids prevent cross-invocation leases.
            val identity = Uuid.random().toString()
            val scope = invocation.scope?.copy(condition = condition) ?: SourceScope(
                SourceRevision(identity, "klause-ir-clauses-v1"), condition,
            )
            return BooleanProofPortfolio(problem, BooleanProofContext(scope, identity + "/identity-bool-v1"),
                splits, params, recipes.map { it.reserve }, probeFirst, recipes.map { it.solver })
        }
    }
}
