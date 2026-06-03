package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList

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
internal class ConflictAnalyzer internal constructor(private val state: PropagationState) {

    // Reusable per-analysis scratch — grown once and cleared per call instead of
    // reallocating three O(numVars) BooleanArrays on every conflict. [universe] is the live
    // var space (bool vars + atoms) for the current analysis; all loops bound by it, since
    // a buffer may be larger than the current universe after a deeper earlier conflict.
    private var universe = 0
    private var seen = BooleanArray(0)

    // Variables already resolved out as a pivot this analysis. Int-atom antecedents are
    // walked in allocation order (atoms have no trail order), so unlike the bool implication
    // graph they can present a same-level cycle (A's reason mentions B and vice-versa). Once
    // a var has been resolved we must never re-ingest it, or the 1UIP loop ping-pongs forever
    // (and grows [bumpIntVars] until OOM). In the acyclic bool case this never triggers.
    private var resolved = BooleanArray(0)
    private var inClause = BooleanArray(0)
    private var toDrop = BooleanArray(0)

    // Variables encountered (resolved through or kept) during the most recent analysis —
    // the canonical CDCL VSIDS bump set (MiniSAT/Glucose bump every var seen while walking
    // the implication graph, not just the decision vars at the conflict levels). Recorded
    // as a side effect of [ingestReason]; bool-var ids in [bumpBool], underlying int-var
    // ids (decoded from touched atoms) in [bumpInt]. Reused across analyses to avoid
    // per-conflict allocation; the engine reads them after [analyze] when a clause is learned.
    private val bumpBoolVars = IntArrayList()
    private val bumpIntVars = IntArrayList()

    /** Bool vars seen during the last analysis (the VSIDS bump set). Valid only when the
     *  last call returned [AnalysisResult.Learned]; cleared at the start of each analysis. */
    fun lastBumpBoolVars(): IntArrayList = bumpBoolVars

    /** Underlying int vars seen during the last analysis (via touched atom-lits). */
    fun lastBumpIntVars(): IntArrayList = bumpIntVars

    /** Return [arr] if already ≥ [n], else a fresh array; either way clear `[0, n)` to false. */
    private fun scratch(arr: BooleanArray, n: Int): BooleanArray {
        val a = if (arr.size >= n) arr else BooleanArray(n)
        a.fill(false, 0, n)
        return a
    }

    sealed interface AnalysisResult {
        /** A learned conflict clause with its backjump target and glue metric. */
        data class Learned(
            /** The learned clause (disjunction of literals); at least one must hold beyond the conflict point. */
            val literals: IntArray,
            /** Level to pop the trail back to; the clause is unit there, forcing the asserting literal. */
            val backjumpLevel: Int,
            /** Literal Block Distance: distinct decision levels in [literals] (lower ⇒ glue-like, kept longer). */
            val lbd: Int,
            /** Distinct decision levels appearing in [literals]. Sorted ascending. Used
             *  by the engine to project a conflict back to the subset of assumption-
             *  level pins (decision levels 1..|seed|) that participated — feeds the
             *  assumption-core extraction path in [com.eignex.klause.solver.satisfyUnderAssumptions]. */
            val decisionLevels: IntArray,
            /** True iff the clause is a proper 1UIP clause — exactly one literal at the
             *  conflict level — so that after popping to [backjumpLevel] it becomes unit
             *  and forces its asserting literal. When false (which can happen for int
             *  *equality* decisions, whose pin contributes two same-level bound atoms that
             *  1UIP cannot collapse to a single UIP), the engine must fall back to
             *  chronological backtracking instead of trying to assert a non-unit clause. */
            val asserting: Boolean = true,
        ) : AnalysisResult {
            override fun equals(other: Any?): Boolean = other is Learned &&
                literals.contentEquals(other.literals) &&
                backjumpLevel == other.backjumpLevel &&
                lbd == other.lbd &&
                decisionLevels.contentEquals(other.decisionLevels)
            override fun hashCode(): Int = 31 * (31 * (31 * literals.contentHashCode() + backjumpLevel) + lbd) +
                decisionLevels.contentHashCode()
            override fun toString(): String =
                "Learned(literals=${literals.toList()}, backjumpLevel=$backjumpLevel, lbd=$lbd, levels=${decisionLevels.toList()})"
        }

