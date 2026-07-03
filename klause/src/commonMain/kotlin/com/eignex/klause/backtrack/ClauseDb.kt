package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ClauseTier
import com.eignex.klause.propagation.ConflictAnalyzer
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * What [advance] reports back to the search loop. LCG-style non-chronological
 * backjump needs the target level threaded back to the outer loop, hence the
 * sealed type rather than a plain Boolean success / failure.
 */
internal sealed interface AdvanceOutcome {
    /** A value pinned cleanly; commit the node to the trail. */
    data object Success : AdvanceOutcome

    /** Node has no more values; chronological backtrack. */
    data object Exhausted : AdvanceOutcome

    /** Decision budget hit. */
    data object BudgetCapped : AdvanceOutcome

    /** Non-chronological backjump requested. After the engine pops trail to
     *  `learned.backjumpLevel`, it materialises `learned.literals` as a `Clause`,
     *  hands it to [PropagationSession.addLearnedClause], and resumes with the new
     *  clause now constraining future search and unit-propagating the asserting
     *  literal. */
    data class Backjump(val learned: Learned) : AdvanceOutcome
}

internal fun BacktrackSolver.advance(
    node: TrailNode,
    session: PropagationSession,
    params: BacktrackParams,
    pruneIf: ((PropagationSession) -> Boolean)?,
    decisionsRemaining: () -> Long,
    decrement: () -> Unit,
    sink: SolveStatsSink? = null,
    relearnTripped: ((Learned) -> Boolean)? = null,
    onConflictTick: (() -> Unit)? = null,
    pruneLearned: (() -> Learned?)? = null,
): AdvanceOutcome {
    while (true) {
        if (decisionsRemaining() <= 0) return AdvanceOutcome.BudgetCapped
        decrement()
        val propsBefore = session.propagationCount
        val outcome = node.applyNext(session) ?: return AdvanceOutcome.Exhausted
        // Count every factor-forced assignment this pin triggered — including the
        // propagation done on the way to a conflict (Unsat returns below).
        sink?.observePropagation(session.propagationCount - propsBefore)
        val r = outcome.result
        if (r is PropagationResult.Unsat) {
            // Every conflict is a failed node — count it here, the one point all
            // conflicts funnel through, so both the satisfy (driveSearch) and
            // branch-and-bound (ResumableMinimize) loops report failures regardless
            // of whether the conflict backjumps or falls through to chronological
            // within-node value enumeration (#509).
            sink?.observeFail()
            onConflictTick?.invoke()
            // Forward the full conflict reason record so activity-, weight-, and
            // factor-driven heuristics (VSIDS, dom/wdeg) all see exactly what they
            // need without further plumbing.
            params.variableSelector.onConflict(node.varRef, r)
            params.valueSelector.onConflict(node.varRef, outcome.value)
            // CDB: if the analyzer produced a 1UIP clause with a non-chronological
            // backjump target, signal it up. The engine pops to the backjump level and
            // then persists the clause via [PropagationSession.addLearnedClause] (see
            // [backjumpAndLearn]), so the learned nogood both forces its asserting
            // literal now and constrains all future propagation — not just the one-shot
            // jump-distance prune.
            val learned = r.learnedClause as? ConflictAnalyzer.AnalysisResult.Learned
            // Only take the non-chronological backjump when the clause is a proper
            // 1UIP (asserting) clause — popping to its backjump level then makes it
            // unit and forces the asserting literal. A non-asserting clause (e.g. the
            // two same-level bound atoms an int *equality* decision contributes, which
            // 1UIP cannot collapse) would never become unit, so asserting it is a no-op
            // and the search would re-make the same decision forever. Fall through to
            // chronological within-node value enumeration instead, which is complete.
            // Two guards before taking the backjump: a clause carrying an
            // already-true literal (a kept resolved-atom literal can be) is satisfied,
            // so the assert would be a no-op and the popped frames' untried values
            // lost for nothing; and a clause re-derived identically past the relearn
            // threshold signals a cycle the backjump isn't breaking. Either way the
            // conflict falls through to chronological within-node enumeration.
            if (learned != null &&
                learned.asserting &&
                learned.literals.none { session.litTruth(it) == true } &&
                relearnTripped?.invoke(learned) != true
            ) {
                sink?.observeLearn()
                return AdvanceOutcome.Backjump(learned)
            }
            // #588 diagnostic: classify why this conflict did NOT learn — the gate breakdown
            // tells us which barrier (no clause / non-asserting / already-true literal) is
            // responsible for the low asserting rate.
            when {
                learned == null -> sink?.observeCaNotApplicable()
                !learned.asserting -> sink?.observeCaNonAsserting()
                learned.literals.any { session.litTruth(it) == true } -> sink?.observeCaRejectedTrueLit()
            }
            continue
        }
        if (pruneIf != null && pruneIf(session)) {
            // A bound-pruned node is a failed node, same as a propagation conflict — count it so
            // the failure total matches solvers that post the objective bound as a constraint
            // (Gecode/Chuffed). This covers the dominant linear objective-bound prune, which has
            // no other counter; the lp/energetic/lagrangian sub-counters set inside pruneIf remain
            // a breakdown of part of this total (#509).
            sink?.observeFail()
            // Immediate LP backjump (#280): if the prune carried an asserting Farkas 1UIP clause,
            // convert this node into a non-chronological backjump-and-learn. Revert the current
            // pin first (the propagation-conflict path reaches the Backjump return with the failed
            // pin already self-reverted, so trail.size == decisionLevel; match that here), then
            // route through the same guards and handler as a propagation conflict.
            val lpLearned = pruneLearned?.invoke()
            if (lpLearned != null &&
                lpLearned.asserting &&
                lpLearned.literals.none { session.litTruth(it) == true } &&
                relearnTripped?.invoke(lpLearned) != true
            ) {
                sink?.observeLpBackjump()
                sink?.observeLearn()
                session.popLast()
                return AdvanceOutcome.Backjump(lpLearned)
            }
            session.popLast()
            continue
        }
        // ABS-style activity heuristics need the implied set from the just-completed
        // propagation step; only Implied carries those keys.
        if (r is PropagationResult.Implied) {
            params.variableSelector.onPropagation(r)
        }
        params.variableSelector.onCommit(node.varRef)
        params.valueSelector.onCommit(node.varRef, outcome.value)
        return AdvanceOutcome.Success
    }
}

