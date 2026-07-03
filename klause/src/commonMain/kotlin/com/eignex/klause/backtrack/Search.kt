package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.lp.LpNogoodPool
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.random.Random

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
internal fun BacktrackSolver.touchedToArray(touched: HashSet<Int>?): IntArray {
    if (touched == null || touched.isEmpty()) return IntArray(0)
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
    data class Exhausted(val core: UnsatCore? = null, val touchedAssumptionLevels: IntArray = IntArray(0)) :
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
internal data class ApplyOutcome(val value: Int, val result: PropagationResult)

internal class BoolNode(override val varRef: VarRef.Bool, valueSeq: Sequence<Int>) : TrailNode {
    private val iter = valueSeq.iterator()
    override fun applyNext(session: PropagationSession): ApplyOutcome? {
        if (!iter.hasNext()) return null
        val v = iter.next()
        return ApplyOutcome(v, session.pinBool(varRef.varId, v != 0))
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
internal class IntNode(override val varRef: VarRef.IntVar, valueSeq: Sequence<Int>) : TrailNode {
    private val preferred: Int = valueSeq.firstOrNull() ?: 0
    private var step = 0
    private var split = 0
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
 * Lazy stream of search outcomes. Each call resumes the DFS from where it last yielded.
 * Engine invariant: `trail` lists nodes whose currently-active value is reflected in
 * `session`'s pushed pins. On Unsat, `session` self-reverts — the engine doesn't
 * popLast in that case.
 */
internal fun BacktrackSolver.driveSearch(
    params: BacktrackParams,
    pruneIf: ((PropagationSession) -> Boolean)? = null,
    // Immediate LP backjump (#280): after [pruneIf] prunes a node, this returns the asserting
    // 1UIP clause derived from the node's LP infeasibility (or null). When present, [advance]
    // backjumps and learns instead of popping one level chronologically.
    pruneLearned: (() -> Learned?)? = null,
    sink: SolveStatsSink? = null,
    // Objective-bound propagation (single-variable objectives only). When [objectiveVar]
    // is set, the engine pushes each incumbent's bound onto that variable at the root —
    // `objVar ≤ best-1` for minimise ([objectiveAscending]) or `objVar ≥ best+1` for
    // maximise — as a permanent unit that propagates through the constraint defining the
    // objective. [objectiveBest] returns the objective variable's value in the current
    // incumbent, or null before one is found. Strictly stronger than the passive
    // [pruneIf] lower-bound check, and it bounds non-linear-defined objectives too.
    objectiveVar: Int = -1,
    objectiveAscending: Boolean = true,
    objectiveBest: () -> Int? = { null },
    // LP-learned Farkas nogoods (#247) pending registration; drained at each restart while the
    // trail is at root, so their bound atoms are no longer all-false. Null when learning is off.
    lpNogoods: LpNogoodPool? = null,
): Sequence<SearchOutcome> = sequence {
    if (problem.baked is PropagationResult.Unsat) {
        yield(SearchOutcome.Exhausted(coreOf(problem.baked)))
        return@sequence
    }
    val session = PropagationSession(problem)
    // Builder helpers below capture the mutable search state. They never `yield`
    // themselves — a `yield` needs the `sequence { }` builder's `SequenceScope`
    // receiver, which a plain local function does not have — so each exhaustion site
    // keeps its own `yield(exhausted())` while the [SearchOutcome] construction and the
    // shared bookkeeping move here.
    // Bridge backtrack-time unassigns to a heuristic that removes assigned vars from its
    // order structure on pick (VSIDS): decode the combined index and re-offer the var.
    // Only wired when the heuristic opts in, so other heuristics pay no per-revert cost.
    if (params.variableSelector.tracksUnassign) {
        val heuristic = params.variableSelector
        val numBool = problem.numBoolVars
        session.unassignListener = { enc ->
            heuristic.onUnassign(if (enc < numBool) VarRef.Bool(enc) else VarRef.IntVar(enc - numBool))
        }
    }
    // Number of decision levels seed pushes uses — bool pins first then int pins.
    // Decision levels 1..numSeed correspond to assumptions; levels > numSeed are
    // post-seed DFS decisions.
    val numSeed = params.assumptions.boolKeys.size + params.assumptions.intKeys.size
    val touchedSeedLevels = if (numSeed > 0) HashSet<Int>() else null

    // Union the seed-level decision levels referenced by a conflict into the touched set.
    fun recordTouchedSeedLevels(levels: IntArray) {
        if (touchedSeedLevels == null) return
        for (l in levels) if (l in 1..numSeed) touchedSeedLevels.add(l)
    }

    // The DFS-walk exhaustion outcome (no root core; only the touched seed levels carry over).
    fun exhausted(): SearchOutcome.Exhausted =
        SearchOutcome.Exhausted(touchedAssumptionLevels = touchedToArray(touchedSeedLevels))
    val seedResult = session.seed(params.assumptions)
    if (seedResult is PropagationResult.Unsat) {
        recordTouchedSeedLevels(seedResult.conflictLevels)
        yield(SearchOutcome.Exhausted(coreOf(seedResult), touchedToArray(touchedSeedLevels)))
        return@sequence
    }
    // Phase-saving + target-phasing (#204): saved polarities, the deepest conflict-free target phase,
    // and the rephase schedule — all persisting across restarts. See [PhaseSaving].
    val phase = PhaseSaving(problem.numBoolVars, problem.numIntVars, params)
    val onConflictTick: () -> Unit = phase::onConflictTick

    // Pop every decision frame, reverting the session in lockstep, back to the post-seed root.
    fun popTrailToRoot(trail: MutableList<TrailNode>) {
        while (trail.isNotEmpty()) {
            session.popLast()
            trail.removeAt(trail.size - 1)
        }
    }

    val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
    val rng = Random(baseSeed)
    // The effective budget tightens the two limits — whichever is smaller wins. This
    // lets a uniform `maxInstructions` work across backends without removing the
    // backend-specific `maxDecisions` knob.
    var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

    // Failsafe against repeat-learning livelock: count identical re-derivations per
    // clause (order-free literal-set hash). Healthy re-learning happens after
    // forgetting or restarts, but an unbounded streak means the backjump + assert
    // cycle is not progressing — past the threshold those conflicts are handled
    // chronologically. The count surfaces as the `relearned` solve stat under -s.
    val relearnCounts = MutableLongIntMap()
    val relearnTripped: (Learned) -> Boolean = { learned ->
        var h = 0L
        for (lit in learned.literals) h += splitmix64(lit.toLong())
        val n = relearnCounts.addTo(h, 1)
        if (n > 1) sink?.observeRelearn()
        n > RELEARN_FALLBACK_THRESHOLD
    }

    // One pin attempt against [session], wiring the budget probe/decrement and the conflict
    // callbacks. The caller folds `decisionsLeft`'s drop into its per-run decision count.
    fun runAdvance(node: TrailNode): AdvanceOutcome = advance(
        node,
        session,
        params,
        pruneIf,
        { decisionsLeft },
        { decisionsLeft-- },
        sink,
        relearnTripped,
        onConflictTick,
        pruneLearned,
    )

    // Outer restart loop. Each iteration is one Luby-bounded DFS run from the root.
    // When `lubyRestartBase` is null the loop runs exactly once with infinite per-run
    // budget — same as the pre-restart behaviour.
    // Assignment of the most recently yielded leaf, pending a blocking nogood. Without it
    // the DFS only steps past a found solution chronologically, and a later backjump that
    // pops those frames re-opens the leaf — the search can then revisit and re-yield it,
    // potentially forever. The nogood spans the full assignment (not the decisions) so the
    // same solution reached through a different decision order is excluded too. It is
    // registered at the root on the next backtrack (or restart) and kept permanently.
    var pendingBlock: Sample? = null
    // Objective-bound propagation: assert the incumbent bound on the objective variable
    // at the root, once per improving value. Returns true iff that makes the root
    // infeasible — the remaining objective space is empty, so the search is exhausted
    // (optimum proven). Must be called only when the session is at the root.
    var lastObjBoundAsserted: Int? = null
    fun assertObjectiveBoundAtRoot(): Boolean {
        if (objectiveVar < 0) return false
        val best = objectiveBest() ?: return false
        val threshold = if (objectiveAscending) best - 1 else best + 1
        if (threshold == lastObjBoundAsserted) return false
        lastObjBoundAsserted = threshold
        return session.assertObjectiveBound(objectiveVar, threshold, atMost = objectiveAscending) is
            PropagationResult.Unsat
    }
    // Glucose-style adaptive restart policy (#198). When enabled it replaces the Luby
    // budget: restarts fire on learned-clause quality (recent LBD vs the long-run average),
    // with trail-size blocking. `restartRequested` is set by the conflict handlers and
    // consumed at the top of the inner loop; the policy's own stats persist across restarts.
    val glucose: GlucoseRestart? = if (params.adaptiveRestart) GlucoseRestart() else null
    var restartRequested = false
    // Vivification (#203) walks the learned DB round-robin across restarts; the cursor
    // persists between restart passes so successive passes cover the whole database.
    val vivifyEnabled = params.vivification && params.assumptions.isEmpty
    var vivifyCursor = 0
    var lubyIdx = 1L
    // Cross-arm clause exchange (portfolio): import nogoods learned by prior segments/arms before
    // the first DFS run, so a re-scheduled backtrack arm starts warm instead of cold-relearning
    // every slice (#381). The session sits at the post-seed root here — the same state a restart
    // pops back to — so imported literals are free and register without an immediate unit/conflict.
    params.clauseExchange?.onSearchStart(session)
    outer@ while (true) {
        val perRunBudget: Long = if (glucose != null) {
            Long.MAX_VALUE // adaptive restarts drive the schedule; the Luby budget is off
        } else {
            params.lubyRestartBase?.let { base ->
                // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
                val limit = lubyN(lubyIdx)
                if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
            } ?: Long.MAX_VALUE
        }
        var decisionsThisRun = 0L

        val trail: MutableList<TrailNode> = ArrayList()
        var descend = true
        // Time-adaptive deadline polling (mirrors ResumableMinimize); see [DeadlinePoller].
        val poller = DeadlinePoller()

        inner@ while (true) {
            if (poller.due()) {
                if (params.cancellation()) {
                    // Slice truncated: publish this segment's trailing glue clauses so the next
                    // segment (this arm or a sibling) imports them at its start (#381).
                    params.clauseExchange?.onSearchEnd(session)
                    yield(SearchOutcome.BudgetCapped)
                    return@sequence
                }
                poller.rearm()
            }
            // Restart trigger: Luby budget hit, or the adaptive policy asked to re-pick.
            // Either way pop back to root and restart.
            if (decisionsThisRun >= perRunBudget || restartRequested) {
                restartRequested = false
                popTrailToRoot(trail)
                val restartBlock = pendingBlock
                if (restartBlock != null) {
                    pendingBlock = null
                    if (restartBlock.bools.isNotEmpty() || restartBlock.ints.isNotEmpty()) {
                        // All decisions are popped; register the nogood so the restarted run
                        // cannot re-yield the same leaf. A root-level contradiction here
                        // proves the remaining space empty.
                        val nogood = session.assignmentNogood(restartBlock.bools, restartBlock.ints)
                        val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                        if (res is PropagationResult.Unsat) {
                            yield(exhausted())
                            return@sequence
                        }
                    }
                }
                // LP-learned Farkas nogoods (#247): the trail is at root, so each clause's bound
                // atoms are free again. Register them permanently; a root contradiction proves the
                // whole space empty. Globally valid (implied by the original constraints).
                if (lpNogoods != null) {
                    val drained = lpNogoods.drain()
                    for (nogood in drained) {
                        val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                        if (res is PropagationResult.Unsat) {
                            yield(exhausted())
                            return@sequence
                        }
                    }
                }
                // Cross-arm clause exchange (portfolio): at root, import nogoods other arms
                // learned and export this arm's new glue clauses. Imports register without
                // immediate propagation (their literals are free at root) — a root contradiction
                // surfaces on the next fixpoint, not here. No-op when not in a sharing portfolio.
                params.clauseExchange?.onRestart(session)
                if (assertObjectiveBoundAtRoot()) {
                    yield(exhausted())
                    return@sequence
                }
                params.variableSelector.onRestart()
                params.valueSelector.onRestart()
                // LCG learned-clause forgetting: at each restart, prune the database
                // when over [maxLearnedClauses]. Glue clauses (LBD ≤ glueThreshold)
                // are always retained; among the rest, the lowest-LBD entries are
                // kept up to the cap.
                forgetIfOverCap(session, params)
                // Vivification inprocessing: the trail is at root here, so a bounded slice
                // of the learned DB can be strengthened against clean assumptions (#203).
                if (vivifyEnabled) vivifyCursor = vivify(session, params, vivifyCursor)
                lubyIdx++
                sink?.observeRestart()
                params.onEvent?.invoke(SearchEvent.Restart(lubyIdx - 1, decisionsThisRun))
                continue@outer
            }
            if (descend) {
                val varRef = params.variableSelector.pick(session, rng)
                if (varRef == null) {
                    val snap = snapshotAssignment(session)
                    // Notify heuristics first so solution-guided variants can snapshot
                    // the incumbent before the engine continues with the next yield.
                    params.variableSelector.onSolution(snap)
                    params.valueSelector.onSolution(snap)
                    pendingBlock = snap
                    yield(SearchOutcome.Found(snap))
                    descend = false
                    continue@inner
                }
                val values = params.valueSelector.values(session, varRef, rng)
                val phased = phase.applyPhase(varRef, values, rng)
                val node = makeNode(varRef, phased)
                val decsBefore = decisionsLeft
                val out = runAdvance(node)
                decisionsThisRun += decsBefore - decisionsLeft
                when (out) {
                    AdvanceOutcome.Success -> {
                        phase.capture(varRef, session)
                        trail.add(node)
                        sink?.observeNode(trail.size)
                        phase.captureTargetIfDeeper(session, trail.size)
                    }

                    AdvanceOutcome.Exhausted -> {
                        descend = false
                        continue@inner
                    }

                    AdvanceOutcome.BudgetCapped -> {
                        params.clauseExchange?.onSearchEnd(session)
                        yield(SearchOutcome.BudgetCapped)
                        return@sequence
                    }

                    is AdvanceOutcome.Backjump -> {
                        recordTouchedSeedLevels(out.learned.decisionLevels)
                        // Feed the learned clause's LBD and the current depth to the
                        // adaptive restart policy (trail size == decision level here; the
                        // failed pin was self-reverted by the session).
                        if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                            restartRequested = true
                        }
                        // Execute the backjump + learn sequence. On cascading conflict
                        // during assertion, recurse.
                        val term = backjumpAndLearn(
                            out.learned, trail, session, params, alignFirst = false,
                        )
                        when (term) {
                            BackjumpTerm.Resume -> {
                                descend = true
                                continue@inner
                            }

                            BackjumpTerm.Exhausted -> {
                                yield(exhausted())
                                return@sequence
                            }

                            BackjumpTerm.Stuck -> {
                                descend = false
                                continue@inner
                            }
                        }
                    }
                }
            } else {
                val rootBlock = pendingBlock
                if (rootBlock != null) {
                    // Apply the pending blocking nogood at the root, where it can neither
                    // conflict nor assert mid-trail; a root contradiction proves the
                    // remaining space empty.
                    pendingBlock = null
                    popTrailToRoot(trail)
                    val nogood = session.assignmentNogood(rootBlock.bools, rootBlock.ints)
                    if (nogood.isNotEmpty()) {
                        val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                        if (res is PropagationResult.Unsat) {
                            yield(exhausted())
                            return@sequence
                        }
                    }
                    if (assertObjectiveBoundAtRoot()) {
                        yield(exhausted())
                        return@sequence
                    }
                    descend = true
                    continue@inner
                }
                if (trail.isEmpty()) {
                    yield(exhausted())
                    return@sequence
                }
                val top = trail.last()
                session.popLast()
                val decsBefore = decisionsLeft
                val out = runAdvance(top)
                decisionsThisRun += decsBefore - decisionsLeft
                when (out) {
                    AdvanceOutcome.Success -> {
                        phase.capture(top.varRef, session)
                        descend = true
                    }

                    AdvanceOutcome.Exhausted -> {
                        trail.removeAt(trail.size - 1)
                    }

                    AdvanceOutcome.BudgetCapped -> {
                        params.clauseExchange?.onSearchEnd(session)
                        yield(SearchOutcome.BudgetCapped)
                        return@sequence
                    }

                    is AdvanceOutcome.Backjump -> {
                        recordTouchedSeedLevels(out.learned.decisionLevels)
                        if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                            restartRequested = true
                        }
                        // Else-path: session has been popped below trail.last; align
                        // first (trail.removeAt) then proceed to backjump + learn.
                        val term = backjumpAndLearn(
                            out.learned, trail, session, params, alignFirst = true,
                        )
                        when (term) {
                            BackjumpTerm.Resume -> {
                                descend = true
                                continue@inner
                            }

                            BackjumpTerm.Exhausted -> {
                                yield(exhausted())
                                return@sequence
                            }

                            BackjumpTerm.Stuck -> {
                                descend = false
                                continue@inner
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun BacktrackSolver.makeNode(varRef: VarRef, values: Sequence<Int>): TrailNode = when (varRef) {
    is VarRef.Bool -> BoolNode(varRef, values)
    is VarRef.IntVar -> IntNode(varRef, values)
}

/**
 * Luby sequence (Luby-Sinclair-Zuckerman 1993). Standard CDCL restart schedule:
 * `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, ...`. Closed form:
 * `lubyN(i) = 2^(k-1)` when `i = 2^k − 1` (i.e. one less than a power of two);
 * otherwise `lubyN(i − 2^(k-1) + 1)` where `k = ⌊log₂(i)⌋ + 1`.
 */
internal fun BacktrackSolver.lubyN(idxIn: Long): Long {
    var i = idxIn
    var k = 1
    // Find smallest k such that 2^k > i.
    while ((1L shl k) <= i) k++
    // Equivalent to the textbook recurrence; iteratively unwound.
    while (true) {
        val pow = 1L shl (k - 1)
        if (i == (pow shl 1) - 1) return pow
        // Otherwise i < (pow << 1) - 1; recurse on (i - pow + 1).
        i = i - pow + 1
        k = 1
        while ((1L shl k) <= i) k++
    }
}
