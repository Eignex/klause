package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
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
                SearchOutcome.Exhausted -> SolveResult.Unsat
                SearchOutcome.BudgetCapped -> SolveResult.Unknown
            }
        }
        return SolveResult.Unsat
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
                SolveResult.Unsat -> return@sequence
                SolveResult.Unknown -> return@sequence
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
                SearchOutcome.Exhausted -> SolveResult.Unsat
                SearchOutcome.BudgetCapped -> SolveResult.Unknown
            }
        }
        return SolveResult.Unsat
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
                SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> return@sequence
            }
        }
    }

    /**
     * Linear-objective minimisation by enumeration. Complete but exponential — fine on
     * small spaces, otherwise use the LS or Z3 backend.
     */
    override fun minimize(objective: Objective, params: BacktrackParams): Sample? {
        var best: Sample? = null
        var bestObj = Double.POSITIVE_INFINITY
        // Must use enumerate (DFS terminates on Exhausted) rather than samples (infinite
        // for any feasible problem).
        for (s in enumerate(params.copy(minHammingDistance = 0, recentWindow = 0))) {
            val o = objective.evaluate(s)
            if (o < bestObj) { bestObj = o; best = s }
        }
        return best
    }

    // ---------------------------------------------------------------------------------------
    // Engine.
    // ---------------------------------------------------------------------------------------

    private sealed interface SearchOutcome {
        data class Found(val sample: Sample) : SearchOutcome
        data object Exhausted : SearchOutcome
        data object BudgetCapped : SearchOutcome
    }

    /**
     * A trail frame for one variable being explored. The value iterator is supplied by the
     * caller's [ValueHeuristic] at node creation; [applyNext] pulls the next value, pushes
     * it into the session, and reports the session's response (or `null` when exhausted).
     */
    private sealed interface TrailNode {
        val varRef: VarRef
        fun applyNext(session: PropagationSession): PropagationResult?
    }

    private class BoolNode(
        override val varRef: VarRef.Bool,
        valueSeq: Sequence<Int>,
    ) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): PropagationResult? {
            if (!iter.hasNext()) return null
            return session.pinBool(varRef.varId, iter.next() != 0)
        }
    }

    private class IntNode(
        override val varRef: VarRef.IntVar,
        valueSeq: Sequence<Int>,
    ) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): PropagationResult? {
            if (!iter.hasNext()) return null
            return session.pinInt(varRef.varId, iter.next())
        }
    }

    /**
     * Lazy stream of search outcomes. Each call resumes the DFS from where it last yielded.
     * Engine invariant: `trail` lists nodes whose currently-active value is reflected in
     * `session`'s pushed pins. On Unsat, `session` self-reverts — the engine doesn't
     * popLast in that case.
     */
    private fun driveSearch(params: BacktrackParams): Sequence<SearchOutcome> = sequence {
        if (problem.baked is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted); return@sequence
        }
        val session = PropagationSession(problem)
        val seedResult = session.seed(params.assumptions)
        if (seedResult is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted); return@sequence
        }
        val rng = Random(params.randomSeed ?: Random.Default.nextLong())
        val trail: MutableList<TrailNode> = ArrayList()
        var decisionsLeft = params.maxDecisions
        var descend = true
        // Check cancellation between iterations rather than every operation — amortised
        // cost is negligible and the search responds within a handful of decisions.
        var cancelCheckCountdown = 0

        loop@ while (true) {
            if (cancelCheckCountdown-- <= 0) {
                if (params.cancellation()) { yield(SearchOutcome.BudgetCapped); return@sequence }
                cancelCheckCountdown = CANCEL_CHECK_INTERVAL
            }
            if (descend) {
                val varRef = params.variableHeuristic.pick(session, rng)
                if (varRef == null) {
                    yield(SearchOutcome.Found(snapshotAssignment(session)))
                    descend = false
                    continue@loop
                }
                val node = makeNode(varRef, params.valueHeuristic.values(session, varRef, rng))
                if (!advance(node, session, { decisionsLeft }, { decisionsLeft-- })) {
                    if (decisionsLeft <= 0) { yield(SearchOutcome.BudgetCapped); return@sequence }
                    descend = false
                    continue@loop
                }
                trail.add(node)
            } else {
                if (trail.isEmpty()) { yield(SearchOutcome.Exhausted); return@sequence }
                val top = trail.last()
                session.popLast()
                if (advance(top, session, { decisionsLeft }, { decisionsLeft-- })) {
                    descend = true
                } else {
                    if (decisionsLeft <= 0) { yield(SearchOutcome.BudgetCapped); return@sequence }
                    trail.removeAt(trail.size - 1)
                }
            }
        }
    }

    private fun makeNode(varRef: VarRef, values: Sequence<Int>): TrailNode = when (varRef) {
        is VarRef.Bool -> BoolNode(varRef, values)
        is VarRef.IntVar -> IntNode(varRef, values)
    }

    /**
     * Drive [node] through its remaining values until one succeeds or it exhausts. On Unsat
     * the session self-reverts, so the engine doesn't need to compensate. Returns `false`
     * either when the node ran out of values OR the decision budget was hit mid-loop.
     */
    private fun advance(
        node: TrailNode,
        session: PropagationSession,
        decisionsRemaining: () -> Long,
        decrement: () -> Unit,
    ): Boolean {
        while (true) {
            if (decisionsRemaining() <= 0) return false
            decrement()
            val r = node.applyNext(session) ?: return false
            if (r !is PropagationResult.Unsat) return true
        }
    }

    private fun snapshotAssignment(session: PropagationSession): Sample {
        val sp = session.problem
        val bools = BooleanArray(sp.numBoolVars) { v -> session.boolValue(v) ?: false }
        val ints = IntArray(sp.numIntVars) { v -> session.intDomain(v).min }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (hamming(candidate, p) < minDistance) return false
        return true
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }

    private companion object {
        /** Cancellation is polled this often inside the search loop. Lower = more
         *  responsive; higher = lower overhead. 256 is a few microseconds per check at
         *  worst, and the search stops within a few hundred decisions of a cancel. */
        const val CANCEL_CHECK_INTERVAL: Int = 256
    }
}