/**
 * Apply the LCG forgetting policy on a Luby restart. No-op when
 * [BacktrackParams.maxLearnedClauses] is null or the learned database is already
 * under the cap. Otherwise: glue clauses (LBD ≤ [BacktrackParams.lbdGlueThreshold])
 * are kept, and among non-glue clauses we keep the lowest-LBD ones up to the
 * remaining cap. Implemented as: collect (index, lbd) pairs for non-glue clauses,
 * sort by LBD ascending, take the first `remaining` of them, plus all glue.
 */
internal fun BacktrackSolver.forgetIfOverCap(session: PropagationSession, params: BacktrackParams) {
    val cap = params.maxLearnedClauses ?: return
    val learnedSize = session.learnedClauseCount
    if (learnedSize <= cap) return
    if (params.tieredLearnedDb) {
        forgetTiered(session, params, cap, learnedSize)
        return
    }
    val glueThreshold = params.lbdGlueThreshold
    // Bucket non-glue clauses by LBD and pick the lowest LBDs up to the residual
    // capacity. We do this as: compute LBD per index, sort ascending, and define
    // `keep(i, lbd) = lbd <= glueThreshold || rank(i) < remaining`.
    val nonGlue = ArrayList<IntArray>(learnedSize) // [lbd, index] pairs
    for (i in 0 until learnedSize) {
        val lbd = session.learnedClauseLbd(i)
        if (lbd > glueThreshold && !session.learnedClausePermanent(i)) nonGlue.add(intArrayOf(lbd, i))
    }
    // If all are glue, nothing to forget.
    if (nonGlue.isEmpty()) return
    val glueCount = learnedSize - nonGlue.size
    val remainingCap = (cap - glueCount).coerceAtLeast(0)
    if (nonGlue.size <= remainingCap) return // already under cap
    nonGlue.sortBy { it[0] } // ascending LBD
    val kept = IntHashSet(remainingCap)
    for (k in 0 until remainingCap) kept.add(nonGlue[k][1])
    session.forgetLearnedClauses { idx, lbd ->
        lbd <= glueThreshold || session.learnedClausePermanent(idx) || idx in kept
    }
    val dropped = nonGlue.size - remainingCap
    params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped, dropped = dropped))
}

