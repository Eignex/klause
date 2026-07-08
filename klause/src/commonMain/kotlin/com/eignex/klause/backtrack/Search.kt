package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntHashSet

// ---------------------------------------------------------------------------------------
// Engine.
// ---------------------------------------------------------------------------------------

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
    data class Exhausted(val core: UnsatCore? = null, val touchedAssumptionLevels: IntArray = EmptyIntArray) :
        SearchOutcome
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
    private var split = 0L
    private var lowerFirst = true
    private var resolved = false

    override fun applyNext(session: PropagationSession): ApplyOutcome? {
        if (!resolved) {
            val d = session.intDomain(varRef.varId)
            split = if (preferred >= d.max) d.max - 1 else maxOf(preferred, d.min)
            lowerFirst = preferred <= split
            resolved = true
        }
        val vid = varRef.varId
        return when (step++) {
            0 -> if (lowerFirst) {
                ApplyOutcome(split, session.pinIntAtMost(vid, split))
            } else {
                ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
            }

            1 -> if (lowerFirst) {
                ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
            } else {
                ApplyOutcome(split, session.pinIntAtMost(vid, split))
            }

            else -> null
        }
    }
}

/**
 * The satisfaction [SearchPolicy]: pure complete DFS with no LP bounding and no incumbent. Every
 * feasible leaf is surfaced; the selectors' `onSolution` hooks fire in [DfsEngine] before the leaf is
 * surfaced, so this only returns the sample. On a budget exit the trailing glue clauses are published
 * for cross-arm import (#381).
 */
private class SatPolicy(private val params: BacktrackParams) : SearchPolicy<Sample> {
    override fun cancelled(): Boolean = params.cancellation()

    override fun onLeaf(snap: Sample): Sample = snap

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
): Sequence<SearchOutcome> = sequence {
    val engine = DfsEngine(this@driveSearch, params, sink, SatPolicy(params))
    while (true) {
        when (val e = engine.runUntilEvent()) {
            is EngineEvent.Solution -> yield(SearchOutcome.Found(e.payload))

            is EngineEvent.Exhausted -> {
                yield(SearchOutcome.Exhausted(e.core, e.touched))
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
