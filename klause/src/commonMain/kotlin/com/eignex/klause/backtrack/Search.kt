package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunObserver
import com.eignex.klause.solver.search.SearchModelContinuation
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet
import kotlin.random.Random

/** Map touched-seed-level [IntArray] to the subset of [input] assumptions at those
 *  levels. Returns `null` when the input was empty (no assumption layer to
 *  project) or no level was touched (no information). */
internal fun BacktrackSolver.projectTouchedToAssumptions(input: Assumptions, levels: IntArray): Assumptions? {
    if (input.isEmpty || levels.isEmpty()) return null
    // [levels] is already the touched seed-level array; the projection is idempotent over
    // duplicates, so pass it straight through (no dedup set needed).
    return projectSeedConflictToAssumptions(input, levels)
}

/** Convert a touched-seed-level set into a sorted-ascending [IntArray], or empty
 *  when there were no touches (or no seed in the first place). */
internal fun BacktrackSolver.touchedToArray(touched: IntHashSet?): IntArray {
    if (touched == null || touched.isEmpty()) return EmptyIntArray
    val out = touched.toIntArray()
    out.sort()
    return out
}

/** Lift a [PropagationResult.Unsat]'s factor-level conflict info to a klause [UnsatCore].
 *  Empty `conflictFactors` (seed-only contradiction, no factor invocation involved)
 *  collapses to `null` — the API contract is "core absent" rather than "core empty",
 *  since an empty core wouldn't be actionable. */
internal fun BacktrackSolver.coreOf(unsat: PropagationResult.Unsat): UnsatCore? = if (unsat.conflictFactors.isEmpty()) {
    null
} else {
    UnsatCore.of(unsat.conflictFactors)
}

internal sealed interface SearchOutcome {
    data class Found(val sample: Sample) : SearchOutcome

    /** DFS exhausted without finding a model. [core] is non-null when the exhaustion
     *  was forced by root-level propagation (bake or seed); after a full DFS-tree
     *  walk, no single-factor core explains the result and [core] stays null.
     *  [touchedAssumptionLevels] is the union of seed-level decision levels that
     *  appeared in any conflict's learned-clause decision-level set during the
     *  search — feeds the assumption-core projection in
     *  [com.eignex.klause.solver.result.satisfyUnderAssumptions]. Empty when no seed was
     *  in play or no conflict referenced a seed level. */
    data class Exhausted(
        val core: UnsatCore? = null,
        val touchedAssumptionLevels: IntArray = EmptyIntArray,
        /** True when a leaf was reached whose residual LP-only continuous relaxation could not be
         *  certified feasible or infeasible (see `leafRealFeasibility`), so the tree is not provably
         *  all-infeasible — the terminal verdict must be `unknown`, not UNSAT. */
        val indeterminate: Boolean = false,
    ) : SearchOutcome
    data object BudgetCapped : SearchOutcome
}

/**
 * A trail frame for one variable being explored. The value iterator is supplied by the
 * caller's [ValueSelector] at node creation; [nextDecision] pulls the next value and
 * describes the corresponding shared search decision. Returns `null` when the value
 * iterator is exhausted.
 */
internal sealed interface TrailNode {
    val varRef: VarRef
    fun nextDecision(session: PropagationSession): NextDecision?
}

/** The heuristic-visible value and the shared decision selected for a trail frame. */
internal data class NextDecision(val value: Long, val decision: SearchDecision)

internal class BoolNode(override val varRef: VarRef.Bool, valueSeq: Sequence<Long>) : TrailNode {
    private val iter = valueSeq.iterator()
    override fun nextDecision(session: PropagationSession): NextDecision? {
        if (!iter.hasNext()) return null
        val v = iter.next()
        return NextDecision(v, SearchDecision.Bool(Lit.make(varRef.varId, v != 0L)))
    }
}

/**
 * Int decisions branch on a **bound**, not an equality: `v ≤ s` then `v ≥ s+1` (or the
 * reverse). Each branch is a single bound atom, so a conflict it seeds has one literal at
 * its level and 1UIP yields an asserting clause — an equality pin (`v = k`) instead pins
 * two same-level bound atoms that 1UIP cannot collapse, which stalls conflict learning.
 * The split point `s` is the value heuristic's preferred value (clamped into `[min, max-1]`
 * so both children are non-empty); the side holding that preferred value is explored first.
 */
