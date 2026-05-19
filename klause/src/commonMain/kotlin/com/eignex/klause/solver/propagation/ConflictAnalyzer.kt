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
         * @param lbd Literal Block Distance — the number of *distinct decision levels*
         *  appearing in the learned clause. Lower LBD ⇒ more "glue-like" (binds many
         *  variables at the same level together) ⇒ likelier to be reused later in the
         *  search. Restart-driven forgetting policies (see
         *  [com.eignex.klause.solver.backtrack.BacktrackParams.maxLearnedClauses] /
         *  `lbdGlueThreshold`) order clauses by LBD when deciding which to keep.
         */
        data class Learned(
            val literals: IntArray,
            val backjumpLevel: Int,
            val lbd: Int,
        ) : AnalysisResult {
            override fun equals(other: Any?): Boolean =
                other is Learned
                    && literals.contentEquals(other.literals)
                    && backjumpLevel == other.backjumpLevel
                    && lbd == other.lbd
            override fun hashCode(): Int =
                31 * (31 * literals.contentHashCode() + backjumpLevel) + lbd
            override fun toString(): String =
                "Learned(literals=${literals.toList()}, backjumpLevel=$backjumpLevel, lbd=$lbd)"
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
        val factor = state.factorAt(conflictFactorId)
        val seedReason = factor.conflictReason(state, conflictFactorId) ?: return AnalysisResult.NotApplicable
        return analyzeFromSeed(seedReason)
    }

    /**
     * Decision-vs-prior-pin conflict path: when [PropagationState.pinBoolAsDecision]
     * fails because the var was already pinned to the opposite value, no factor's
     * `propagate` fired and `currentFactor` is `-1`. The implicit violated clause is:
     *   `(prior pin's antecedents) ∨ Lit.make(v, !prior_value)`
     * — every literal currently false in the state, exactly matching the analyzer's
     * "seed reason" contract. Falls through to the same 1UIP loop as [analyze].
     */
    fun analyzeDecisionConflict(conflictedVar: Int): AnalysisResult {
        val priorValue = state.boolValues[conflictedVar] ?: return AnalysisResult.NotApplicable
        val priorAnt = state.boolAntecedents[conflictedVar]
        // The just-attempted decision lit (currently false in state because the prior
        // pin still holds and pinBoolImpl rejected the new value).
        val decisionLit = com.eignex.klause.solver.Lit.make(conflictedVar, !priorValue)
        val seed = if (priorAnt == null) intArrayOf(decisionLit)
        else IntArray(priorAnt.size + 1).also {
            for (i in priorAnt.indices) it[i] = priorAnt[i]
            it[priorAnt.size] = decisionLit
        }
        return analyzeFromSeed(seed)
    }

    private fun analyzeFromSeed(seedReason: IntArray): AnalysisResult {
        val currentLevel = state.currentLevel
        if (currentLevel <= 0) return AnalysisResult.NotApplicable

        // Standard 1UIP loop with bool + atom support. The `seen` array spans
        // [0, numBoolVars + atomCount): low indices are bool vars (walked via
        // boolPinOrder), high indices are virtual atom-vars (resolved via
        // atomAntecedents — atoms have no chronological pin order, just allocation order,
        // so we sweep them after the bool trail is exhausted at currentLevel).
        val numBoolVars = state.problem.numBoolVars
        val atomCount = state.atomIntVar.size
        val seen = BooleanArray(numBoolVars + atomCount)
        var currentLevelCount = 0
        val learned = ArrayList<Int>(seedReason.size)

        ingestReason(seedReason, seen, learned, currentLevel) {
            currentLevelCount++
        }

        if (currentLevelCount == 0) {
            return finalizeClause(learned, currentLevel)
        }

        var pinIdx = state.boolPinOrder.size - 1
        while (true) {
            // Pick a current-level pivot: prefer the most-recent bool from the pin trail
            // (1UIP canonical order); if none, fall back to any atom-var at currentLevel.
            var pivot = -1
            while (pinIdx >= 0) {
                val v = state.boolPinOrder[pinIdx]
                pinIdx--
                if (seen[v] && state.boolLevel[v] == currentLevel) { pivot = v; break }
            }
            if (pivot < 0) {
                // Look for an atom pivot at currentLevel.
                for (id in 0 until atomCount) {
                    val v = numBoolVars + id
                    if (seen[v] && state.atomLevel[id] == currentLevel) { pivot = v; break }
                }
                if (pivot < 0) break
            }
            seen[pivot] = false
            currentLevelCount--
            if (currentLevelCount == 0) {
                learned.add(uipLit(pivot))
                return finalizeClause(learned, currentLevel)
            }
            val antecedents = antecedentsOf(pivot)
                ?: run {
                    // Leaf pivot — promote and drain the rest.
                    learned.add(uipLit(pivot))
                    drainSeenAsLeaves(seen, learned)
                    return finalizeClause(learned, currentLevel)
                }
            ingestReason(antecedents, seen, learned, currentLevel) {
                currentLevelCount++
            }
        }
        drainSeenAsLeaves(seen, learned)
        return finalizeClause(learned, currentLevel)
    }

    /** Produce the literal for [pivot] as it should appear in the learned clause —
     *  the negation of its current truth value, for both bool and atom pivots. */
    private fun uipLit(pivot: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (pivot < numBoolVars) {
            val pinned = state.boolValues[pivot] ?: error("UIP bool var $pivot unpinned")
            Lit.make(pivot, !pinned)
        } else {
            val atomId = pivot - numBoolVars
            val holds = state.atomCurrentTruth(atomId) ?: error("UIP atom $atomId undetermined")
            Lit.make(pivot, !holds)
        }
    }

    private fun antecedentsOf(v: Int): IntArray? {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) state.boolAntecedents[v]
        else state.atomAntecedents[v - numBoolVars]
    }

    /**
     * Apply self-subsuming-resolution minimization, then compute backjump level + LBD
     * on the final clause and wrap into [AnalysisResult.Learned]. Single tail call from
     * every exit path of [analyze] so all exit shapes get the same post-processing.
     */
    private fun finalizeClause(learned: ArrayList<Int>, currentLevel: Int): AnalysisResult.Learned {
        val minimized = minimize(learned, currentLevel)
        return AnalysisResult.Learned(
            minimized.toIntArray(),
            backjumpLevelOf(minimized, currentLevel),
            lbdOf(minimized),
        )
    }

    /**
     * Self-subsuming-resolution clause minimization. After 1UIP produces the learned
     * clause, any literal `l` whose variable has antecedents fully implied by the rest
     * of the clause is *redundant* — dropping it yields a strictly stronger clause
     * (subset of the original under standard resolution rules).
     *
     * The UIP literal (at [currentLevel]) is never dropped — it's the asserting literal
     * the engine relies on at the backjump level. Other literals are checked via
     * [isRedundant], which walks antecedents recursively with a per-call cache to keep
     * the cost linear in the implication graph reached.
     *
     * Standard CDCL polish (MiniSAT, Glucose). Shrinks learned clauses by 10-30% on
     * typical SAT-style instances, with knock-on improvements to watcher-list traversal
     * cost during future propagation.
     */
    private fun minimize(learned: ArrayList<Int>, currentLevel: Int): ArrayList<Int> {
        if (learned.size <= 1) return learned
        // inClause covers the combined bool + atom var space so atom-lit literals
        // participate in the redundancy check.
        val inClause = BooleanArray(state.problem.numBoolVars + state.atomIntVar.size)
        for (lit in learned) {
            val v = Lit.variable(lit)
            if (v < inClause.size) inClause[v] = true
        }
        val cache = HashMap<Int, Boolean>(learned.size * 4)
        val toDrop = HashSet<Int>()
        for (lit in learned) {
            val v = Lit.variable(lit)
            if (levelOf(v) == currentLevel) continue  // never drop UIP
            if (isRedundant(v, inClause, cache)) toDrop.add(v)
        }
        if (toDrop.isEmpty()) return learned
        val out = ArrayList<Int>(learned.size - toDrop.size)
        for (lit in learned) if (Lit.variable(lit) !in toDrop) out.add(lit)
        return out
    }

    /**
     * True iff every chain of antecedents leading to [v]'s pin terminates in either a
     * variable that's *already in the learned clause* ([inClause]) or a level-0 fact.
     * Decision-style leaves (variables with `null` antecedents) make [v] non-redundant.
     *
     * Cached per variable for the duration of a single [minimize] call — the recursion
     * depth is bounded by the size of the implication graph reached, but the cache
     * keeps the total work linear.
     */
    private fun isRedundant(
        v: Int, inClause: BooleanArray, cache: HashMap<Int, Boolean>,
    ): Boolean {
        cache[v]?.let { return it }
        val antecedents = antecedentsOf(v) ?: run {
            cache[v] = false; return false
        }
        for (lit in antecedents) {
            val u = Lit.variable(lit)
            if (u == v) continue
            if (levelOf(u) <= 0) continue
            if (u < inClause.size && inClause[u]) continue
            if (!isRedundant(u, inClause, cache)) {
                cache[v] = false
                return false
            }
        }
        cache[v] = true
        return true
    }

    /** Unified level lookup that handles both bool vars (via [PropagationState.boolLevel])
     *  and atom vars (via [PropagationState.atomLevel]). */
    private fun levelOf(v: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) state.boolLevel[v]
        else state.atomLevel[v - numBoolVars]
    }

    /**
     * Add each literal in [reason] to either the working `seen` set (if it's at
     * [currentLevel] — resolution will continue through it) or directly to [learned]
     * (lower level — it's part of the final clause). Increments [bumpCurrentLevel] for
     * each new-at-current-level variable so the caller can track resolution progress.
     */
    private fun ingestReason(
        reason: IntArray,
        seen: BooleanArray,
        learned: ArrayList<Int>,
        currentLevel: Int,
        bumpCurrentLevel: () -> Unit,
    ) {
        val numBoolVars = state.problem.numBoolVars
        for (lit in reason) {
            val v = Lit.variable(lit)
            if (v >= seen.size) continue  // atom allocated after analyzer started; shouldn't happen
            if (seen[v]) continue
            val lvl = levelOf(v)
            if (lvl <= 0) continue
            seen[v] = true
            if (lvl == currentLevel) {
                bumpCurrentLevel()
            } else {
                if (v < numBoolVars) {
                    val pinned = state.boolValues[v] ?: error("seen var $v not pinned")
                    learned.add(Lit.make(v, !pinned))
                } else {
                    val atomId = v - numBoolVars
                    val holds = state.atomCurrentTruth(atomId)
                        ?: error("ingest atom $atomId at lower level undetermined")
                    learned.add(Lit.make(v, !holds))
                }
            }
        }
    }

    /** Convert every still-seen variable into a literal in [learned]. */
    private fun drainSeenAsLeaves(seen: BooleanArray, learned: ArrayList<Int>) {
        val numBoolVars = state.problem.numBoolVars
        for (v in seen.indices) {
            if (!seen[v]) continue
            val lit: Int = if (v < numBoolVars) {
                val pinned = state.boolValues[v] ?: continue
                Lit.make(v, !pinned)
            } else {
                val atomId = v - numBoolVars
                val holds = state.atomCurrentTruth(atomId) ?: continue
                Lit.make(v, !holds)
            }
            if (learned.any { Lit.variable(it) == v }) continue
            learned.add(lit)
        }
    }

    /**
     * Literal Block Distance: the number of *distinct decision levels* spanned by the
     * learned clause's literals. Glauert-style "glue" measure popularised by Audemard &
     * Simon's Glucose — a tighter predictor of long-term clause usefulness than raw
     * length or activity. Forgetting policies typically keep clauses with LBD ≤ 2
     * forever ("glue clauses") and drop high-LBD clauses first.
     */
    private fun lbdOf(learned: List<Int>): Int {
        if (learned.isEmpty()) return 0
        val seenLevels = HashSet<Int>(learned.size)
        for (lit in learned) seenLevels.add(levelOf(Lit.variable(lit)))
        return seenLevels.size
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
            val lvl = levelOf(Lit.variable(lit))
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }
}
