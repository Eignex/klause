package com.eignex.klause.solver

import com.eignex.klause.count.ApproxCountConfig
import com.eignex.klause.count.Count
import com.eignex.klause.count.CountConfig
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.count.accurateSamples
import com.eignex.klause.count.combineCounts
import com.eignex.klause.count.countingScope
import com.eignex.klause.count.scopedApproximateCount
import com.eignex.klause.count.scopedExactCount
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.TerminationReason

/**
 * Single-threaded per-instance scope and retained state for a [Solver]. Each operation captures
 * the merged assumption stack at invocation; later pushes override earlier scopes and call parameters.
 * Popping restores the preceding scope without changing the source problem. An operation must enforce
 * its scope or throw [UnsupportedOperationException]. Counting uses an isolated conditioned model.
 *
 * Fresh calls start fresh searches. Resumable handles retain their backend state between slices;
 * closeable streams retain their traversal between yields. An open handle or stream excludes other
 * operations and scope changes until completion, failure or close. [close] closes the active owner,
 * clears scopes and rejects subsequent operations. Repeated close is harmless.
 *
 * Legacy sequences capture scopes at invocation and must be consumed sequentially. Use the open
 * streaming methods with `use` when stopping early. Local-search sessions also retain learned weights;
 * the default [StatelessSession] retains only scopes and ownership.
 */
interface Session<P : SolverParams> : AutoCloseable {
    /** The backing solver. */
    val solver: Solver<P>

    /** The immutable source problem, independent of pushed scopes. */
    val problem: Problem get() = solver.problem

    /** Current depth of the assumption stack. */
    val depth: Int

    /** Push pins and deductions for subsequent operations; later pins win. */
    fun push(assumptions: Assumptions)

    /** Restore the preceding scope; throws when the stack is empty or an owner is active. */
    fun pop()

    /** Solve once under the captured scope. */
    fun solve(params: P): SolveResult

    /** Open a satisfaction handle under the captured scope, or null when unsupported. */
    fun resumableSolve(params: P): ResumableSolve? = null

    /** Open an optimization handle under the captured scope, or null when unsupported. */
    fun resumable(objective: LinearObjective, params: P): ResumableSearch? = null

    /** Draw one sample under the captured scope and close the draw's state. */
    fun sample(params: P): SampleResult {
        val sample = openSamples(params).use { it.firstOrNull() }
        return if (sample != null) SampleResult.Found(sample)
        else SampleResult.Unknown(TerminationReason.BudgetExhausted)
    }

    /** Legacy sample sequence under a scope captured at invocation; consume sequentially. */
    fun samples(params: P): Sequence<Sample>

    /** Legacy enumeration under a scope captured at invocation; consume sequentially. */
    fun enumerate(params: P): Sequence<Sample>

    /** Approximate count under the captured scope. */
    fun approximateCount(config: ApproxCountConfig = ApproxCountConfig()): Count {
        requireUnscoped()
        return solver.approximateCount(config)
    }

    /** Anytime exact count under a scope captured at invocation. */
    fun exactCount(config: ExactCountConfig = ExactCountConfig()): Sequence<Count> {
        requireUnscoped()
        return solver.exactCount(config)
    }

    /** Best-effort exact/approximate count under the captured scope. */
    fun count(config: CountConfig = CountConfig()): Count {
        requireUnscoped()
        return solver.count(config)
    }

    /** Quality-tiered sampling under a scope captured at invocation. */
    fun samples(config: SamplingConfig, params: P): Sequence<Sample> = when (config.quality) {
        SampleQuality.CHEAP -> samples(params)
        SampleQuality.ACCURATE -> {
            requireUnscoped()
            solver.samples(config, params)
        }
    }

    /** Minimize under the captured scope, or reject unsupported optimization. */
    fun minimize(objective: LinearObjective, params: P): MinimizeResult {
        requireUnscoped()
        return optimizer().minimize(objective, params)
    }

    /** Legacy incumbent sequence under a scope captured at invocation; consume sequentially. */
    fun improvements(objective: LinearObjective, params: P): Sequence<MinimizeResult> {
        requireUnscoped()
        return optimizer().improvements(objective, params)
    }

