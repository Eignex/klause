package com.eignex.klause.backtrack

import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.compile
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SampleResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import kotlin.random.Random

/**
 * Complete depth-first search over a [Problem]'s assignment space, driven by propagation
 * via [PropagationSession]. Variable selection and value selection are plug-in heuristics
 * via [BacktrackParams.variableSelector] / [BacktrackParams.valueSelector] — same split
 * MiniZinc uses for `solve :: int_search(vars, var_strategy, value_strategy, complete)`.
 *
 *  - [solve] — first witness as [SolveResult.Sat], [SolveResult.Unsat] when the tree is
 *    fully explored, [SolveResult.Unknown] on [BacktrackParams.maxDecisions] exhaustion.
 *  - [samples] — yields every SAT leaf reached during traversal (each one distinct).
 *  - [enumerate] — same as [samples] plus the rolling-window Hamming-distance filter.
 *  - [minimize] — enumerates feasible assignments and returns the lowest-scoring one.
 *    Complete but exponential.
 *
 *  Complete enumeration on `n` unpinned bools walks up to `2^n` branches. Use
 *  [BacktrackParams.maxDecisions] to cap exploration on large problems.
 */
class BacktrackSolver(override val problem: Problem) :
    Solver<BacktrackParams>,
    Optimizer<BacktrackParams>,
    ResumableOptimizer<BacktrackParams> {

    /** Solve a [CompiledSchema]'s problem. */
    constructor(compiled: CompiledSchema) : this(compiled.problem)

    /** Compile [schema] with the default config and solve the resulting problem. */
    constructor(schema: VariableSchema) : this(schema.compile().problem)

    /** Solve once and return a [SolveResult]. */
    fun solve(): SolveResult = solve(BacktrackParams())

    /** Draw a single diverse sample, or null if none exists. */
    fun sample(): SampleResult = sample(BacktrackParams())

    /** Lazily draw diverse samples. */
    fun samples(): Sequence<Sample> = samples(BacktrackParams())

    /** Lazily enumerate distinct models. */
    fun enumerate(): Sequence<Sample> = enumerate(BacktrackParams())

    /** Optimise against [objective] under the hard constraints. */
    fun minimize(objective: LinearObjective): MinimizeResult = minimize(objective, BacktrackParams())

    /** Open an explicit-state, pausable branch-and-bound over [objective] (#381). See [ResumableSearch].
     *  The handle reuses this solver's search primitives; [params] should carry the
     *  [BacktrackParams.objectiveBoundSupplier] for external bound sharing (its [BacktrackParams.cancellation]
     *  is superseded per slice). */
    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch =
        ResumableMinimize(this, objective, params)

    /**
     * Open a reusable [RepairSearch] for the LNS destroy/repair loop (#644): one persistent
     * [ResumableMinimize] whose session (learned-clause DB) and LP relaxation are re-seeded per fragment
     * via [ResumableMinimize.rebind] instead of rebuilt. The per-fragment objective cutoff is threaded
     * through the [BacktrackParams.objectiveBoundSupplier] so it prunes against best-known; the caller
     * keeps that cutoff monotone (see [RepairSearch.repair]). [params] is the base repair config (its own
     * `objectiveBoundSupplier` is overridden here).
     */
    internal fun openRepair(objective: LinearObjective, params: BacktrackParams): RepairSearch {
        var activeCutoff = Double.POSITIVE_INFINITY
        val handle = ResumableMinimize(
            this,
            objective,
            params.copy(objectiveBoundSupplier = { activeCutoff }),
            pausable = false,
        )
        return object : RepairSearch {
            override fun repair(assumptions: Assumptions, decisionBudget: Long, cutoff: Double): Sample? {
                activeCutoff = cutoff
                handle.rebind(assumptions, decisionBudget)
                var best: Sample? = null
                while (!handle.isDone) {
                    val terminal = handle.runSlice(Cancellation.Never, Long.MAX_VALUE) { best = it.sample }
                    if (terminal != null) break
                }
                return best
            }

            override fun close() = handle.close()
        }
    }

    override fun describe(params: BacktrackParams): String {
        val lp = params.lpConfig?.let { "config" }
            ?: if (params.lpPlan.bounding) "bounding${if (params.lpPlan.variableShaving) "+shave" else ""}" else "off"
        return """
            backtrack
              seed:        ${params.randomSeed ?: "auto"}
              var-select:  ${params.variableSelector::class.simpleName}
              val-select:  ${params.valueSelector::class.simpleName}
              luby:        ${params.lubyRestartBase ?: "off"}
              max-learned: ${params.maxLearnedClauses ?: "unbounded"}
              lp:          $lp
        """.trimIndent()
    }

    override fun solve(params: BacktrackParams): SolveResult {
        val sink = SolveStatsSink(backend = "backtrack")
        sink.start()
        for (outcome in driveSearch(params, sink = sink)) {
            sink.stop()
            val stats = sink.snapshot()
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample, stats)

                is SearchOutcome.Exhausted ->
                    if (outcome.indeterminate) {
                        // A leaf's continuous LP could not be certified either way, so the tree is not
                        // provably all-infeasible — report `unknown` rather than an unsound UNSAT.
                        SolveResult.Unknown(TerminationReason.Unsupported, stats)
                    } else {
                        SolveResult.Unsat(
                            core = outcome.core,
                            stats = stats,
                            assumptionCore =
                            projectTouchedToAssumptions(params.assumptions, outcome.touchedAssumptionLevels),
                        )
                    }

                SearchOutcome.BudgetCapped -> {
                    sink.timedOut = true
                    SolveResult.Unknown(TerminationReason.BudgetExhausted, sink.snapshot())
                }
            }
        }
        sink.stop()
        return SolveResult.Unsat(stats = sink.snapshot())
    }

    /**
     * Independent random samples ("with replacement", per the [com.eignex.klause.solver.Solver.samples]
     * contract). Each yield kicks off a fresh DFS from root on a new [PropagationSession]
     * with a per-call RNG seed; no engine state carries between yields, so subsequent
     * yields are statistically independent given the random heuristic defaults.
     *
     * **Reproducibility.** With a fixed [BacktrackParams.randomSeed] the per-call seeds
     * are derived by a deterministic LCG advance, so the same parent seed produces the
     * same sequence of samples across runs. This is reproducibility, not correlation —
     * the per-call seeds are independent random draws as far as the search is concerned.
     *
     * **Duplicates.** The sequence does **not** filter duplicates. For a problem with N
     * feasible models, the same model may be yielded multiple times; the distribution
     * across yields is determined by the heuristics. For distinct samples use [enumerate]
     * (complete + DFS-ordered) or `samples(p).distinct().take(n)` (random + distinct,
     * uses memory linear in yielded count).
     *
     * **Termination.** The sequence is **infinite for any feasible problem** — callers
     * must bound it with `.take(n)` or `.takeWhile(...)`. It terminates early only when:
     *  - a run returns [SolveResult.Unsat] — the entire search tree exhausts without a
     *    SAT (the problem is infeasible); or
     *  - a run returns [SolveResult.Unknown] — [BacktrackParams.maxDecisions] elapsed
     *    before any SAT was found on that run.
     */
    override fun samples(params: BacktrackParams): Sequence<Sample> = sequence {
        var seed = params.randomSeed ?: Random.Default.nextLong()
        while (true) {
            val perCall = params.copy(randomSeed = seed)
            when (val r = solveOnce(perCall)) {
                is SolveResult.Sat -> yield(r.assignment)
                is SolveResult.Unsat -> return@sequence
                is SolveResult.Unknown -> return@sequence
            }
            // LCG advance for reproducibility: same parent seed → same per-call seed
            // sequence → same sample sequence. The per-call seeds drive the heuristics'
            // random choices; from the search's perspective they're independent draws.
            seed = seed * 6364136223846793005L + 1442695040888963407L
        }
    }

    private fun solveOnce(params: BacktrackParams): SolveResult {
        for (outcome in driveSearch(params)) {
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample)
                is SearchOutcome.Exhausted -> SolveResult.Unsat(outcome.core)
                SearchOutcome.BudgetCapped -> SolveResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        return SolveResult.Unsat()
    }

    /**
     * Distinct SAT assignments via single-DFS traversal of the search tree. Complete:
     * given enough budget, every distinct feasible assignment is yielded exactly once.
     * The optional rolling Hamming-distance window adds extra spacing between yields.
     *
     * For *diverse* distinct samples — useful when a small test/verification budget
     * shouldn't be spent on one subtree — call [samples] (which uses random restarts
     * with-replacement) and de-duplicate client-side, e.g. `samples(p).distinct().take(n)`.
     */
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (outcome in driveSearch(params)) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val snap = outcome.sample
                    if (farEnough(snap, window, params.minHammingDistance)) {
                        yield(snap)
                        if (params.recentWindow > 0) {
                            if (window.size >= params.recentWindow) window.removeFirst()
                            window.addLast(snap)
                        }
                    }
                }

                is SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> return@sequence
            }
        }
    }

    /**
     * Branch-and-bound minimisation. Walks the DFS yielding feasible leaves; each leaf
     * improves the incumbent `bestObj` and tightens a partial-assignment lower bound
     * that the search engine consults on every successful pin to prune the subtree when
     * it provably can't beat the incumbent. The pruning predicate closes over the
     * mutable `bestObj`, so the tightening propagates lazily without explicit
     * communication into the engine.
     *
     * For [LinearObjective] the bound is `Σ_b lb_b(bool) + Σ_i lb_i(int) + constant`,
     * where:
     *  - `lb_b = boolWeights[b]` if `b` is pinned-true, `0` if pinned-false,
     *    `min(0, boolWeights[b])` if unpinned;
     *  - `lb_i = coeff[i] · (coeff ≥ 0 ? dom.min : dom.max)`.
     *
     * Sound: every completion can only *raise* the contribution of unpinned vars from
     * the minimum, so an LB that already equals or exceeds the incumbent guarantees no
     * descendant leaf beats it.
     */
    override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult =
        improvements(objective, params).last()

    /**
     * Anytime variant of [minimize]: yields one [MinimizeResult.BestFound] per new
     * incumbent discovered, followed by exactly one terminal verdict
     * ([MinimizeResult.Optimal] / [MinimizeResult.Infeasible] / final
     * [MinimizeResult.BestFound] / [MinimizeResult.Unknown]). Same B&B engine as
     * [minimize]; just exposes the search's intermediate bests as they land instead of
     * collapsing them into a single return value.
     *
     * With [BacktrackParams.lpConfig] the LP-relaxation family is enabled here, structurally:
     * [LpAutoConfig.resolve] ORs on the techniques the emphasis permits whose target structure the
     * problem contains. The objective is statically linear, so no objective-shape check is involved —
     * LP enablement is purely a params decision.
     */
    override fun improvements(objective: LinearObjective, params: BacktrackParams): Sequence<MinimizeResult> =
        sequence {
            // The single B&B orchestration ([ResumableMinimize]), driven lazily: one incumbent surfaced
            // per step, then the terminal verdict. lpConfig is resolved inside the search. `pausable = false`
            // makes a fired cancellation a hard terminal stop (no resume) — a one-shot stream's contract.
            val search = ResumableMinimize(this@BacktrackSolver, objective, params, pausable = false)
            while (true) {
                when (val event = search.runUntilEvent()) {
                    is StepEvent.Incumbent -> yield(event.result)

                    is StepEvent.Terminal -> {
                        yield(event.result)
                        break
                    }

                    StepEvent.Paused -> break // unreachable when pausable = false
                }
            }
        }
}

/** Ceiling on the adaptive cancellation cadence (nodes between deadline polls). Fast instances
 *  settle here — a few microseconds per check at worst; slow ones adapt below it. See
 *  `ResumableMinimize.adaptCancelInterval`. */
internal const val CANCEL_CHECK_INTERVAL: Int = 256

/** Target wall-clock gap (ms) between deadline polls; the adaptive cadence steers toward it so `-t`
 *  overshoot stays ~this small regardless of per-node cost. */
internal const val CANCEL_CHECK_TARGET_MS: Long = 5

/** Cap on cascading CDB backjumps within a single search step. Defensive; under
 *  a well-formed analyzer the loop terminates well before this. */
internal const val MAX_CASCADING_BACKJUMPS: Int = 64

/** After this many identical re-derivations of one clause, its conflicts are
 *  handled chronologically instead of by backjump — a repeat-learning streak this
 *  long means the backjump + assert cycle is not progressing. Generous enough that
 *  healthy re-learning (after forgetting or restarts) never trips it. */
internal const val RELEARN_FALLBACK_THRESHOLD: Int = 8
