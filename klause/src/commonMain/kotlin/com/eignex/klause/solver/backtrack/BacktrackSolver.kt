package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.TerminationReason
import com.eignex.klause.solver.UnsatCore
import com.eignex.klause.solver.propagation.ConflictAnalyzer
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Complete depth-first search over a [Problem]'s assignment space, driven by propagation
 * via [PropagationSession]. Variable selection and value selection are plug-in heuristics
 * via [BacktrackParams.variableHeuristic] / [BacktrackParams.valueHeuristic] — same split
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
class BacktrackSolver(override val problem: Problem) : Solver<BacktrackParams>, Optimizer<BacktrackParams> {

    override fun solve(params: BacktrackParams): SolveResult {
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
     * descendant leaf beats it. For arbitrary [Objective] subtypes the predicate
     * degrades to "never prune," so correctness is preserved at the cost of falling
     * back to full enumeration.
     */
    override fun minimize(objective: Objective, params: BacktrackParams): MinimizeResult =
        improvements(objective, params).last()

    /**
     * Anytime variant of [minimize]: yields one [MinimizeResult.BestFound] per new
     * incumbent discovered, followed by exactly one terminal verdict
     * ([MinimizeResult.Optimal] / [MinimizeResult.Infeasible] / final
     * [MinimizeResult.BestFound] / [MinimizeResult.Unknown]). Same B&B engine as
     * [minimize]; just exposes the search's intermediate bests as they land instead of
     * collapsing them into a single return value.
     */
    override fun improvements(
        objective: Objective,
        params: BacktrackParams,
    ): Sequence<MinimizeResult> = sequence {
        var best: Sample? = null
        var bestObj = Double.POSITIVE_INFINITY
        val pruneIf: ((PropagationSession) -> Boolean)? = when (objective) {
            is LinearObjective -> { session -> linearLowerBound(objective, session) >= bestObj }
            else -> null
        }
        for (outcome in driveSearch(
            params.copy(minHammingDistance = 0, recentWindow = 0),
            pruneIf = pruneIf,
        )) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val o = objective.evaluate(outcome.sample)
                    if (o < bestObj) {
                        bestObj = o; best = outcome.sample
                        // Yield each new incumbent eagerly — consumers can react to it
                        // before search continues toward the bound. The reason here is
                        // a hint ("more might come"); the terminal yield carries the
                        // real verdict.
                        yield(MinimizeResult.BestFound(outcome.sample, o, TerminationReason.BudgetExhausted))
                    }
                }
                is SearchOutcome.Exhausted -> {
                    yield(if (best != null) MinimizeResult.Optimal(best, bestObj)
                          else MinimizeResult.Infeasible(outcome.core))
                    return@sequence
                }
                SearchOutcome.BudgetCapped -> {
                    yield(if (best != null) MinimizeResult.BestFound(best, bestObj, TerminationReason.BudgetExhausted)
                          else MinimizeResult.Unknown(TerminationReason.BudgetExhausted))
                    return@sequence
                }
            }
        }
        // Sequence drained without a terminal outcome — treat as exhausted.
        yield(if (best != null) MinimizeResult.Optimal(best, bestObj) else MinimizeResult.Infeasible())
    }

    /**
     * Sound lower bound on a [LinearObjective] given the current partial assignment in
     * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
     * weight (or 0) that makes their contribution smallest; unpinned int vars take the
     * domain endpoint matching the coefficient's sign.
     */
    private fun linearLowerBound(obj: LinearObjective, session: PropagationSession): Double {
        var total = obj.constant
        val sp = session.problem
        val nb = minOf(sp.numBoolVars, obj.boolWeights.size)
        for (b in 0 until nb) {
            val w = obj.boolWeights[b]
            val v = session.boolValue(b)
            total += when {
                v == true -> w
                v == false -> 0.0
                w < 0.0 -> w
                else -> 0.0
            }
        }
        val ni = minOf(sp.numIntVars, obj.intCoefficients.size)
        for (i in 0 until ni) {
            val c = obj.intCoefficients[i]
            if (c == 0.0) continue
            val d = session.intDomain(i)
            total += if (c >= 0.0) c * d.min else c * d.max
        }
        return total
    }

    // ---------------------------------------------------------------------------------------
    // Engine.
    // ---------------------------------------------------------------------------------------

    /** Lift a [PropagationResult.Unsat]'s factor-level conflict info to a klause [UnsatCore].
     *  Empty `conflictFactors` (seed-only contradiction, no factor invocation involved)
     *  collapses to `null` — the API contract is "core absent" rather than "core empty",
     *  since an empty core wouldn't be actionable. */
    private fun coreOf(unsat: PropagationResult.Unsat): UnsatCore? =
        if (unsat.conflictFactors.isEmpty()) null
        else UnsatCore.of(unsat.conflictFactors)

    private sealed interface SearchOutcome {
        data class Found(val sample: Sample) : SearchOutcome
        /** DFS exhausted without finding a model. [core] is non-null when the exhaustion
         *  was forced by root-level propagation (bake or seed); after a full DFS-tree
         *  walk, no single-factor core explains the result and [core] stays null. */
        data class Exhausted(val core: UnsatCore? = null) : SearchOutcome
        data object BudgetCapped : SearchOutcome
    }

    /**
     * A trail frame for one variable being explored. The value iterator is supplied by the
     * caller's [ValueHeuristic] at node creation; [applyNext] pulls the next value, pushes
     * it into the session, and reports back both the value (so the engine can fire
     * heuristic callbacks scoped to the attempted pair) and the session's propagation
     * response. Returns `null` when the value iterator is exhausted.
     */
    private sealed interface TrailNode {
        val varRef: VarRef
        fun applyNext(session: PropagationSession): ApplyOutcome?
    }

    /** What [TrailNode.applyNext] returns: the actual value pushed (bools encoded as 0/1
     *  so the value heuristic callbacks see the original heuristic-emitted form) plus the
     *  session's [PropagationResult]. */
    private data class ApplyOutcome(val value: Int, val result: PropagationResult)

    private class BoolNode(
        override val varRef: VarRef.Bool,
        valueSeq: Sequence<Int>,
    ) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!iter.hasNext()) return null
            val v = iter.next()
            return ApplyOutcome(v, session.pinBool(varRef.varId, v != 0))
        }
    }

    private class IntNode(
        override val varRef: VarRef.IntVar,
        valueSeq: Sequence<Int>,
    ) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!iter.hasNext()) return null
            val v = iter.next()
            return ApplyOutcome(v, session.pinInt(varRef.varId, v))
        }
    }

    /**
     * Lazy stream of search outcomes. Each call resumes the DFS from where it last yielded.
     * Engine invariant: `trail` lists nodes whose currently-active value is reflected in
     * `session`'s pushed pins. On Unsat, `session` self-reverts — the engine doesn't
     * popLast in that case.
     */
    private fun driveSearch(
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)? = null,
    ): Sequence<SearchOutcome> = sequence {
        if (problem.baked is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted(coreOf(problem.baked))); return@sequence
        }
        val session = PropagationSession(problem)
        val seedResult = session.seed(params.assumptions)
        if (seedResult is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted(coreOf(seedResult))); return@sequence
        }
        // Phase-saving: cache the last value committed for each var (across backtracks
        // and restarts). Allocated only when enabled. The `boolPhaseSet` parallel array
        // distinguishes "never committed a value yet" from "saved value happens to be
        // false" — without it the default-false BooleanArray entries would shadow any
        // real saves of false.
        val boolPhase: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numBoolVars) else null
        val boolPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numBoolVars) else null
        val intPhase: IntArray? = if (params.phaseSaving) IntArray(problem.numIntVars) else null
        val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numIntVars) else null

        val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
        val rng = Random(baseSeed)
        // The effective budget tightens the two limits — whichever is smaller wins. This
        // lets a uniform `maxInstructions` work across backends without removing the
        // backend-specific `maxDecisions` knob.
        var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

        // Outer restart loop. Each iteration is one Luby-bounded DFS run from the root.
        // When `lubyRestartBase` is null the loop runs exactly once with infinite per-run
        // budget — same as the pre-restart behaviour.
        var lubyIdx = 1L
        outer@ while (true) {
            val perRunBudget: Long = params.lubyRestartBase?.let { base ->
                // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
                val limit = lubyN(lubyIdx)
                if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
            } ?: Long.MAX_VALUE
            var decisionsThisRun = 0L

            val trail: MutableList<TrailNode> = ArrayList()
            var descend = true
            var cancelCheckCountdown = 0

            inner@ while (true) {
                if (cancelCheckCountdown-- <= 0) {
                    if (params.cancellation()) { yield(SearchOutcome.BudgetCapped); return@sequence }
                    cancelCheckCountdown = CANCEL_CHECK_INTERVAL
                }
                // Luby budget hit → pop back to root and restart.
                if (decisionsThisRun >= perRunBudget) {
                    while (trail.isNotEmpty()) {
                        session.popLast()
                        trail.removeAt(trail.size - 1)
                    }
                    params.variableHeuristic.onRestart()
                    params.valueHeuristic.onRestart()
                    // LCG learned-clause forgetting: at each restart, prune the database
                    // when over [maxLearnedClauses]. Glue clauses (LBD ≤ glueThreshold)
                    // are always retained; among the rest, the lowest-LBD entries are
                    // kept up to the cap.
                    forgetIfOverCap(session, params)
                    lubyIdx++
                    continue@outer
                }
                if (descend) {
                    val varRef = params.variableHeuristic.pick(session, rng)
                    if (varRef == null) {
                        yield(SearchOutcome.Found(snapshotAssignment(session)))
                        descend = false
                        continue@inner
                    }
                    val values = params.valueHeuristic.values(session, varRef, rng)
                    val ordered = applyPhase(varRef, values, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                    val node = makeNode(varRef, ordered)
                    val decsBefore = decisionsLeft
                    val out = advance(node, session, params, pruneIf, { decisionsLeft }, { decisionsLeft-- })
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            capturePhase(varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                            trail.add(node)
                        }
                        AdvanceOutcome.Exhausted -> {
                            descend = false
                            continue@inner
                        }
                        AdvanceOutcome.BudgetCapped -> {
                            yield(SearchOutcome.BudgetCapped); return@sequence
                        }
                        is AdvanceOutcome.Backjump -> {
                            // Trail size == session.decisionLevel here (the failed pin was
                            // self-reverted by the session); execute the backjump + learn
                            // sequence. On cascading conflict during assertion, recurse.
                            val term = backjumpAndLearn(out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = false)
                            when (term) {
                                BackjumpTerm.Resume -> { descend = true; continue@inner }
                                BackjumpTerm.Exhausted -> {
                                    yield(SearchOutcome.Exhausted()); return@sequence
                                }
                                BackjumpTerm.Stuck -> { descend = false; continue@inner }
                            }
                        }
                    }
                } else {
                    if (trail.isEmpty()) { yield(SearchOutcome.Exhausted()); return@sequence }
                    val top = trail.last()
                    session.popLast()
                    val decsBefore = decisionsLeft
                    val out = advance(top, session, params, pruneIf, { decisionsLeft }, { decisionsLeft-- })
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            capturePhase(top.varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                            descend = true
                        }
                        AdvanceOutcome.Exhausted -> {
                            trail.removeAt(trail.size - 1)
                        }
                        AdvanceOutcome.BudgetCapped -> {
                            yield(SearchOutcome.BudgetCapped); return@sequence
                        }
                        is AdvanceOutcome.Backjump -> {
                            // Else-path: session has been popped below trail.last; align
                            // first (trail.removeAt) then proceed to backjump + learn.
                            val term = backjumpAndLearn(out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = true)
                            when (term) {
                                BackjumpTerm.Resume -> { descend = true; continue@inner }
                                BackjumpTerm.Exhausted -> {
                                    yield(SearchOutcome.Exhausted()); return@sequence
                                }
                                BackjumpTerm.Stuck -> { descend = false; continue@inner }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * If phase-saving is on and a value is cached for [varRef], prepend the cached value
     * to the heuristic's order (and drop it from the rest of the sequence so it isn't
     * tried twice). Otherwise the heuristic's order passes through unchanged.
     */
    private fun applyPhase(
        varRef: VarRef,
        values: Sequence<Int>,
        boolPhase: BooleanArray?, boolPhaseSet: BooleanArray?,
        intPhase: IntArray?, intPhaseSet: BooleanArray?,
    ): Sequence<Int> {
        return when (varRef) {
            is VarRef.Bool -> {
                if (boolPhase != null && boolPhaseSet != null && boolPhaseSet[varRef.varId]) {
                    val saved = if (boolPhase[varRef.varId]) 1 else 0
                    sequenceOf(saved) + values.filter { it != saved }
                } else values
            }
            is VarRef.IntVar -> {
                if (intPhase != null && intPhaseSet != null && intPhaseSet[varRef.varId]) {
                    val saved = intPhase[varRef.varId]
                    sequenceOf(saved) + values.filter { it != saved }
                } else values
            }
        }
    }

    /** Record the variable's currently-pinned value for phase-saving. Called after every
     *  successful pin (descent into a node). */
    private fun capturePhase(
        varRef: VarRef, session: PropagationSession,
        boolPhase: BooleanArray?, boolPhaseSet: BooleanArray?,
        intPhase: IntArray?, intPhaseSet: BooleanArray?,
    ) {
        when (varRef) {
            is VarRef.Bool -> {
                if (boolPhase != null && boolPhaseSet != null) {
                    val v = session.boolValue(varRef.varId)
                    if (v != null) { boolPhase[varRef.varId] = v; boolPhaseSet[varRef.varId] = true }
                }
            }
            is VarRef.IntVar -> {
                if (intPhase != null && intPhaseSet != null) {
                    val d = session.intDomain(varRef.varId)
                    if (d.min == d.max) { intPhase[varRef.varId] = d.min; intPhaseSet[varRef.varId] = true }
                }
            }
        }
    }

    private fun makeNode(varRef: VarRef, values: Sequence<Int>): TrailNode = when (varRef) {
        is VarRef.Bool -> BoolNode(varRef, values)
        is VarRef.IntVar -> IntNode(varRef, values)
    }

    /**
     * Luby sequence (Luby-Sinclair-Zuckerman 1993). Standard CDCL restart schedule:
     * `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, ...`. Closed form:
     * `lubyN(i) = 2^(k-1)` when `i = 2^k − 1` (i.e. one less than a power of two);
     * otherwise `lubyN(i − 2^(k-1) + 1)` where `k = ⌊log₂(i)⌋ + 1`.
     */
    private fun lubyN(idxIn: Long): Long {
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

    /**
     * Drive [node] through its remaining values until one succeeds or it exhausts. On Unsat
     * the session self-reverts; the engine notifies the heuristics so activity-/conflict-
     * driven strategies (VSIDS, dom/wdeg, last-conflict) can accumulate state. When
     * [pruneIf] is non-null, a successful pin is *additionally* checked against the
     * predicate: if the predicate says "no descendant of this partial assignment can
     * improve the incumbent," the pin is reverted and the next value tried — B&B soft
     * pruning, not fired as a heuristic conflict (the partial assignment is still
     * propagation-consistent). Returns `false` when the node runs out of values or the
     * decision budget is exhausted.
     */
    /**
     * What [advance] reports back to the search loop. The previous `Boolean` was
     * `true = success / false = exhausted-or-budget`; LCG-style non-chronological
     * backjump adds a third path that needs the target level threaded back to the
     * outer loop, hence the sealed type.
     */
    private sealed interface AdvanceOutcome {
        /** A value pinned cleanly; commit the node to the trail. */
        data object Success : AdvanceOutcome
        /** Node has no more values; chronological backtrack. */
        data object Exhausted : AdvanceOutcome
        /** Decision budget hit. */
        data object BudgetCapped : AdvanceOutcome
        /** Non-chronological backjump requested. After the engine pops trail to
         *  `learned.backjumpLevel`, it materialises [learned.literals] as a `Clause`,
         *  hands it to [PropagationSession.addLearnedClause], and resumes with the new
         *  clause now constraining future search and unit-propagating the asserting
         *  literal. */
        data class Backjump(val learned: com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned) : AdvanceOutcome
    }

    private fun advance(
        node: TrailNode,
        session: PropagationSession,
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)?,
        decisionsRemaining: () -> Long,
        decrement: () -> Unit,
    ): AdvanceOutcome {
        while (true) {
            if (decisionsRemaining() <= 0) return AdvanceOutcome.BudgetCapped
            decrement()
            val outcome = node.applyNext(session) ?: return AdvanceOutcome.Exhausted
            val r = outcome.result
            if (r is PropagationResult.Unsat) {
                // Forward the full conflict reason record so activity-, weight-, and
                // factor-driven heuristics (VSIDS, dom/wdeg) all see exactly what they
                // need without further plumbing.
                params.variableHeuristic.onConflict(node.varRef, r)
                params.valueHeuristic.onConflict(node.varRef, outcome.value)
                // CDB: if the analyzer produced a 1UIP clause with a non-chronological
                // backjump target, signal it up. The clause itself isn't stored yet
                // (LCG learning persistence is a follow-up); just the jump distance is
                // honoured, which by itself prunes subtrees that would re-derive the
                // same conflict at higher levels.
                val learned = r.learnedClause as? ConflictAnalyzer.AnalysisResult.Learned
                if (learned != null) return AdvanceOutcome.Backjump(learned)
                continue
            }
            if (pruneIf != null && pruneIf(session)) {
                session.popLast()
                continue
            }
            params.variableHeuristic.onCommit(node.varRef)
            params.valueHeuristic.onCommit(node.varRef, outcome.value)
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
    private fun forgetIfOverCap(session: PropagationSession, params: BacktrackParams) {
        val cap = params.maxLearnedClauses ?: return
        val learnedSize = session.problem.let { _ ->
            // PropagationSession exposes the count indirectly via session.state — pull
            // it from the state field that learnedClauses reads. We reuse the public
            // accessor on the session here to avoid leaking the state.
            session.learnedClauseCount
        }
        if (learnedSize <= cap) return
        val glueThreshold = params.lbdGlueThreshold
        // Bucket non-glue clauses by LBD and pick the lowest LBDs up to the residual
        // capacity. We do this as: compute LBD per index, sort ascending, and define
        // `keep(i, lbd) = lbd <= glueThreshold || rank(i) < remaining`.
        val nonGlue = ArrayList<IntArray>(learnedSize)  // [lbd, index] pairs
        for (i in 0 until learnedSize) {
            val lbd = session.learnedClauseLbd(i)
            if (lbd > glueThreshold) nonGlue.add(intArrayOf(lbd, i))
        }
        // If all are glue, nothing to forget.
        if (nonGlue.isEmpty()) return
        val glueCount = learnedSize - nonGlue.size
        val remainingCap = (cap - glueCount).coerceAtLeast(0)
        if (nonGlue.size <= remainingCap) return  // already under cap
        nonGlue.sortBy { it[0] }  // ascending LBD
        val kept = HashSet<Int>(remainingCap)
        for (k in 0 until remainingCap) kept.add(nonGlue[k][1])
        session.forgetLearnedClauses { idx, lbd ->
            lbd <= glueThreshold || idx in kept
        }
    }

    /** How [backjumpAndLearn] terminated. */
    private enum class BackjumpTerm {
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
     *   - pop trail + session to [learned.backjumpLevel];
     *   - materialise [learned.literals] as a [com.eignex.klause.solver.factor.Clause]
     *     and feed it to [PropagationSession.addLearnedClause], which asserts it via
     *     propagation (forcing the asserting literal as a unit pin);
     *   - if the assertion cascades into another conflict, recurse on the new analyzer
     *     result. Bounded to keep the search loop from looping forever on pathological
     *     instances; [BackjumpTerm.Stuck] surfaces to the caller in that case.
     *
     * @param alignFirst when `true`, drops the stale [trail.last] entry before popping
     *   (used by the else-path where session was already popped past trail.last by the
     *   caller).
     */
    private fun backjumpAndLearn(
        learned: com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned,
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
            // Pop trail + session to the backjump level.
            while (trail.size > current.backjumpLevel) {
                session.popLast()
                trail.removeAt(trail.size - 1)
            }
            // Build the Clause and assert it. The clause's literals are non-empty as
            // long as the analyzer produced a UIP (always the case in well-formed
            // calls); if the clause came out empty, fall back to chronological.
            if (current.literals.isEmpty()) return BackjumpTerm.Stuck
            val clause = com.eignex.klause.solver.factor.Clause(current.literals)
            val result = session.addLearnedClause(clause, current.lbd)
            when (result) {
                is PropagationResult.Implied -> return BackjumpTerm.Resume
                is PropagationResult.Unsat -> {
                    // Assertion cascaded into another conflict. The session ran the
                    // analyzer on the new conflict; if a new learned clause came back,
                    // recurse — otherwise we're stuck.
                    val next = result.learnedClause
                        as? com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned
                        ?: return BackjumpTerm.Stuck
                    // If the new backjump target is level 0 and the clause is empty
                    // after that jump, the whole problem is infeasible.
                    if (next.backjumpLevel == 0 && next.literals.isEmpty()) {
                        return BackjumpTerm.Exhausted
                    }
                    current = next
                }
                else -> return BackjumpTerm.Stuck  // shouldn't happen — addLearnedClause returns only Implied/Unsat
            }
        }
        return BackjumpTerm.Stuck
    }


    private fun snapshotAssignment(session: PropagationSession): Sample {
        val sp = session.problem
        val bools = BooleanArray(sp.numBoolVars) { v -> session.boolValue(v) ?: false }
        val ints = IntArray(sp.numIntVars) { v -> session.intDomain(v).min }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }
    private companion object {
        /** Cancellation is polled this often inside the search loop. Lower = more
         *  responsive; higher = lower overhead. 256 is a few microseconds per check at
         *  worst, and the search stops within a few hundred decisions of a cancel. */
        const val CANCEL_CHECK_INTERVAL: Int = 256
        /** Cap on cascading CDB backjumps within a single search step. Defensive; under
         *  a well-formed analyzer the loop terminates well before this. */
        const val MAX_CASCADING_BACKJUMPS: Int = 64
    }
}