    /** Open a scoped sample cursor; close when stopping early. */
    fun openSamples(params: P): SearchStream<Sample> = samples(params).asSearchStream()

    /** Open a scoped quality-tiered sample cursor; close when stopping early. */
    fun openSamples(config: SamplingConfig, params: P): SearchStream<Sample> = samples(config, params).asSearchStream()

    /** Open a scoped enumeration cursor; close when stopping early. */
    fun openEnumerate(params: P): SearchStream<Sample> = enumerate(params).asSearchStream()

    /** Open a scoped exact-count cursor; close when stopping early. */
    fun openExactCount(config: ExactCountConfig = ExactCountConfig()): SearchStream<Count> =
        exactCount(config).asSearchStream()

    /** Open a scoped incumbent cursor; close when stopping early. */
    fun openImprovements(objective: LinearObjective, params: P): SearchStream<MinimizeResult> =
        improvements(objective, params).asSearchStream()

    /** Release the active owner and retained session state; idempotent. */
    override fun close() {}
}

private fun Session<*>.requireUnscoped() {
    if (depth != 0) throw UnsupportedOperationException("this session does not implement scoped operations")
}

@Suppress("UNCHECKED_CAST")
private fun <P : SolverParams> Session<P>.optimizer(): Optimizer<P> = solver as? Optimizer<P>
    ?: throw UnsupportedOperationException("Solver ${solver::class.simpleName} does not implement Optimizer")

/** A [Session] retaining assumption scopes and exclusive ownership of open searches. */
open class StatelessSession<P : SolverParams>(override val solver: Solver<P>) : Session<P> {
    private val stack = ArrayDeque<Assumptions>()
    private var closed = false
    private var active: AutoCloseable? = null

    override val depth: Int get() = stack.size

    /** Reject operations on a closed session or while an open search owns it. */
    protected fun ensureAvailable() {
        check(!closed) { "session is closed" }
        check(active == null) { "session already has an active search" }
    }

    override fun push(assumptions: Assumptions) {
        ensureAvailable()
        stack.addLast(assumptions)
    }

    override fun pop() {
        ensureAvailable()
        require(stack.isNotEmpty()) { "Session.pop on an empty assumption stack" }
        stack.removeLast()
    }

    /** Merge scopes onto parameters, with later pushes winning. */
    @Suppress("UNCHECKED_CAST")
    protected fun applyStack(params: P): P {
        ensureAvailable()
        return if (stack.isEmpty()) params else params.withAssumptions(mergedStack()) as P
    }

    private fun mergedStack(): Assumptions = stack.fold(Assumptions.None) { scope, next -> scope.mergedWith(next) }

    private fun <T> guarded(sequence: Sequence<T>): Sequence<T> = Sequence {
        ensureAvailable()
        val cursor = sequence.iterator()
        object : Iterator<T> {
            override fun hasNext(): Boolean {
                ensureAvailable()
                return cursor.hasNext()
            }
            override fun next(): T {
                ensureAvailable()
                if (!cursor.hasNext()) throw NoSuchElementException()
                return cursor.next()
            }
        }
    }

    private fun <T : Any> own(source: SearchStream<T>): SearchStream<T> {
        val owned = PullSearchStream(
            pull = { if (source.hasNext()) source.next() else null },
            release = { try { source.close() } finally { active = null } },
            terminal = { source.isDone },
        )
        active = owned
        return owned
    }

    override fun solve(params: P): SolveResult = solveScoped(applyStack(params))

    override fun resumableSolve(params: P): ResumableSolve? {
        val source = resumableSolveScoped(applyStack(params)) ?: return null
        return ownSolve(source) { active = null }.also { active = it }
    }

    override fun resumable(objective: LinearObjective, params: P): ResumableSearch? {
        val source = resumableScoped(objective, applyStack(params)) ?: return null
        return ownSearch(source) { active = null }.also { active = it }
    }

    override fun samples(params: P): Sequence<Sample> = guarded(samplesScoped(applyStack(params)))
    override fun enumerate(params: P): Sequence<Sample> = guarded(enumerateScoped(applyStack(params)))

