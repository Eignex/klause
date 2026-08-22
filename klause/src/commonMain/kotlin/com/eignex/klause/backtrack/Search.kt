package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

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
 * caller's [ValueSelector] at node creation; [applyNext] pulls the next value, pushes
 * it into the session, and reports back both the value (so the engine can fire
 * heuristic callbacks scoped to the attempted pair) and the session's propagation
 * response. Returns `null` when the value iterator is exhausted.
 */
internal sealed interface TrailNode {
    val varRef: VarRef
    fun applyNext(session: PropagationSession): ApplyOutcome?
}

/** What [TrailNode.applyNext] returns: the actual value pushed (bools encoded as 0/1
 *  so the value heuristic callbacks see the original heuristic-emitted form) plus the
 *  session's [PropagationResult]. */
internal data class ApplyOutcome(val value: Long, val result: PropagationResult)

internal class BoolNode(override val varRef: VarRef.Bool, valueSeq: Sequence<Long>) : TrailNode {
    private val iter = valueSeq.iterator()
    override fun applyNext(session: PropagationSession): ApplyOutcome? {
        if (!iter.hasNext()) return null
        val v = iter.next()
        return ApplyOutcome(v, session.pinBool(varRef.varId, v != 0L))
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

    override fun applyNext(session: PropagationSession): ApplyOutcome? {
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
                ApplyOutcome(splitLo, session.pinIntAtMost(vid, splitLo))
            } else {
                ApplyOutcome(splitHi, session.pinIntAtLeast(vid, splitHi))
            }

            1 -> if (lowerFirst) {
                ApplyOutcome(splitHi, session.pinIntAtLeast(vid, splitHi))
            } else {
                ApplyOutcome(splitLo, session.pinIntAtMost(vid, splitLo))
            }

            else -> null
        }
    }
}

/**
 * The satisfaction [SearchPolicy]: pure complete DFS with no LP bounding and no incumbent. Every
 * feasible leaf is surfaced; the selectors' `onSolution` hooks fire in [DfsEngine] before the leaf is
 * surfaced, so this only returns the sample. On a budget exit the trailing glue clauses are published
 * for cross-arm import.
 */