/**
 * Three-tier reduction policy (#201). Each learned clause is classified by LBD into a
 * permanent core (LBD ≤ [BacktrackParams.lbdGlueThreshold]), a mid tier
 * (LBD ≤ [BacktrackParams.midLbdThreshold]) and a local tier; tiers persist across
 * reductions. Reuse since the last reduction (the clause detected a conflict or forced a
 * unit, tracked by `PropagationState.noteLearnedUse`) drives promotion and demotion:
 *  - core: always kept;
 *  - mid: always kept this pass, but demoted to local when idle so it can be deleted later;
 *  - local: promoted to mid when reused, otherwise a deletion candidate.
 * Among the local deletion candidates the lowest-LBD ones are kept up to the residual cap
 * and the rest are dropped. Reuse flags are cleared for survivors so the next window
 * measures fresh activity.
 */
internal fun BacktrackSolver.forgetTiered(
    session: PropagationSession,
    params: BacktrackParams,
    cap: Int,
    learnedSize: Int,
) {
    val coreThreshold = params.lbdGlueThreshold
    val midThreshold = params.midLbdThreshold
    val locals = ArrayList<IntArray>(learnedSize) // [lbd, index] local deletion candidates
    for (i in 0 until learnedSize) {
        val lbd = session.learnedClauseLbd(i)
        val used = session.learnedClauseUsedSinceReduction(i)
        val entryTier = session.learnedClauseTier(i).let { t ->
            if (t != ClauseTier.UNSET) {
                t
            } else {
                when {
                    lbd <= coreThreshold -> ClauseTier.CORE
                    lbd <= midThreshold -> ClauseTier.MID
                    else -> ClauseTier.LOCAL
                }
            }
        }
        if (session.learnedClausePermanent(i)) {
            session.setLearnedClauseTier(i, entryTier) // permanent clauses are always kept
            continue
        }
        when (entryTier) {
            ClauseTier.CORE -> session.setLearnedClauseTier(i, ClauseTier.CORE)

            // Mid is kept this pass; demote to local when idle so it ages out next time.
            ClauseTier.MID -> session.setLearnedClauseTier(i, if (used) ClauseTier.MID else ClauseTier.LOCAL)

            ClauseTier.LOCAL -> if (used) {
                session.setLearnedClauseTier(i, ClauseTier.MID) // promote a reused local clause
            } else {
                session.setLearnedClauseTier(i, ClauseTier.LOCAL)
                locals.add(intArrayOf(lbd, i)) // deletion candidate
            }

            ClauseTier.UNSET -> error("entryTier is resolved away from UNSET above")
        }
    }
    val kept = learnedSize - locals.size
    val residualCap = (cap - kept).coerceAtLeast(0)
    if (locals.size <= residualCap) {
        for (i in 0 until learnedSize) session.clearLearnedClauseUsed(i)
        return
    }
    locals.sortBy { it[0] } // ascending LBD: keep the lowest, drop the highest
    val dropSet = IntHashSet(locals.size - residualCap)
    for (k in residualCap until locals.size) dropSet.add(locals[k][1])
    session.forgetLearnedClauses { idx, _ -> idx !in dropSet }
    params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropSet.size, dropped = dropSet.size))
    // Indices were compacted by the forget; reset every survivor's reuse flag.
    val survivors = session.learnedClauseCount
    for (i in 0 until survivors) session.clearLearnedClauseUsed(i)
}