    override fun samples(config: SamplingConfig, params: P): Sequence<Sample> {
        val scoped = applyStack(params)
        return guarded(qualitySamples(config, scoped))
    }

    private fun qualitySamples(config: SamplingConfig, params: P): Sequence<Sample> = when (config.quality) {
        SampleQuality.CHEAP -> samplesScoped(params)
        SampleQuality.ACCURATE -> solver.problem.countingScope(params.assumptions)
            .accurateSamples(config) { samplesScoped(params) }
    }

    override fun approximateCount(config: ApproxCountConfig): Count {
        ensureAvailable()
        return if (stack.isEmpty()) solver.approximateCount(config)
        else solver.problem.countingScope(mergedStack()).scopedApproximateCount(config)
    }

    override fun exactCount(config: ExactCountConfig): Sequence<Count> {
        ensureAvailable()
        val sequence = if (stack.isEmpty()) solver.exactCount(config)
        else solver.problem.countingScope(mergedStack()).scopedExactCount(config)
        return guarded(sequence)
    }

    override fun count(config: CountConfig): Count {
        ensureAvailable()
        if (stack.isEmpty()) return solver.count(config)
        val scope = solver.problem.countingScope(mergedStack())
        return combineCounts(scope.scopedExactCount(config.toExactConfig()).last()) {
            scope.scopedApproximateCount(config.toApproxConfig())
        }
    }

    override fun minimize(objective: LinearObjective, params: P): MinimizeResult =
        minimizeScoped(objective, applyStack(params))

    override fun improvements(objective: LinearObjective, params: P): Sequence<MinimizeResult> =
        guarded(improvementsScoped(objective, applyStack(params)))

    override fun openSamples(params: P): SearchStream<Sample> = own(openSamplesScoped(applyStack(params)))

    override fun openEnumerate(params: P): SearchStream<Sample> = own(openEnumerateScoped(applyStack(params)))

    override fun openSamples(config: SamplingConfig, params: P): SearchStream<Sample> {
        val scoped = applyStack(params)
        return own(openQualitySamplesScoped(config, scoped))
    }

    override fun openExactCount(config: ExactCountConfig): SearchStream<Count> {
        ensureAvailable()
        val sequence = if (stack.isEmpty()) solver.exactCount(config)
        else solver.problem.countingScope(mergedStack()).scopedExactCount(config)
        return own(sequence.asSearchStream())
    }

    override fun openImprovements(objective: LinearObjective, params: P): SearchStream<MinimizeResult> =
        own(openImprovementsScoped(objective, applyStack(params)))

    internal open fun solveScoped(params: P): SolveResult = solver.solve(params)
    internal open fun resumableSolveScoped(params: P): ResumableSolve? =
        (solver as? ResumableSolver<P>)?.resumableSolve(params)
    internal open fun resumableScoped(objective: LinearObjective, params: P): ResumableSearch? =
        (solver as? ResumableOptimizer<P>)?.resumable(objective, params)
    internal open fun samplesScoped(params: P): Sequence<Sample> = solver.samples(params)
    internal open fun enumerateScoped(params: P): Sequence<Sample> = solver.enumerate(params)
    internal open fun minimizeScoped(objective: LinearObjective, params: P): MinimizeResult =
        optimizer().minimize(objective, params)
    internal open fun improvementsScoped(objective: LinearObjective, params: P): Sequence<MinimizeResult> =
        optimizer().improvements(objective, params)
    internal open fun openSamplesScoped(params: P): SearchStream<Sample> = solver.openSamples(params)
    internal open fun openQualitySamplesScoped(config: SamplingConfig, params: P): SearchStream<Sample> =
        when (config.quality) {
            SampleQuality.CHEAP -> openSamplesScoped(params)
            SampleQuality.ACCURATE -> qualitySamples(config, params).asSearchStream()
        }
    internal open fun openEnumerateScoped(params: P): SearchStream<Sample> = solver.openEnumerate(params)
    internal open fun openImprovementsScoped(objective: LinearObjective, params: P): SearchStream<MinimizeResult> =
        optimizer().openImprovements(objective, params)

    override fun close() {
        if (closed) return
        closed = true
        try {
            active?.close()
        } finally {
            active = null
            stack.clear()
        }
    }
}