private class SatPolicy(
    private val params: BacktrackParams,
    private val problem: Problem,
    private val completeLeaf: ((Sample, PropagationSession) -> Sample?)? = null,
) : SearchPolicy<Sample> {
    /** Set when a leaf's residual continuous LP was neither certified feasible nor infeasible, so the
     *  final Exhausted verdict must degrade to `unknown` rather than UNSAT. */
    var sawIndeterminate = false
        private set

    // Real rows have no propagator, so on a model with continuous columns nothing between the root and a
    // full-assignment leaf ever refutes a partial assignment that already activates an infeasible set of
    // real rows — the search would enumerate the whole Boolean space one expensive leaf LP at a time. Run
    // the certified LP-infeasibility prune at search nodes instead, with Farkas-clause learning: the
    // certificate cites the reified rows' premise literals, so one refutation prunes every assignment
    // sharing them and the engine backjumps past the dead region.
    private val lpEngine: LpEngine? = if (problem.numRealVars > 0) {
        LpEngine(
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
    } else {
        null
    }

    override val pruneIf: ((PropagationSession) -> Boolean)? = lpEngine?.let { eng ->
        // An infinite incumbent never dominates, so the LP arm prunes only on certified infeasibility.
        { session -> eng.pruneNode(session, Double.POSITIVE_INFINITY, -1, true) }
    }

    override val pruneLearned: (() -> Learned?)? = lpEngine?.let { eng -> { eng.lastBackjump() } }

    /** Non-asserting theory lemmas pool up in the engine; register them permanently at each restart —
     *  without this the satisfaction path silently drops every lemma whose 1UIP analysis fails. A root
     *  contradiction while registering proves the whole space empty. */
    override fun drainLpNogoodsAtRestart(session: PropagationSession): Boolean {
        val pool = lpEngine?.lpNogoods ?: return false
        for (nogood in pool.drain()) {
            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
            if (res is PropagationResult.Unsat) return true
            params.clauseExchange?.publishGlobal(session.asSharedClause(nogood, nogood.size))
        }
        return false
    }

    override fun cancelled(): Boolean = params.cancellation()

    /**
     * The leaf-exact contract, which is general even though the LP is its only client today.
     *
     * A leaf pins every search variable, so whatever those pins do not settle has to be *decided* here,
     * exactly, by whoever owns it. Three outcomes and no fourth: decided feasible completes the sample,
     * decided infeasible rejects it, and undecided rejects it *and* records that the tree was never
     * proved empty — [sawIndeterminate] is what turns the terminal verdict into unknown instead of an
     * unsound UNSAT. A decider that cannot answer exactly must take the third branch rather than guess.
     *
     * That contract is what lets a variable be handed to a theory instead of being searched: the theory
     * owes an exact answer once the search variables are pinned, or it owes an admission that it has none.
     */
    override fun onLeaf(snap: Sample, session: PropagationSession): Sample? {
        completeLeaf?.let { return it(snap, session) }
        // With LP-only continuous variables, a CP-consistent leaf is a solution only if the residual real
        // LP is feasible — the real rows have no propagator, so CP alone has not enforced them. On success
        // the LP's continuous values complete the assignment into a full solution. The engine-owned check
        // builds from the live session, so a refuted leaf also derives its premise-cited theory lemma.
        if (problem.numRealVars == 0) return snap
        val res = lpEngine?.leafCertify(session)
            ?: leafRealFeasibility(problem, objective = null, sample = snap, cancellation = params.cancellation)
        return when (res.verdict) {
            LpVerdict.OPTIMAL -> snap.copy(reals = res.reals)

            LpVerdict.INFEASIBLE -> null

            // reals cannot complete this assignment — reject and backtrack
            LpVerdict.INDETERMINATE -> {
                sawIndeterminate = true
                null
            }
        }
    }

    override fun onBudgetExit(session: PropagationSession) {
        params.clauseExchange?.onSearchEnd(session)
    }
}

/**
 * Lazy stream of search outcomes for the satisfaction path (solve / enumerate / samples). A thin
 * adapter over the shared [DfsEngine]: each call resumes the DFS from where it last yielded, mapping the
 * engine's [EngineEvent]s to [SearchOutcome]s.
 */
internal fun BacktrackSolver.driveSearch(
    params: BacktrackParams,
    sink: SolveStatsSink? = null,
    completeLeaf: ((Sample, PropagationSession) -> Sample?)? = null,
): Sequence<SearchOutcome> = sequence {
    // Theory lemmas from the residual real LP register at restarts; a real-column model with no
    // restart policy would pool them forever, so give it the Luby default.
    val runParams = if (this@driveSearch.problem.numRealVars > 0 && params.lubyRestartBase == null) {
        params.copy(lubyRestartBase = SAT_REAL_LUBY_BASE)
    } else {
        params
    }
    val policy = SatPolicy(runParams, this@driveSearch.problem, completeLeaf)
    val engine = DfsEngine(this@driveSearch, runParams, sink, policy)
    while (true) {
        when (val e = engine.runUntilEvent()) {
            is EngineEvent.Solution -> yield(SearchOutcome.Found(e.payload))

            is EngineEvent.Exhausted -> {
                yield(SearchOutcome.Exhausted(e.core, e.touched, policy.sawIndeterminate))
                return@sequence
            }

            EngineEvent.BudgetCapped, EngineEvent.Cancelled -> {
                yield(SearchOutcome.BudgetCapped)
                return@sequence
            }
        }
    }
}

internal fun BacktrackSolver.makeNode(varRef: VarRef, values: Sequence<Long>): TrailNode = when (varRef) {
    is VarRef.Bool -> BoolNode(varRef, values)
    is VarRef.IntVar -> IntNode(varRef, values)
}

/** Luby restart unit for satisfaction search over real columns (the lemma-registration cadence). */
private const val SAT_REAL_LUBY_BASE = 256L