/**
 * Clause vivification inprocessing (#203) — Piette-Hamadi-Saïs 2008. Walks a bounded
 * round-robin slice ([BacktrackParams.vivifyBatch]) of the learned-clause database and
 * strengthens each pure-Boolean, non-permanent clause via [vivifyClause]. Must be called
 * with the session at root (the restart boundary pops the DFS trail first). Strengthened
 * clauses are swapped in by dropping the originals and re-adding the shortened versions;
 * since the re-added clauses are at least binary over root-unassigned variables they don't
 * propagate, so the session is left at root. Returns the advanced cursor for the next pass.
 *
 * Soundness: every clause [vivifyClause] returns is still implied by the formula (a
 * subclause of an implied clause, or a prefix proven implied by propagation), so swapping
 * it in cannot lose models — checked by the learned-clause / witness validation tests.
 */
internal fun BacktrackSolver.vivify(session: PropagationSession, params: BacktrackParams, startCursor: Int): Int {
    val count = session.learnedClauseCount
    if (count == 0) return 0
    val numBool = session.problem.numBoolVars
    val batch = params.vivifyBatch.coerceAtLeast(1)
    val replacements = ArrayList<IntArray>()
    val dropIdx = IntHashSet()
    var cursor = if (startCursor in 0 until count) startCursor else 0
    var examined = 0
    while (examined < batch && examined < count) {
        val idx = cursor
        cursor = (cursor + 1) % count
        examined++
        if (session.learnedClausePermanent(idx)) continue
        val clause = session.learnedClauseAt(idx)
        val lits = clause.literals
        // Pure-Boolean only; nothing to shorten below 3 literals (we never emit units).
        if (lits.size < 3 || !clause.allLiteralsBool(numBool)) continue
        val strengthened = vivifyClause(session, lits) ?: continue
        if (strengthened.size in 2 until lits.size) {
            dropIdx.add(idx)
            replacements.add(strengthened)
        }
    }
    if (replacements.isEmpty()) return cursor
    session.forgetLearnedClauses { i, _ -> i !in dropIdx }
    for (newLits in replacements) session.addLearnedClause(Clause(newLits), lbd = newLits.size)
    // The forget renumbered the database, so resume the round-robin from the start.
    return 0
}

/**
 * Vivify one clause with the session at root: walk [lits] asserting the negation of each
 * literal under propagation. A literal already falsified by the earlier negations is
 * dropped (redundant); a literal forced true, or a conflict on asserting its negation,
 * shortens the clause to the literals visited so far. Returns the strengthened literal
 * array, or null when nothing changed. Every tentative pin is reverted before returning,
 * so the session is left exactly as it was found.
 */
internal fun BacktrackSolver.vivifyClause(session: PropagationSession, lits: IntArray): IntArray? {
    val keep = IntArrayList(lits.size)
    var pushed = 0
    var result: IntArray? = null
    for (li in lits) {
        when (session.litTruth(li)) {
            // The earlier negations already force li true ⇒ (kept ∨ li) is implied.
            true -> {
                keep.add(li)
                result = keep.toIntArray()
                break
            }

            // li is already falsified by the earlier negations ⇒ redundant, drop it.
            false -> Unit

            // Undetermined: assert ¬li and keep going.
            null -> {
                keep.add(li)
                val r = session.pinBool(Lit.variable(li), !Lit.isPositive(li))
                if (r is PropagationResult.Unsat) {
                    // ¬(kept) is unsatisfiable ⇒ (kept) is implied.
                    result = keep.toIntArray()
                    break
                }
                pushed++
            }
        }
    }
    repeat(pushed) { session.popLast() }
    if (result == null && keep.size < lits.size) result = keep.toIntArray()
    return result
}

/** How [backjumpAndLearn] terminated. */
internal enum class BackjumpTerm {
    /** Backjumped, learned clause asserted cleanly. Resume by descending. */
    Resume,

