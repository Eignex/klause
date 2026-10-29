package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.CertifiedLpResult
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveSession
import com.eignex.klause.lp.engine.LpSolveStep
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.solver.validation.floatingOptimumAcceptance
import com.eignex.klause.theory.finiteLinearModel
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyDoubleArray

internal class LeafRealSolver(
    private val problem: Problem,
    private val objective: LinearObjective?,
    componentSplit: Boolean = true,
    private val sink: LpStatsSink? = null,
    context: LpSolveContext = LpSolveContext.Production,
    pricing: LpPricingOptions = LpPricingOptions(),
) : AutoCloseable {
    private val encoders = problem.selectedFactorProjections()
    private val sources = LpRetainedSources(problem, CpToLpRelaxation(problem, objective, projections = null))
    private var trail = LpBoundTrail(LpRetainedSources.emptyModel())
    private val solvers = LpSolveSession(context, pricing, componentSplit)
    private var integers = arrayOfNulls<IntDomain>(problem.numIntVars)
    private var nextWitness = 0L
    private var closed = false
    private var activeSolve: LpSolveCursor<LeafRealResult>? = null

    fun solve(
        sample: Sample,
        cancellation: Cancellation = Cancellation.Never,
        toleranceCheck: ((Sample) -> Boolean)? = null,
        workLimit: Long = 0L,
    ): LeafRealResult {
        check(!closed) { "real leaf solver is closed" }
        check(activeSolve == null) { "real leaf solver is suspended" }
        require(sample.numIntVars == problem.numIntVars && sample.numBoolVars == problem.numBoolVars)
        if (cancellation()) return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val encoded = encoders.finiteLinearModel(sample, cancellation)
            ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val relaxation = try {
            relaxation(sample, cancellation)
        } catch (_: LpAssemblyCancelled) {
            null
        } ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val certified = solvers.solve(
            relaxation.model,
            cancellation = cancellation,
            workLimit = workLimit,
            observer = sink?.certificationObserver(LpRoute.STANDALONE),
            floatAccept = toleranceCheck.floatingOptimumAcceptance()?.let { check ->
                { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
            },
            floatOffset = relaxation.objectiveConstant.toDouble(),
            claim = if (objective == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM,
        )
        return relaxation.leafResult(certified, problem, objective, sample, sink)
            .checkedEncoding(encoded, sample, cancellation)
            .also { it.observeConsumption(sink) }
    }

    fun openSolve(
        sample: Sample,
        cancellation: Cancellation = Cancellation.Never,
        toleranceCheck: ((Sample) -> Boolean)? = null,
    ): LpSolveCursor<LeafRealResult> {
        check(!closed && activeSolve == null) { "real leaf solver is closed or suspended" }
        require(sample.numIntVars == problem.numIntVars && sample.numBoolVars == problem.numBoolVars)
        return LpSolveCursor<LeafRealResult>(completed = { activeSolve = null }) {
            if (cancellation()) return@LpSolveCursor LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
            val encoded = encoders.finiteLinearModel(sample, cancellation)
                ?: return@LpSolveCursor LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
            val relaxation = try {
                relaxation(sample, cancellation)
            } catch (_: LpAssemblyCancelled) {
                null
            } ?: return@LpSolveCursor LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
            val certified = solvers.openSolve(
                relaxation.model,
                cancellation = cancellation,
                observer = sink?.certificationObserver(LpRoute.STANDALONE),
                floatAccept = toleranceCheck.floatingOptimumAcceptance()?.let { check ->
                    { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
                },
                floatOffset = relaxation.objectiveConstant.toDouble(),
                claim = if (objective == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM,
            ).use { cursor ->
                var step = cursor.advance(shouldPause = { shouldPause(cursor.work) })
                while (step is LpSolveStep.Paused) {
                    pauseAt(cursor.work)
                    step = cursor.advance(shouldPause = { shouldPause(cursor.work) })
                }
                (step as LpSolveStep.Completed<CertifiedLpResult>).value
            }
            val result = relaxation.leafResult(certified, problem, objective, sample, sink)
                .checkedEncoding(encoded, sample, cancellation)
            if (cancellation()) LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
            else result.also { it.observeConsumption(sink) }
        }.also { activeSolve = it }
    }

    private fun relaxation(sample: Sample, cancellation: Cancellation): LpRelaxation? {
        val pins = Array(problem.numIntVars) { variable ->
            val value = sample.intValue(variable)
            integers[variable]?.takeIf { it.min == value } ?: IntDomain(value, value)
        }
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = pins[varId]
            override fun boolValue(varId: Int): Boolean = sample.boolValue(varId)
        }
        val staged = LpBoundTrail(trail.state)
        var edit = sources.prepare(staged.state, domains, cancellation) ?: return null
        val weakens = (0 until staged.state.model.n).any { column ->
            val previous = staged.state.model.column(column).bounds
            val requested = edit.bounds[column]
            previous.lower?.let { requested.lower == null || it.strongerThan(requested.lower, false) } == true ||
                previous.upper?.let { requested.upper == null || it.strongerThan(requested.upper, true) } == true
        }
        if (weakens) {
            if (!staged.resetRoot(cancellation)) return null
            edit = sources.prepare(staged.state, domains, cancellation) ?: return null
        }
        val assertions = edit.boundAssertions(nextWitness, cancellation) ?: return null
        val before = staged.state.assertions.size
        if (!staged.replaceRows(edit.retired, edit.columns, edit.rows, false, cancellation,
                edit.permanentRows, edit.objective, assertions) || !edit.isCurrent() || cancellation()
        ) {
            return null
        }
        val count = staged.state.assertions.size - before
        edit.commit()
        trail = staged
        nextWitness += count
        integers = Array(pins.size) { pins[it] }
        sources.prepareCompaction(trail.state, cancellation)?.let { compaction ->
            if (compaction.isCurrent() && trail.compact(compaction.remap, cancellation)) compaction.commit()
        }
        return sources.relaxation(trail.state, domains)
    }

    fun releaseSolvers() = release(false)

    @Suppress("TooGenericExceptionCaught")
    private fun release(close: Boolean) {
        val pending = activeSolve
        activeSolve = null
        var failure: Throwable? = null
        try {
            if (pending?.isAdvancing != true) pending?.close()
        } catch (cleanup: Throwable) {
            failure = cleanup
        }
        try {
            if (close) solvers.close() else solvers.releaseSolvers()
        } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        release(true)
    }
}