internal class IntNode(override val varRef: VarRef.IntVar, valueSeq: Sequence<Long>) : TrailNode {
    private val preferred: Long = valueSeq.firstOrNull() ?: 0L
    private var step = 0
    private var splitLo = 0L
    private var splitHi = 0L
    private var lowerFirst = true
    private var resolved = false

    override fun nextDecision(session: PropagationSession): NextDecision? {
        if (!resolved) {
            val d = session.intDomain(varRef.varId)
            // On a non-enumerable (wide-span) domain, split at the bounds midpoint regardless of the value
            // heuristic's preferred value. An extreme preferred (min/max, e.g. indomain_min) would otherwise
            // peel one value per level — O(span) branch depth on a > 2^31-span domain — where a dichotomic
            // split is O(log span). [boundsMidpoint] lands in `[min, max-1]` here (min < max on a wide
            // domain), so both children stay non-empty; the partition `x <= s` / `x >= s+1` is complete for
            // any `s`, so this changes cost, never soundness.
            val s = when {
                // A preferred value strictly inside a wide domain is a real signal — on the LP path it is
                // the relaxation's own value for this column ([LpHints.order]) — and branching there is the
                // branch-and-bound split `x ≤ ⌊v⌋` / `x ≥ ⌈v⌉`. It matters most on a column left open by the
                // search clamp, where the midpoint is a bisection of an invented box rather than of
                // anything the model says. A preferred sitting *at* a bound carries no such signal (that is
                // what `indomain_min`/`max` produce), and following it would peel one value per level —
                // O(span) depth — so those still bisect.
                !d.enumerable && preferred > d.min && preferred < d.max -> preferred

                !d.enumerable -> boundsMidpoint(d)

                preferred >= d.max -> d.max - 1

                else -> maxOf(preferred, d.min)
            }
            splitLo = s
            splitHi = s + 1
            lowerFirst = preferred <= s
            resolved = true
        }
        val vid = varRef.varId
        return when (step++) {
            0 -> if (lowerFirst) {
                NextDecision(splitLo, SearchDecision.IntAtMost(vid, splitLo))
            } else {
                NextDecision(splitHi, SearchDecision.IntAtLeast(vid, splitHi))
            }

            1 -> if (lowerFirst) {
                NextDecision(splitHi, SearchDecision.IntAtLeast(vid, splitHi))
            } else {
                NextDecision(splitLo, SearchDecision.IntAtMost(vid, splitLo))
            }

            else -> null
        }
    }
}

/** Lazy shared-session outcomes for satisfaction, enumeration, and sampling. */
internal fun BacktrackSolver.driveSearch(
    params: BacktrackParams,
    sink: SolveStatsSink? = null,
): Sequence<SearchOutcome> = driveSharedSearch(params, sink)

/**
 * Satisfaction path for a CP/theory search.
 *
 * This has no private DFS trail: [CpSearchComponent] and every supplied theory component are driven by
 * [com.eignex.klause.solver.search.SearchRun].
 */
