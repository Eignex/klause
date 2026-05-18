package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Lit

/**
 * First-UIP (Unique Implication Point) conflict analyzer — the classical CDCL clause-learning
 * routine (Marques-Silva-Sakallah 1996, popularised by Chaff / MiniSAT). Walks the
 * implication graph backwards from a conflict, resolving each step against the
 * antecedent literals stored on [PropagationState.boolAntecedents], until exactly one
 * variable at the current decision level remains — the UIP. The conjunction of negated
 * literals on the learned clause is forbidden by the original problem, so adding it
 * prunes any future search path that would re-derive the same conflict.
 *
 * Today this analyzer is **Clause-only**: only [com.eignex.klause.solver.factor.Clause]
 * factors record antecedents on unit propagation, and only Clauses produce a
 * [com.eignex.klause.solver.Factor.conflictReason]. When the walk hits a variable with
 * `null` antecedents (decision, assumption, or a non-Clause-pinned implied variable), it
 * treats that variable as a leaf — same as a decision. The resulting clause is sound
 * (negation of a falsifiable conjunction) but may be longer than the strict 1UIP clause
 * a fully-instrumented solver would produce. As other factor types learn to emit
 * antecedents and `conflictReason`s, the analyzer transparently picks up the richer info.
 *
 * The output is an [AnalysisResult]: either a [AnalysisResult.Learned] carrying the
 * learned-clause literals and the backjump level (= second-highest decision level among
 * the literals; the level the engine should non-chronologically jump to), or
 * [AnalysisResult.NotApplicable] when the analyzer can't produce a usable nogood (no
 * conflict reason, or the failing factor isn't clause-form).
 */
class ConflictAnalyzer internal constructor(private val state: PropagationState) {

    sealed interface AnalysisResult {
        /**
         * @param literals the learned clause (disjunction of literals). At least one literal
         *  must be true in any extension of the current partial assignment beyond the
         *  conflict point.
         * @param backjumpLevel the level the engine should pop trail back to. The learned
         *  clause is guaranteed to be unit at this level — propagation will immediately
         *  force the asserting literal, breaking the conflict.
         */
        data class Learned(val literals: IntArray, val backjumpLevel: Int) : AnalysisResult {
            override fun equals(other: Any?): Boolean =
                other is Learned && literals.contentEquals(other.literals) && backjumpLevel == other.backjumpLevel
            override fun hashCode(): Int = 31 * literals.contentHashCode() + backjumpLevel
            override fun toString(): String =
                "Learned(literals=${literals.toList()}, backjumpLevel=$backjumpLevel)"
        }

        /** Analysis couldn't produce a clause (no conflict reason, or non-Clause failure). */
        data object NotApplicable : AnalysisResult
    }