        /** Analysis couldn't produce a clause (no conflict reason, or non-Clause failure). */
        data object NotApplicable : AnalysisResult
    }

    /**
     * Run analysis from a conflict triggered by factor [conflictFactorId]. The conflict
     * level is the deepest decision level among the seed reason's own literals, read through
     * the bound-history-accurate [levelOf] (#76/#77) — not `state.currentLevel`, which
     * runToFixpoint sets from `maxLevelForVars` over *all* of the failing factor's variables
     * (a superset of the reason) and, for atom-lit clauses, off the drifted `atomLevel`. That
     * attribution can name a level no reason literal actually sits at, which makes the 1UIP
     * loop find no pivot at the conflict level and degenerate into a non-asserting clause
     * (lost learning) or mis-target backjumpLevelOf. Taking the max over the reason's own
     * literals pins the conflict level exactly at its deepest literal. (The engine's levelling
     * is max-antecedent based, so no reason literal — nor any antecedent resolved in below —
     * sits above this, keeping the asserting/backjump computation sound.)
     */
    fun analyze(conflictFactorId: Int): AnalysisResult {
        val factor = state.factorAt(conflictFactorId)
        val seedReason = factor.conflictReason(state, conflictFactorId) ?: return AnalysisResult.NotApplicable
        return analyzeFromSeed(seedReason, conflictLevelOf(seedReason))
    }

    /** Deepest accurate decision level among [reason]'s literals — the conflict level for a
     *  factor-seeded analysis (see [analyze]). */
    private fun conflictLevelOf(reason: IntArray): Int {
        var max = 0
        for (lit in reason) {
            val l = levelOf(Lit.variable(lit))
            if (l > max) max = l
        }
        return max
    }

    /**
     * Decision-vs-prior-pin conflict path: when [PropagationState.pinBoolAsDecision]
     * fails because the var was already pinned to the opposite value, no factor's
     * `propagate` fired and `currentFactor` is `-1`. The implicit violated clause is:
     *   `(prior pin's antecedents) ∨ Lit.make(v, !prior_value)`
     * — every literal currently false in the state, exactly matching the analyzer's
     * "seed reason" contract. Falls through to the same 1UIP loop as [analyze].
     *
     * The conflict level here is `state.currentLevel` — the just-attempted decision level —
     * not the seed's literal max: the conflicted var's own [PropagationState.boolLevel] is the
     * *prior* (shallower) pin, so the seed cannot reveal the decision depth that is genuinely
     * the conflict level. Holding the conflict level above the seed literals also lets the
     * minimiser resolve the decision lit away into the stronger underlying nogood.
     */
    fun analyzeDecisionConflict(conflictedVar: Int): AnalysisResult {
        val priorValue = state.boolValues[conflictedVar] ?: return AnalysisResult.NotApplicable
        val priorAnt = state.boolAntecedents[conflictedVar]
        // The just-attempted decision lit (currently false in state because the prior
        // pin still holds and pinBoolImpl rejected the new value).
        val decisionLit = Lit.make(conflictedVar, !priorValue)
        val seed = if (priorAnt == null) {
            intArrayOf(decisionLit)
        } else {
            IntArray(priorAnt.size + 1).also {
                for (i in priorAnt.indices) it[i] = priorAnt[i]
                it[priorAnt.size] = decisionLit
            }
        }
        return analyzeFromSeed(seed, state.currentLevel)
    }

