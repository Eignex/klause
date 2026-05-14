package com.eignex.klause.solver

import kotlin.random.Random

/**
 * Per-call params for [BacktrackSolver].
 *
 *  - [maxDecisions] — abort after this many decisions are pushed (Unknown). `Long.MAX_VALUE`
 *    by default — let the search run to completion.
 *  - [randomSeed] — used by the heuristic for tie-breaking when multiple vars are equally
 *    constrained. `null` picks a fresh seed per call.
 *  - [assumptions] — variables pinned for the duration of the call. Seeded as facts (not
 *    decisions) before the search starts.
 *  - [heuristic] — branching plug-in. The default ([MostConstrainedHeuristic]) picks the
 *    smallest-domain unpinned var, bools-before-ints, with `true` / domain-min as the
 *    first value.
 *  - [minHammingDistance] / [recentWindow] — dedup filter for the [enumerate] path. Ignored
 *    by [solve] / [samples].
 */
data class BacktrackParams(
    val maxDecisions: Long = Long.MAX_VALUE,
    val randomSeed: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
    val heuristic: BranchingHeuristic = MostConstrainedHeuristic,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
) : SolverParams

/**
 * Decision-picking strategy for [BacktrackSolver]. Returning `null` from [pick] means
 * "every variable is assigned" — the caller treats that as SAT.
 *
 * Implementations should use [PropagationSession.boolValue] and [PropagationSession.intDomain]
 * to see the propagated state (decisions + implications), not just the decision trail.
 */
fun interface BranchingHeuristic {
    fun pick(session: PropagationSession, rng: Random): Decision?
}

/** Which variable to branch on next. The engine handles value enumeration. */
sealed interface Decision {
    val varId: Int
    data class Bool(override val varId: Int) : Decision
    data class IntVar(override val varId: Int) : Decision
}

/**
 * Default heuristic: smallest-domain unpinned var, bools-before-ints. Looks at the
 * propagated state via [PropagationSession.boolValue] / [intDomain], so it skips vars that
 * propagation has already determined.
 */
object MostConstrainedHeuristic : BranchingHeuristic {
    override fun pick(session: PropagationSession, rng: Random): Decision? {
        val problem = session.problem
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) return Decision.Bool(v)
        }
        var bestVar = -1
        var bestSize = Int.MAX_VALUE
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            val size = d.size
            if (size > 1 && size < bestSize) { bestSize = size; bestVar = v }
        }
        return if (bestVar < 0) null else Decision.IntVar(bestVar)
    }
}

/**
 * Complete depth-first search over a [Problem]'s assignment space, driven by propagation
 * via [PropagationSession]. At each level the heuristic picks a variable; the engine
 * enumerates its values (bool: false then true; int: domain low to high), backtracking on
 * conflict. SAT-leaves are the witnesses; UNSAT is returned when the tree exhausts.
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

    override fun samples(params: BacktrackParams): Sequence<Sample> = sequence {
        for (outcome in driveSearch(params)) {
            when (outcome) {
                is SearchOutcome.Found -> yield(outcome.sample)
                SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> return@sequence
            }
        }
    }

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
        for (s in samples(params)) {
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
     * A trail frame for one variable being explored. Tracks which values have been tried;
     * `nextValue` returns the next untried value (or null when exhausted).
     */
    private sealed interface TrailNode {
        val varId: Int
        /** Apply the next-untried value to the session. Returns the session's response, or
         *  null if all values for this var have been tried. */
        fun applyNext(session: PropagationSession): PropagationResult?
    }

    private class BoolNode(override val varId: Int) : TrailNode {
        private var index = 0  // 0 = false, 1 = true, 2 = done
        override fun applyNext(session: PropagationSession): PropagationResult? = when (index++) {
            0 -> session.pinBool(varId, false)
            1 -> session.pinBool(varId, true)
            else -> null
        }
    }

    private class IntNode(override val varId: Int) : TrailNode {
        private var cursor: Int = Int.MIN_VALUE  // next domain value to try
        private var started = false
        override fun applyNext(session: PropagationSession): PropagationResult? {
            val d = session.intDomain(varId)
            if (!started) { cursor = d.min; started = true }
            while (cursor <= d.max) {
                val v = cursor++
                val r = session.pinInt(varId, v)
                if (r !is PropagationResult.Unsat) return r
                // Otherwise keep going to the next value.
            }
            return null
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
        // `descend` = "look for a new variable to branch on". When false, we're retreating
        // (top of trail just failed to advance, or we just yielded SAT and need to retry
        // the top with its next value).
        var descend = true

        loop@ while (true) {
            if (descend) {
                val pick = params.heuristic.pick(session, rng)
                if (pick == null) {
                    // No more decisions — current state is a SAT leaf.
                    yield(SearchOutcome.Found(snapshotAssignment(session)))
                    descend = false
                    continue@loop
                }
                val node: TrailNode = when (pick) {
                    is Decision.Bool -> BoolNode(pick.varId)
                    is Decision.IntVar -> IntNode(pick.varId)
                }
                if (!advance(node, session, decisionsRemaining = { decisionsLeft }, decrement = { decisionsLeft-- })) {
                    // Budget capped while pushing.
                    if (decisionsLeft <= 0) { yield(SearchOutcome.BudgetCapped); return@sequence }
                    // Otherwise: node exhausted on first try — retreat (no trail entry to add).
                    descend = false
                    continue@loop
                }
                trail.add(node)
                // descend stays true — look for next var.
            } else {
                // Retreat: advance the current top, or pop if it's exhausted.
                if (trail.isEmpty()) { yield(SearchOutcome.Exhausted); return@sequence }
                val top = trail.last()
                // The top's active value is still in the session. Pop it to free this level
                // so the next applyNext starts fresh at the same decision level.
                session.popLast()
                if (advance(top, session, decisionsRemaining = { decisionsLeft }, decrement = { decisionsLeft-- })) {
                    descend = true
                } else {
                    if (decisionsLeft <= 0) { yield(SearchOutcome.BudgetCapped); return@sequence }
                    trail.removeAt(trail.size - 1)
                    // descend remains false — keep retreating.
                }
            }
        }
    }

    /**
     * Advance [node] by trying its next untried values until one succeeds (returns true,
     * session has it pinned) or it exhausts (returns false). On Unsat the session
     * self-reverts, so the engine doesn't need to compensate. Returns false if either the
     * node ran out of values OR the decision budget was hit mid-loop.
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
            // Unsat: session self-reverted. Try the next value.
        }
    }

    /**
     * Build a [Sample] from the session's current propagated state. After the heuristic
     * returned null, every bool/int is determined.
     */
    private fun snapshotAssignment(session: PropagationSession): Sample {
        val problem = session.problem
        val bools = BooleanArray(problem.numBoolVars) { v -> session.boolValue(v) ?: false }
        val ints = IntArray(problem.numIntVars) { v -> session.intDomain(v).min }
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
}
