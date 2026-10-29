package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.solver.search.BooleanProofTasks
import com.eignex.klause.solver.search.BooleanTaskCheck
import com.eignex.klause.solver.search.BooleanTaskLease
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.Cancellation

internal data class BooleanProofStep(
    val task: Int?,
    val sample: Sample? = null,
    val reason: TerminationReason? = null,
)

@Suppress("TooGenericExceptionCaught") // retire every owned handle while preserving primary engine failures
internal class BooleanProofScheduler(
    private val problem: BakedProblem,
    context: BooleanProofContext,
    splitVariables: List<Int>,
    parameters: List<BacktrackParams>,
) : AutoCloseable {
    private class Worker {
        var search: RepairSearch? = null
        var lease: BooleanTaskLease? = null
        var retiredWork = 0L
        var retiredStats = SolveStats.EMPTY
    }

    private val parameters = parameters.toList()
    private val sourceRevision = context.scope.revision
    private val sourceMapping = context.mapping
    private val splitVariablesForValidation = splitVariables.toSet()
    private val tasks = BooleanProofTasks(context, splitVariables)
    private val workers = parameters.map { Worker() }
    private val objective = LinearObjective(boolWeights = LongArray(problem.numBoolVars))
    private var closed = false
    private var witness: Sample? = null
    var chargedWork: Long = 0L
        private set

    val context: BooleanProofContext get() = tasks.context
    val nativeCoverageComplete: Boolean get() = tasks.nativeCoverageComplete
    val independentlyCheckedCoverageComplete: Boolean get() = tasks.independentlyCheckedCoverageComplete
    val pending: Int get() = tasks.pending
    val completed: Int get() = tasks.closed
    val size: Int get() = tasks.size
    val work: Long get() = workers.sumOf { it.retiredWork + (it.search?.work ?: 0L) }
    val stats: SolveStats get() = workers.fold(SolveStats.EMPTY) { result, worker ->
        result.mergedWith(worker.retiredStats).mergedWith(worker.search?.stats ?: SolveStats.EMPTY)
    }

    init {
        require(parameters.size in 1..64)
        require(problem.numBoolVars in 1..4096 && problem.numIntVars == 0 && problem.numRealVars == 0)
        require(problem.factors.all { it is Clause } && !problem.hasSymmetryBreaking)
        require(splitVariables.all { it in 0 until problem.numBoolVars })
        validate(context)
        for (params in parameters) {
            require(params.assumptions.bools == context.scope.condition.bools && params.assumptions.numInts == 0 &&
                params.assumptions.deductions.isEmpty)
            require(params.componentFactory == null && params.objectiveBoundSupplier == null &&
                params.objectiveLowerBoundSupplier == null && params.globalVarLowerSupplier == null &&
                params.globalVarUpperSupplier == null && params.toleranceCheck == null &&
                params.clauseExchange == null && params.cutExchange == null &&
                params.improvedSolutionSink == null && params.pooledIncumbents == null)
        }
    }

    // One serialized coordinator owns the board; a worker retains its exact lease through every pause.
    fun runSlice(worker: Int, cancellation: Cancellation, millis: Long, offeredWork: Long): BooleanProofStep {
        check(!closed)
        require(worker in workers.indices && offeredWork > 0L && millis > 0L)
        witness?.let { return BooleanProofStep(null, sample = it) }
        if (cancellation()) return BooleanProofStep(null, reason = TerminationReason.Cancelled)
        val slot = workers[worker]
        val lease = slot.lease ?: tasks.acquire(worker)?.also { slot.lease = it }
            ?: return BooleanProofStep(null)
        try {
            if (slot.search == null) {
                slot.search = BacktrackSolver(problem).openRepair(objective, parameters[worker].copy(
                    nativeSat = false, assumptions = Assumptions.None,
                ))
            }
            val search = checkNotNull(slot.search)
            if (started[worker] !== lease) {
                search.begin(assumptions(lease), parameters[worker].maxDecisions, Double.POSITIVE_INFINITY, cancellation)
                started[worker] = lease
            }
            val result = search.runSlice(cancellation, millis, offeredWork)
            val sample = result.sample
            if (sample != null) {
                val verdict = finiteWitnessVerifier(problem, null, assumptions(lease), cancellation)
                    .verify(Candidate(sample, null))
                if (verdict is Verification.Accepted) {
                    witness = sample
                    tasks.release(lease)
                    slot.lease = null
                    return BooleanProofStep(lease.task, sample, result.reason)
                }
                if (verdict is Verification.Rejected) error("task worker produced an invalid witness")
            }
            if (result.reason != null) {
                tasks.complete(lease) {
                    if (sample == null && result.reason == TerminationReason.SearchExhausted) {
                        BooleanTaskCheck.NativeRefutation
                    } else BooleanTaskCheck.Unresolved
                }
                slot.lease = null
            }
            return BooleanProofStep(lease.task, reason = result.reason)
        } catch (failure: Throwable) {
            tasks.release(lease)
            slot.lease = null
            retire(worker, failure)
            throw failure
        }
    }

    fun run(
        cancellation: Cancellation,
        workLimit: Long,
        baseSliceWork: Long = 5000L,
        maxSliceWork: Long = 150000L,
    ): SolveResult {
        check(!closed)
        require(workLimit >= 0L && baseSliceWork > 0L && maxSliceWork >= baseSliceWork)
        witness?.let { return SolveResult.Sat(it, stats = stats) }
        val stopped = BooleanArray(workers.size)
        var allowance = baseSliceWork
        var cursor = 0
        while (!cancellation() && chargedWork < workLimit && !nativeCoverageComplete) {
            if (workers.indices.none { !stopped[it] && (workers[it].lease != null || pending > 0) }) break
            val worker = cursor
            cursor = (cursor + 1) % workers.size
            if (stopped[worker] || (workers[worker].lease == null && pending == 0)) continue
            val offered = minOf(allowance, workLimit - chargedWork)
            val before = work
            val step = runSlice(worker, cancellation, Long.MAX_VALUE, offered)
            val spent = (work - before).coerceAtLeast(0L)
            val debit = if (spent == 0L) offered else spent
            chargedWork = if (debit > Long.MAX_VALUE - chargedWork) Long.MAX_VALUE else chargedWork + debit
            if (step.sample != null) return SolveResult.Sat(step.sample, stats = stats)
            if (step.reason != null && step.reason != TerminationReason.SearchExhausted) stopped[worker] = true
            allowance = (allowance.toDouble() * 1.5).toLong().coerceIn(baseSliceWork, maxSliceWork)
        }
        return if (nativeCoverageComplete) SolveResult.Unsat(
            stats = stats, assumptionCore = Assumptions(bools = context.scope.condition.bools),
            semantics = problem.semantics,
        ) else SolveResult.Unknown(
            if (cancellation()) TerminationReason.Cancelled else TerminationReason.BudgetExhausted,
            stats = stats, semantics = problem.semantics,
        )
    }

    private val started = arrayOfNulls<BooleanTaskLease>(parameters.size)

    fun loseWorker(worker: Int) {
        check(!closed)
        require(worker in workers.indices)
        tasks.loseWorker(worker)
        workers[worker].lease = null
        retire(worker)
    }

    fun replaceContext(replacement: BooleanProofContext) {
        check(!closed)
        validate(replacement)
        // Private learned state and permanent cutoffs belong to the preceding authority.
        workers.indices.forEach(::loseWorker)
        tasks.replaceContext(replacement)
        witness = null
    }

    private fun validate(value: BooleanProofContext) {
        require(value.scope.condition.bools.keys.none { id ->
            id in splitVariablesForValidation
        })
        require(value.scope.revision == sourceRevision && value.mapping == sourceMapping) {
            "a changed source or mapping requires a fresh scheduler"
        }
        require(value.scope.objective == null && value.cutoff == null) { "objective tasks are unavailable" }
        val condition = value.scope.condition
        require(condition.lower.isEmpty() && condition.upper.isEmpty() && condition.integerRows.isEmpty())
        require(condition.bools.keys.all { it in 0 until problem.numBoolVars })
    }

    private fun assumptions(lease: BooleanTaskLease): Assumptions = Assumptions(
        bools = lease.context.scope.condition.bools + lease.cube.literals.associate {
            Lit.variable(it) to Lit.isPositive(it)
        },
    )

    private fun retire(worker: Int, primary: Throwable? = null) {
        val slot = workers[worker]
        val search = slot.search ?: return
        slot.search = null
        started[worker] = null
        try {
            search.close()
        } catch (failure: Throwable) {
            if (primary == null) throw failure
            primary.addSuppressed(failure)
        } finally {
            slot.retiredWork += search.work
            slot.retiredStats = slot.retiredStats.mergedWith(search.stats)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.cancel()
        var failure: Throwable? = null
        for (worker in workers.indices) {
            try {
                retire(worker)
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            workers[worker].lease = null
        }
        failure?.let { throw it }
    }
}