    private fun analyzeFromSeed(seedReason: IntArray, currentLevel: Int): AnalysisResult {
        if (currentLevel <= 0) return AnalysisResult.NotApplicable

        // Standard 1UIP loop with bool + atom support. The `seen` array spans
        // [0, numBoolVars + atomCount): low indices are bool vars (walked via
        // boolPinOrder), high indices are virtual atom-vars (resolved via
        // atomAntecedents — atoms have no chronological pin order, just allocation order,
        // so we sweep them after the bool trail is exhausted at currentLevel).
        val numBoolVars = state.problem.numBoolVars
        val atomCount = state.atomIntVar.size
        universe = numBoolVars + atomCount
        seen = scratch(seen, universe)
        resolved = scratch(resolved, universe)
        bumpBoolVars.clear()
        bumpIntVars.clear()
        var currentLevelCount = 0
        val learned = IntArrayList(seedReason.size)

        ingestReason(seedReason, learned, currentLevel) {
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
                if (seen[v] && state.boolLevel[v] == currentLevel) {
                    pivot = v
                    break
                }
            }
            if (pivot < 0) {
                // Look for an atom pivot at currentLevel. Use the bound-history-derived level
                // (not the drifted [atomLevel]) so a stale level can't hide a genuine
                // current-level pivot or surface a spurious one (#76).
                for (id in 0 until atomCount) {
                    val v = numBoolVars + id
                    if (seen[v] && state.atomLevelForConflict(id) == currentLevel) {
                        pivot = v
                        break
                    }
                }
                if (pivot < 0) break
            }
            seen[pivot] = false
            resolved[pivot] = true
            currentLevelCount--
            if (currentLevelCount == 0) {
                learned.add(uipLit(pivot))
                return finalizeClause(learned, currentLevel)
            }
            val antecedents = antecedentsOf(pivot)
                ?: run {
                    // Leaf pivot — promote and drain the rest.
                    learned.add(uipLit(pivot))
                    drainSeenAsLeaves(learned)
                    return finalizeClause(learned, currentLevel)
                }
            ingestReason(antecedents, learned, currentLevel) {
                currentLevelCount++
            }
        }
        drainSeenAsLeaves(learned)
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
        return if (v < numBoolVars) {
            state.boolAntecedents[v]
        } else {
            state.atomAntecedents[v - numBoolVars]
        }
    }

    /**
     * Apply self-subsuming-resolution minimization, then compute backjump level + LBD
     * on the final clause and wrap into [AnalysisResult.Learned]. Single tail call from
     * every exit path of [analyze] so all exit shapes get the same post-processing.
     */
    private fun finalizeClause(learned: IntArrayList, currentLevel: Int): AnalysisResult.Learned {
        val minimized = minimize(learned, currentLevel)
        val levels = distinctLevelsOf(minimized)
        // A proper 1UIP clause carries exactly one literal at the conflict level; that lone
        // literal becomes the unit-asserting literal after the backjump. Some conflicts —
        // notably those seeded by an int *equality* decision, whose pin contributes two
        // same-level bound atoms (`v ≥ k` and `v ≤ k`) that have no antecedents to resolve
        // against — leave more than one literal at the conflict level. Such a clause is not
        // unit after any backjump, so the engine must not try to assert it.
        var atConflictLevel = 0
        for (i in 0 until minimized.size) {
            if (levelOf(Lit.variable(minimized[i])) == currentLevel) atConflictLevel++
        }
        return AnalysisResult.Learned(
            minimized.toIntArray(),
            backjumpLevelOf(minimized, currentLevel),
            levels.size,
            levels,
            asserting = atConflictLevel == 1,
        )
    }

    /** Sorted-ascending array of distinct decision levels touched by [learned]. Shares
     *  its scan with [lbdOf] (whose count is just `levels.size`); finalize computes both
     *  in one pass via this helper. */
    private fun distinctLevelsOf(learned: IntArrayList): IntArray {
        if (learned.size == 0) return IntArray(0)
        val seen = HashSet<Int>(learned.size)
        for (i in 0 until learned.size) seen.add(levelOf(Lit.variable(learned[i])))
        val out = seen.toIntArray()
        out.sort()
        return out
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
    private fun minimize(learned: IntArrayList, currentLevel: Int): IntArrayList {
        if (learned.size <= 1) return learned
        val universeSize = universe
        inClause = scratch(inClause, universeSize)
        for (i in 0 until learned.size) {
            val v = Lit.variable(learned[i])
            if (v < universeSize) inClause[v] = true
        }
        val cache = HashMap<Int, Boolean>(learned.size * 4)
        toDrop = scratch(toDrop, universeSize)
        var dropCount = 0
        for (i in 0 until learned.size) {
            val v = Lit.variable(learned[i])
            if (v >= universeSize) continue
            if (levelOf(v) == currentLevel) continue
            if (isRedundant(v, inClause, cache)) {
                toDrop[v] = true
                dropCount++
            }
        }
        if (dropCount == 0) return learned
        val out = IntArrayList(learned.size - dropCount)
        for (i in 0 until learned.size) {
            val lit = learned[i]
            val v = Lit.variable(lit)
            if (v >= universeSize || !toDrop[v]) out.add(lit)
        }
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
    private fun isRedundant(v: Int, inClause: BooleanArray, cache: HashMap<Int, Boolean>): Boolean {
        cache[v]?.let { return it }
        val antecedents = antecedentsOf(v) ?: run {
            cache[v] = false
            return false
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
     *  and atom vars. Atom levels come from [PropagationState.atomLevelForConflict] — the
     *  bound-history-derived level on the current path — never the drifted [atomLevel],
     *  which would yield an unsound backjump level / LBD / asserting flag (#76). */
    private fun levelOf(v: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) {
            state.boolLevel[v]
        } else {
            state.atomLevelForConflict(v - numBoolVars)
        }
    }

    /**
     * Add each literal in [reason] to either the working `seen` set (if it's at
     * [currentLevel] — resolution will continue through it) or directly to [learned]
     * (lower level — it's part of the final clause). Increments [bumpCurrentLevel] for
     * each new-at-current-level variable so the caller can track resolution progress.
     */
    private fun ingestReason(
        reason: IntArray,
        learned: IntArrayList,
        currentLevel: Int,
        bumpCurrentLevel: () -> Unit,
    ) {
        val numBoolVars = state.problem.numBoolVars
        for (lit in reason) {
            val v = Lit.variable(lit)
            if (v >= universe) continue // atom allocated after analyzer started; shouldn't happen
            if (seen[v] || resolved[v]) continue // already in the frontier, or resolved out (cycle guard)
            val lvl = levelOf(v)
            if (lvl <= 0) continue
            seen[v] = true
            // Record for the VSIDS bump set (every conflict-side var, MiniSAT-style).
            if (v < numBoolVars) {
                bumpBoolVars.add(v)
            } else {
                bumpIntVars.add(state.atomIntVar[v - numBoolVars])
            }
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
    private fun drainSeenAsLeaves(learned: IntArrayList) {
        val numBoolVars = state.problem.numBoolVars
        for (v in 0 until universe) {
            if (!seen[v]) continue
            val lit: Int = if (v < numBoolVars) {
                val pinned = state.boolValues[v] ?: continue
                Lit.make(v, !pinned)
            } else {
                val atomId = v - numBoolVars
                val holds = state.atomCurrentTruth(atomId) ?: continue
                Lit.make(v, !holds)
            }
            var present = false
            for (i in 0 until learned.size) {
                if (Lit.variable(learned[i]) == v) {
                    present = true
                    break
                }
            }
            if (present) continue
            learned.add(lit)
        }
    }

    /**
     * Backjump target: the second-highest decision level among the learned literals'
     * variables. The asserting literal (UIP) sits at [currentLevel]; we want to pop back
     * to the level just past the next-highest, so the learned clause becomes unit (only
     * the UIP literal remains undetermined) and propagation can re-fire it as a forced
     * pin.
     */
    private fun backjumpLevelOf(learned: IntArrayList, currentLevel: Int): Int {
        var best = 0
        for (i in 0 until learned.size) {
            val lvl = levelOf(Lit.variable(learned[i]))
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }
}
