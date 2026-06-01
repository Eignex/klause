package com.eignex.klause.solver

/**
 * Stateful per-instance handle for a [Solver]. A Session holds the per-instance state
 * that would otherwise have to be threaded through every call:
 *
 *  - The current assumption stack: [push] / [pop] add or remove pinned variables;
 *    every solve / sample / enumerate call sees the merged stack on top of whatever
 *    `params.assumptions` already carries. Backends whose params don't model
 *    assumptions (BruteForce, LogicNG, Z3) silently drop the stack — push/pop still
 *    works in the Session but has no effect on those engines.
 *  - Future: learned clauses / no-goods (LCG), warm-start last solution, kumulant
 *    heuristic posteriors. These slot in as additional fields on per-backend Session
 *    subclasses without changing the interface.
 *
 * Sessions are **not** thread-safe. For parallel portfolios, give each worker its own
 * Session; share learning state through a kumulant `StatGroup` configured in Relaxed
 * concurrency mode.
 *
 * Open one via [Solver.session]; the default implementation is [StatelessSession],
 * which only manages the assumption stack and forwards to the underlying Solver.
 * Subclasses can override to add real cross-call state.
 */
interface Session<P : SolverParams> : AutoCloseable {
    val solver: Solver<P>
    val problem: Problem get() = solver.problem

    /** Current depth of the assumption stack. */
    val depth: Int

    /** Push a set of pinned variables onto the assumption stack. Every subsequent
     *  solve / sample / enumerate call sees this scope (merged with anything pushed
     *  earlier) until [pop] is called. */
    fun push(assumptions: Assumptions)

    /** Undo the most recent [push]. Throws if the stack is empty. */
    fun pop()

    fun solve(params: P): SolveResult

    /** Default implementation drains [samples] for one yield. Wraps it in
     *  [SampleResult.Found] when the sequence yields, [SampleResult.Unknown] when it
     *  doesn't. Mirrors [Solver.sample]'s contract. */
    fun sample(params: P): SampleResult {
        val s = samples(params).firstOrNull()
        return if (s != null) {
            SampleResult.Found(s)
        } else {
            SampleResult.Unknown(TerminationReason.BudgetExhausted)
        }
    }
    fun samples(params: P): Sequence<Sample>
    fun enumerate(params: P): Sequence<Sample>

    /**
     * Optional optimisation entrypoint. Default delegates to the underlying solver via
     * the [Optimizer] interface when supported; backends without optimisation throw
     * [UnsupportedOperationException]. Subclasses that maintain incremental cross-call
     * optimisation state (B&B incumbent caching, warm-starts) override.
     */
    fun minimize(objective: Objective, params: P): MinimizeResult {
        val opt = solver as? Optimizer<P>
            ?: throw UnsupportedOperationException(
                "Solver ${solver::class.simpleName} does not implement Optimizer",
            )
        return opt.minimize(objective, params)
    }

    /** Streaming variant of [minimize]. See [Optimizer.improvements]. */
    fun improvements(objective: Objective, params: P): Sequence<MinimizeResult> {
        val opt = solver as? Optimizer<P>
            ?: throw UnsupportedOperationException(
                "Solver ${solver::class.simpleName} does not implement Optimizer",
            )
        return opt.improvements(objective, params)
    }

    /** Release any per-session native resources. Default is a no-op; backends that
     *  hold native handles (Z3 contexts, LogicNG factories) override. */
    override fun close() {}
}

/**
 * Default [Session] implementation. Maintains an assumption stack and forwards every
 * call to the underlying [Solver] after merging the stack into the call's params via
 * [SolverParams.withAssumptions]. Holds no other state.
 *
 * For backends whose params don't model assumptions, [SolverParams.withAssumptions]
 * is a no-op, so push/pop become harmless bookkeeping without affecting the engine.
 */
open class StatelessSession<P : SolverParams>(override val solver: Solver<P>) : Session<P> {
    private val stack = ArrayDeque<Assumptions>()

    override val depth: Int get() = stack.size

    override fun push(assumptions: Assumptions) {
        stack.addLast(assumptions)
    }

    override fun pop() {
        require(stack.isNotEmpty()) { "Session.pop on an empty assumption stack" }
        stack.removeLast()
    }

    /**
     * Merge the entire assumption stack into [params] via [SolverParams.withAssumptions].
     * Later pushes win on conflicts (last-write semantics).
     */
    @Suppress("UNCHECKED_CAST")
    protected fun applyStack(params: P): P {
        if (stack.isEmpty()) return params
        val merged = mergedStack()
        return params.withAssumptions(merged) as P
    }

    private fun mergedStack(): Assumptions {
        if (stack.size == 1) return stack.first()
        var merged = Assumptions.None
        for (a in stack) merged = merged.mergedWith(a)
        return merged
    }

    override fun solve(params: P): SolveResult = solver.solve(applyStack(params))
    override fun samples(params: P): Sequence<Sample> = solver.samples(applyStack(params))
    override fun enumerate(params: P): Sequence<Sample> = solver.enumerate(applyStack(params))

    override fun minimize(objective: Objective, params: P): MinimizeResult {
        val opt = solver as? Optimizer<P>
            ?: throw UnsupportedOperationException(
                "Solver ${solver::class.simpleName} does not implement Optimizer",
            )
        return opt.minimize(objective, applyStack(params))
    }

    override fun improvements(objective: Objective, params: P): Sequence<MinimizeResult> {
        val opt = solver as? Optimizer<P>
            ?: throw UnsupportedOperationException(
                "Solver ${solver::class.simpleName} does not implement Optimizer",
            )
        return opt.improvements(objective, applyStack(params))
    }
}