private fun BacktrackSolver.driveSharedSearch(
    params: BacktrackParams,
    sink: SolveStatsSink? = null,
): Sequence<SearchOutcome> = sequence {
    val cp = CpSearchComponent(
        PropagationSession(
            problem,
            params.cancellation,
            params.propagationCancelFloor,
            nativeSat = params.nativeSat ?: true,
            pbLearning = params.pbLearning ?: true,
        ),
        branching = CpBranching.None,
    )
    val completion = BacktrackCompletion.of(problem, cp, params)
    val brancher = BacktrackBrancher(cp.session, params, sink)
    val components = ArrayList<SearchComponent>()
    components += cp
    components += brancher
    completion.addTo(components)
    components += params.componentFactory?.invoke().orEmpty()
    val session = SearchComponentSet(components).session(cancellation = params.cancellation)
    val seeded = cp.session.seed(params.assumptions)
    cp.rebase()
    if (seeded is PropagationResult.Unsat || cp.session.isUnsatAtRoot) {
        val core = (problem.baked as? PropagationResult.Unsat)?.let(this@driveSharedSearch::coreOf)
        val touched = (seeded as? PropagationResult.Unsat)?.conflictLevels?.let { levels ->
            touchedToArray(IntHashSet().also { touched -> levels.forEach(touched::add) })
        } ?: EmptyIntArray
        yield(SearchOutcome.Exhausted(core, touched))
        return@sequence
    }
    when (session.initialize()) {
        com.eignex.klause.solver.search.ComponentResult.Consistent -> Unit
        is com.eignex.klause.solver.search.ComponentResult.Conflict -> {
            yield(SearchOutcome.Exhausted())
            return@sequence
        }

        com.eignex.klause.solver.search.ComponentResult.Indeterminate -> {
            yield(SearchOutcome.BudgetCapped)
            return@sequence
        }
    }
    val run = session.openRun(
        problem.numBoolVars,
        SearchSolveParams(
            maxDecisions = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE),
            restart = RestartSchedule.from(params),
        ),
        BooleanBranching.None,
        SearchDecisionBudget {
            params.nodeBudget?.let { budget ->
                budget.spend()
                !budget.exhausted()
            } ?: true
        },
        brancher,
        SearchModelContinuation.BlockAtRoot,
    )
    while (true) {
        when (val event = run.next()) {
            is SearchRunEvent.Satisfied -> {
                val sample = completion.sample(event.model, cp)
                brancher.onSolution(sample)
                yield(SearchOutcome.Found(sample))
            }

            SearchRunEvent.Exhausted -> {
                yield(SearchOutcome.Exhausted())
                return@sequence
            }

            SearchRunEvent.Indeterminate.Component -> {
                yield(SearchOutcome.Exhausted(indeterminate = true))
                return@sequence
            }

            SearchRunEvent.Indeterminate.Budget, SearchRunEvent.Indeterminate.Cancelled -> {
                yield(SearchOutcome.BudgetCapped)
                return@sequence
            }
        }
    }
}

private class BacktrackBrancher(
    private val session: PropagationSession,
    params: BacktrackParams,
    private val sink: SolveStatsSink?,
) : SearchBrancher, SearchRunObserver {
    private val variables = params.variableSelector.fresh()
    private val values = params.valueSelector.fresh()
    private val phase = PhaseSaving(session.problem.numBoolVars, session.problem.numIntVars, params)
    private val restart = RestartSchedule.from(params)
    private val rng = Random(params.randomSeed ?: Random.Default.nextLong())
    private val onEvent = params.onEvent
    private var restartCount = 0L

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        val variable = variables.pick(session, rng) ?: return null
        if (restart.phaseMode() != PhaseMode.UNMANAGED) phase.setManagedMode(restart.phaseMode())
        val ordered = phase.applyPhase(variable, values.values(session, variable, rng), rng)
        return when (variable) {
            is VarRef.Bool -> ordered.map { value ->
                require(value == 0L || value == 1L) { "Boolean selector produced $value" }
                SearchDecision.Bool(Lit.make(variable.varId, value != 0L))
            }.toList()

            is VarRef.IntVar -> intAlternatives(variable, ordered.firstOrNull() ?: return null)
        }
    }

    private fun intAlternatives(variable: VarRef.IntVar, preferred: Long): List<SearchDecision> {
        val domain = session.intDomain(variable.varId)
        check(domain.min < domain.max) { "selector chose fixed integer ${variable.varId}" }
        val split = when {
            !domain.enumerable && preferred > domain.min && preferred < domain.max -> preferred
            !domain.enumerable -> boundsMidpoint(domain)
            preferred >= domain.max -> domain.max - 1
            else -> maxOf(preferred, domain.min)
        }
        val lower = SearchDecision.IntAtMost(variable.varId, split)
        val upper = SearchDecision.IntAtLeast(variable.varId, split + 1)
        return if (preferred <= split) listOf(lower, upper) else listOf(upper, lower)
    }

    override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
        val (variable, value) = decision.asSelectorDecision() ?: return
        variables.onCommit(variable)
        values.onCommit(variable, value)
        phase.capture(variable, session)
        phase.captureTargetIfDeeper(session, decisionLevel)
        sink?.search?.observeNode(decisionLevel)
    }

    override fun onConflict(decision: SearchDecision?) {
        sink?.search?.observeFail()
        val (variable, value) = decision?.asSelectorDecision() ?: return
        variables.onConflict(variable)
        values.onConflict(variable, value)
        phase.onConflictTick()
    }

    override fun onRestart() {
        variables.onRestart()
        values.onRestart()
        sink?.search?.observeRestart()
        onEvent?.invoke(SearchEvent.Restart(++restartCount, 0L))
    }

    fun onSolution(sample: Sample) {
        variables.onSolution(sample)
        values.onSolution(sample)
        phase.onSolution(sample)
    }
}