    /** Asserting the learned clause forced a level-0 contradiction; the entire search
     *  space is infeasible. Engine yields [SearchOutcome.Exhausted]. */
    Exhausted,

    /** Cascading conflicts couldn't be resolved further (e.g., assertion reached
     *  level 0 without a useful new clause). Fall back to chronological backtrack. */
    Stuck,
}

/**
 * Execute the CDB backjump + clause-learn sequence:
 *   - pop trail + session to `learned.backjumpLevel`;
 *   - materialise `learned.literals` as a [Clause]
 *     and feed it to [PropagationSession.addLearnedClause], which asserts it via
 *     propagation (forcing the asserting literal as a unit pin);
 *   - if the assertion cascades into another conflict, recurse on the new analyzer
 *     result. Bounded to keep the search loop from looping forever on pathological
 *     instances; [BackjumpTerm.Stuck] surfaces to the caller in that case.
 */
internal fun BacktrackSolver.backjumpAndLearn(
    learned: Learned,
    trail: MutableList<TrailNode>,
    session: PropagationSession,
    @Suppress("UNUSED_PARAMETER") params: BacktrackParams,
    @Suppress("UNUSED_PARAMETER") boolPhase: BooleanArray?,
    @Suppress("UNUSED_PARAMETER") boolPhaseSet: BooleanArray?,
    @Suppress("UNUSED_PARAMETER") intPhase: IntArray?,
    @Suppress("UNUSED_PARAMETER") intPhaseSet: BooleanArray?,
    alignFirst: Boolean,
): BackjumpTerm {
    if (alignFirst && trail.isNotEmpty()) trail.removeAt(trail.size - 1)
    var current = learned
    // Cap the recursive backjump loop to defend against pathological cycles. Each
    // round strictly reduces the conflict level (the analyzer's backjumpLevel is
    // always < the conflict's current level), so termination is guaranteed in a
    // sane analyzer — the cap is purely defensive.
    repeat(MAX_CASCADING_BACKJUMPS) {
        // A non-asserting clause never becomes unit after the backjump, so it can't
        // force its asserting literal — fall back to chronological backtracking.
        if (!current.asserting) return BackjumpTerm.Stuck
        // Pop trail + session to the backjump level.
        while (trail.size > current.backjumpLevel) {
            session.popLast()
            trail.removeAt(trail.size - 1)
        }
        // Build the Clause and assert it. The clause's literals are non-empty as
        // long as the analyzer produced a UIP (always the case in well-formed
        // calls); if the clause came out empty, fall back to chronological.
        if (current.literals.isEmpty()) return BackjumpTerm.Stuck
        val clause = Clause(current.literals)
        val result = session.addLearnedClause(clause, current.lbd)
        when (result) {
            is PropagationResult.Implied -> return BackjumpTerm.Resume

            is PropagationResult.Unsat -> {
                // Assertion cascaded into another conflict. The session ran the
                // analyzer on the new conflict; if a new learned clause came back,
                // recurse — otherwise we're stuck.
                val next = result.learnedClause
                    as? Learned
                    ?: return BackjumpTerm.Stuck
                // If the new backjump target is level 0 and the clause is empty
                // after that jump, the whole problem is infeasible.
                if (next.backjumpLevel == 0 && next.literals.isEmpty()) {
                    return BackjumpTerm.Exhausted
                }
                current = next
            }
        }
    }
    return BackjumpTerm.Stuck
}

/** Materialize the session's current assignment as a [Sample]: each bool at its value (unset ⇒ false),
 *  each int at its domain minimum. Receiver-free — depends only on [session]. */
internal fun snapshotAssignment(session: PropagationSession): Sample {
    val sp = session.problem
    val bools = BooleanArray(sp.numBoolVars) { v -> session.boolValue(v) ?: false }
    val ints = IntArray(sp.numIntVars) { v -> session.intDomain(v).min }
    return Sample(bools, ints)
}

internal fun BacktrackSolver.farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
    if (minDistance <= 0 || window.isEmpty()) return true
    for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
    return true
}