    /**
     * Run analysis from a conflict triggered by factor [conflictFactorId]. The state's
     * current level (`state.currentLevel`) is taken as the conflict level — the level
     * the failing factor was firing at.
     */
    fun analyze(conflictFactorId: Int): AnalysisResult {
        val factor = state.problem.factors[conflictFactorId]
        val seedReason = factor.conflictReason(state, conflictFactorId) ?: return AnalysisResult.NotApplicable
        val currentLevel = state.currentLevel
        if (currentLevel <= 0) {
            // Conflict at level 0 means UNSAT-by-bake; no learned clause helps a higher
            // level since there is none. Return NotApplicable and let the engine
            // surface the result through the usual Unsat path.
            return AnalysisResult.NotApplicable
        }

        // Standard 1UIP loop:
        //   `seen[v]`        — variable already in the working reason set
        //   `currentLevelCount` — variables at the current level still pending resolution
        //   `learned`        — literals already promoted to the learned clause (lower-level
        //                       literals + non-Clause leaves)
        val numBoolVars = state.problem.numBoolVars
        val seen = BooleanArray(numBoolVars)
        var currentLevelCount = 0
        val learned = ArrayList<Int>(seedReason.size)

        // Process the seed reason: each literal in it is currently false in [state] (since
        // the conflict factor returned false). The variable was pinned to make that literal
        // false; bumping `seen` for it adds it to the resolution working set.
        ingestReason(seedReason, seen, learned, currentLevel) {
            currentLevelCount++
        }

        if (currentLevelCount == 0) {
            // Every literal in the conflict reason was at a level < current — the conflict
            // is fundamentally lower. Backjump to the deepest of those levels, learned
            // clause is the conflict reason directly (negated).
            val backjumpLevel = learned.map { state.boolLevel[Lit.variable(it)] }.maxOrNull() ?: 0
            return AnalysisResult.Learned(learned.toIntArray(), backjumpLevel)
        }

        // Walk the pin trail backwards, resolving against the most-recently-pinned
        // variable still in the working set.
        var pinIdx = state.boolPinOrder.size - 1
        while (true) {
            // Find the next variable in the trail that's in `seen` (still pending) and at
            // the current level.
            var pivot = -1
            while (pinIdx >= 0) {
                val v = state.boolPinOrder[pinIdx]
                pinIdx--
                if (seen[v] && state.boolLevel[v] == currentLevel) {
                    pivot = v
                    break
                }
            }
            if (pivot < 0) {
                // No more current-level vars to resolve — shouldn't happen unless the
                // trail was truncated mid-conflict (we always resolve at least one). Treat
                // remaining seen-current-level vars as leaves and fall through.
                break
            }
            seen[pivot] = false
            currentLevelCount--
            if (currentLevelCount == 0) {
                // UIP reached. The pivot is the asserting literal at the current level.
                // Its literal in the learned clause is the *negation* of its current
                // pinned value — because the learned clause says "if everything else is
                // as it was, pivot can't be at its current value".
                val pivotPinned = state.boolValues[pivot] ?: error("UIP variable $pivot is unpinned")
                learned.add(Lit.make(pivot, !pivotPinned))
                val backjumpLevel = backjumpLevelOf(learned, currentLevel)
                return AnalysisResult.Learned(learned.toIntArray(), backjumpLevel)
            }
            // Not yet UIP — replace pivot with its antecedents (resolution).
            val antecedents = state.boolAntecedents[pivot]
                ?: run {
                    // No antecedents recorded → treat pivot as a leaf. Promote its literal
                    // directly and stop trying to resolve.
                    val pivotPinned = state.boolValues[pivot] ?: error("pivot $pivot unpinned")
                    learned.add(Lit.make(pivot, !pivotPinned))
                    // Drain the rest of `seen` similarly.
                    drainSeenAsLeaves(seen, learned)
                    val backjumpLevel = backjumpLevelOf(learned, currentLevel)
                    return AnalysisResult.Learned(learned.toIntArray(), backjumpLevel)
                }
            ingestReason(antecedents, seen, learned, currentLevel) {
                currentLevelCount++
            }
        }
        // Defensive fallback when the trail walk doesn't reach UIP (shouldn't normally
        // happen): emit whatever's in `seen` as leaves.
        drainSeenAsLeaves(seen, learned)
        val backjumpLevel = backjumpLevelOf(learned, currentLevel)
        return AnalysisResult.Learned(learned.toIntArray(), backjumpLevel)
    }

    /**
     * Add each literal in [reason] to either the working `seen` set (if it's at
     * [currentLevel] — resolution will continue through it) or directly to [learned]
     * (lower level — it's part of the final clause). Increments [bumpCurrentLevel] for
     * each new-at-current-level variable so the caller can track resolution progress.
     */
    private inline fun ingestReason(
        reason: IntArray,
        seen: BooleanArray,
        learned: ArrayList<Int>,
        currentLevel: Int,
        bumpCurrentLevel: () -> Unit,
    ) {
        for (lit in reason) {
            val v = Lit.variable(lit)
            if (seen[v]) continue
            val lvl = state.boolLevel[v]
            if (lvl <= 0) continue  // top-level (problem-implied) — not load-bearing
            seen[v] = true
            if (lvl == currentLevel) {
                bumpCurrentLevel()
            } else {
                // Lower-level variable: its literal in the learned clause is the negation
                // of its currently-pinned value, mirroring the UIP literal's encoding.
                val pinned = state.boolValues[v] ?: error("seen var $v not pinned")
                learned.add(Lit.make(v, !pinned))
            }
        }
    }

    /** Convert every still-seen variable into a literal in [learned]. Used as a fallback
     *  path when antecedent chasing terminates short of UIP. */
    private fun drainSeenAsLeaves(seen: BooleanArray, learned: ArrayList<Int>) {
        for (v in seen.indices) {
            if (!seen[v]) continue
            val pinned = state.boolValues[v] ?: continue
            // Avoid double-adding if this var's lit is already in `learned`.
            if (learned.any { Lit.variable(it) == v }) continue
            learned.add(Lit.make(v, !pinned))
        }
    }

    /**
     * Backjump target: the second-highest decision level among the learned literals'
     * variables. The asserting literal (UIP) sits at [currentLevel]; we want to pop back
     * to the level just past the next-highest, so the learned clause becomes unit (only
     * the UIP literal remains undetermined) and propagation can re-fire it as a forced
     * pin.
     */
    private fun backjumpLevelOf(learned: List<Int>, currentLevel: Int): Int {
        var best = 0
        for (lit in learned) {
            val lvl = state.boolLevel[Lit.variable(lit)]
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }
}