private fun SearchDecision.asSelectorDecision(): Pair<VarRef, Long>? = when (this) {
    is SearchDecision.Bool -> VarRef.Bool(literal ushr 1) to if (literal and 1 == 0) 1L else 0L
    is SearchDecision.IntAtMost -> VarRef.IntVar(variable) to upper
    is SearchDecision.IntAtLeast -> VarRef.IntVar(variable) to lower
    is SearchDecision.IntEqual -> VarRef.IntVar(variable) to value
    is SearchDecision.Theory -> null
}

private sealed interface BacktrackCompletion {
    fun addTo(components: MutableList<SearchComponent>)

    fun sample(model: com.eignex.klause.solver.search.AssembledSearchModel, cp: CpSearchComponent): Sample

    data object Discrete : BacktrackCompletion {
        override fun addTo(components: MutableList<SearchComponent>) = Unit

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = checkNotNull(model.valueOf(cp))
    }

    class ResidualReal(
        private val component: ResidualRealComponent,
    ) : BacktrackCompletion {
        override fun addTo(components: MutableList<SearchComponent>) {
            components += component
        }

        override fun sample(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            cp: CpSearchComponent,
        ): Sample = component.sample()
    }

    companion object {
        fun of(problem: Problem, cp: CpSearchComponent, params: BacktrackParams): BacktrackCompletion =
            if (problem.numRealVars == 0) Discrete else ResidualReal(ResidualRealComponent(problem, cp, params))
    }
}

private class ResidualRealComponent(
    private val problem: Problem,
    private val cp: CpSearchComponent,
    params: BacktrackParams,
) : SearchComponent {
    private val engine = LpEngine(
        problem,
        LinearObjective(intCoefficients = LongArray(problem.numIntVars)),
        LpParams(
            lpPlan = params.lpPlan.copy(bounding = true, learn = true, realResidual = true),
            lpConfig = params.lpConfig,
            cancellation = params.cancellation,
            solveBudgetMillis = params.solveBudgetMillis,
            randomSeed = params.randomSeed,
        ),
        SolveStatsSink(backend = "backtrack"),
    )
    private var completed: Sample? = null

    override fun check(context: SearchContext): ComponentCheck {
        completed = null
        if (context.cancelled()) return ComponentCheck.Indeterminate
        val result = engine.leafCertify(cp.session)
        return when (result.verdict) {
            LpVerdict.OPTIMAL -> ComponentCheck.Feasible.also {
                completed = Sample(
                    BooleanArray(problem.numBoolVars) { cp.session.boolValue(it) ?: false },
                    LongArray(problem.numIntVars) { cp.session.intDomain(it).min },
                    result.reals,
                )
            }
            LpVerdict.INFEASIBLE -> ComponentCheck.Infeasible()
            LpVerdict.INDETERMINATE -> ComponentCheck.Indeterminate
        }
    }

    override fun retract(decisionLevel: Int) {
        completed = null
    }

    fun sample(): Sample = requireNotNull(completed)
}

internal fun BacktrackSolver.makeNode(varRef: VarRef, values: Sequence<Long>): TrailNode = when (varRef) {
    is VarRef.Bool -> BoolNode(varRef, values)
    is VarRef.IntVar -> IntNode(varRef, values)
}
