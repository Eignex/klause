package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyBooleanArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * First-UIP (Unique Implication Point) conflict analyzer — the classical CDCL clause-learning
 * routine (Marques-Silva-Sakallah 1996, popularised by Chaff / MiniSAT). Walks the
 * implication graph backwards from a conflict, resolving each step against the antecedent
 * literals of the resolved variable, until exactly one variable at the current decision level
 * remains — the UIP. The conjunction of negated literals on the learned clause is forbidden by
 * the original problem, so adding it prunes any future search path that would re-derive the
 * same conflict.
 *
 * The implication graph spans **bool vars and int order literals** uniformly: bool antecedents
 * come from [PropagationState.boolAntecedents], order-literal antecedents from each atom's
 * trail-resident reason ([AtomStore.ant], with the [atomAntecedentsDerived] fallback
 * for atoms materialised mid-analysis). Factors emit per-factor [Propagator.conflictReason]s
 * (the #651 explanation pillar) and record antecedents on every force, so the analyzer resolves
 * over the full reason graph rather than treating non-clause forces as leaves. A variable with
 * `null` antecedents is a genuine leaf (decision / assumption / root fact).
 *
 * The output is an [AnalysisResult]: either a [AnalysisResult.Learned] carrying the
 * learned-clause literals and the backjump level (= second-highest decision level among
 * the literals; the level the engine should non-chronologically jump to), or
 * [AnalysisResult.NotApplicable] when the analyzer can't produce a usable nogood (the
 * conflict sits at the root).
 */
internal class ConflictAnalyzer internal constructor(private val state: PropagationState) : ReasonGraph {

    /** Learned-clause post-processing (self-subsuming + binary minimization); reads levels and
     *  antecedents back through this analyzer's [ReasonGraph] view so its atom-level memo is shared. */
    private val minimizer = ClauseMinimizer(state, this)

    // Reusable per-analysis scratch — grown once and cleared per call instead of
    // reallocating O(numVars) BooleanArrays on every conflict. [universe] is the live
    // var space (bool vars + atoms) for the current analysis; all loops bound by it, since
    // a buffer may be larger than the current universe after a deeper earlier conflict.
    private var universe = 0
    private var seen = EmptyBooleanArray

    // Per-analysis memo of atomLevelForConflict (#561). Within one analysis the search path is
    // frozen — domains are never mutated, only the implication graph is walked — so an atom's
    // level is invariant. A determined atom on the current path reads it straight off its trail
    // slot (O(1)); the memo covers the remaining reconstruct/hole-record path for atoms not
    // carrying a stored level, which the same atom can hit repeatedly across reasons. Caching by
    // atom id with an epoch stamp keeps those repeats O(1); atomLevelEpoch is bumped per analysis
    // to invalidate.
    private var atomLevelMemo = EmptyIntArray
    private var atomLevelStamp = EmptyIntArray
    private var atomLevelEpoch = 0

    // Variables already resolved out as a pivot this analysis. Order literals established on the
    // current path carry a real trail position (#708) and resolve in reverse-assignment order like
    // bools, but one materialised *mid-analysis* (an opposing bound a reason cites, never woken) has
    // no trail position and its derived antecedents can present a same-level cycle (A's reason
    // mentions B and vice-versa). Once a var has been resolved we must never re-ingest it, or the
    // 1UIP loop ping-pongs forever (and grows [bumpIntVars] until OOM). In the acyclic bool case
    // this never triggers.
    private var resolved = EmptyBooleanArray

    // Variables encountered (resolved through or kept) during the most recent analysis —
    // the canonical CDCL VSIDS bump set (MiniSAT/Glucose bump every var seen while walking
    // the implication graph, not just the decision vars at the conflict levels). Recorded
    // as a side effect of [ingestReason]; bool-var ids in [bumpBool], underlying int-var
    // ids (decoded from touched atoms) in [bumpInt]. Reused across analyses to avoid
    // per-conflict allocation; the engine reads them after [analyze] when a clause is learned.
    private val bumpBoolVars = IntArrayList()
    private val bumpIntVars = IntArrayList()

    // Atom-vars marked seen this analysis. The 1UIP pivot scan walks the unified pin trail
    // ([PropagationState.boolPinOrder]); this list is the fallback frontier for atoms that are seen
    // but NOT on the trail — ones materialised mid-analysis (off the trail, no pin position) — so
    // the scan can still find them without sweeping all `atomCount` atoms (O(frontier), not
    // O(total)). A superset of the currently-seen atoms (never pruned), so the scan re-checks
    // `seen[v]`. Cleared per analysis.
    private val seenAtomList = IntArrayList()

    // O(1) membership index for the leaf-literal dedup in [ingestReason] / [drainSeenAsLeaves],
    // replacing per-literal linear scans of `learned` that made analysis quadratic in clause size.
    // Every literal added is `Lit.make(v, !currentTruth(v))` — one literal per variable, so this is
    // equivalent to a by-variable dedup. Reused across analyses, cleared per call.
    private val litsInLearned = IntHashSet()

    /** Bool vars seen during the last analysis (the VSIDS bump set). Valid only when the
     *  last call returned [AnalysisResult.Learned]; cleared at the start of each analysis. */
    fun lastBumpBoolVars(): IntArrayList = bumpBoolVars

    /** Underlying int vars seen during the last analysis (via touched atom-lits). */
    fun lastBumpIntVars(): IntArrayList = bumpIntVars

    override fun universeSize(): Int = universe

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
             *  assumption-core extraction path in [com.eignex.klause.solver.result.satisfyUnderAssumptions]. */
            val decisionLevels: IntArray,
            /** True iff the clause is a proper 1UIP clause — exactly one literal at the
             *  conflict level — so that after popping to [backjumpLevel] it becomes unit
             *  and forces its asserting literal. When false (a conflict that genuinely rests on
             *  more than one literal at the conflict level — rare since order literals became
             *  trail-resident, #708), the engine must fall back to chronological backtracking
             *  instead of trying to assert a non-unit clause. */
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
     * (a superset of the reason). That
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

    /**
     * Run 1UIP from an externally supplied conflict clause whose literals are all currently false —
     * e.g. an LP infeasibility (Farkas) certificate over absolute bound atoms (#247/#280). The clause
     * is a valid seed reason: its disjunction is violated under the current assignment, exactly the
     * contract [analyze] feeds the 1UIP loop. The conflict level is the deepest accurate decision
     * level among its literals, as in [analyze], so the learned clause asserts at the right level and
     * backjumps non-chronologically. Returns [AnalysisResult.NotApplicable] when the conflict sits at
     * the root (nothing to learn) or 1UIP cannot collapse it to an asserting clause.
     */
    fun analyzeConflictClause(conflictClause: IntArray): AnalysisResult =
        analyzeFromSeed(conflictClause, conflictLevelOf(conflictClause))

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
        // [0, numBoolVars + atomCount): low indices are bool vars, high indices are virtual
        // atom-vars. Both share the unified pin trail [boolPinOrder] for reverse-order pivot
        // selection; an atom materialised mid-analysis has no pin position and is swept from the
        // [seenAtomList] fallback after the trail is exhausted at currentLevel.
        val numBoolVars = state.problem.numBoolVars
        val atomCount = state.atoms.intVar.size
        universe = numBoolVars + atomCount
        seen = scratch(seen, universe)
        resolved = scratch(resolved, universe)
        if (atomLevelStamp.size < atomCount) {
            atomLevelStamp = IntArray(atomCount)
            atomLevelMemo = IntArray(atomCount)
            atomLevelEpoch = 0 // fresh arrays read as epoch 0, so don't start at 0
        }
        atomLevelEpoch++
        seenAtomList.clear()
        litsInLearned.clear()
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

        // Pin-trail cursor for the 1UIP pivot scan. The pivot is always the most-recent still-seen
        // current-level literal (reverse-assignment order); under single establishment (#708) a
        // reason cites only earlier-established (lower-position) literals, so resolving the pivot at
        // position `p` marks new frontier literals strictly below `p` and the next pivot is at or
        // below `p`. The cursor therefore descends monotonically across the analysis (O(trail) total)
        // instead of re-scanning the whole trail every iteration (O(trail) per resolution). The only
        // literal that can become seen *above* the cursor is one cited by a mid-analysis-materialised
        // atom (the off-trail fallback below): it has no pin position, so its derived antecedents may
        // touch the trail anywhere. After any fallback pivot we re-arm a single full rescan so that
        // "becomes-seen-behind" case is caught exactly — the residual non-asserting pathology the old
        // monotonic cursor left, now confined to the (rare) off-trail case (#612 follow-up).
        var pinCursor = state.boolPinOrder.size - 1
        var rescanFromTop = true
        while (true) {
            // Resolved / lower-level literals (`seen` cleared) are skipped. `scanFrom` is the trail
            // top on a re-armed rescan, else the descending cursor.
            var pivot = -1
            var pivotPos = -1
            val scanFrom = if (rescanFromTop) state.boolPinOrder.size - 1 else pinCursor
            for (i in scanFrom downTo 0) {
                val v = state.boolPinOrder[i]
                if (!seen[v]) continue
                val lvl = if (v < numBoolVars) state.boolLevel[v] else cachedAtomLevel(v - numBoolVars)
                if (lvl == currentLevel) {
                    pivot = v
                    pivotPos = i
                    break
                }
            }
            if (pivot >= 0) {
                // Trail pivot: its antecedents land strictly below `pivotPos`, so the next pivot is
                // at or below it — descend the cursor and keep scanning from there.
                pinCursor = pivotPos - 1
                rescanFromTop = false
            } else {
                // Fallback for an atom materialised mid-analysis — cited by a derived reason, never
                // woken, hence absent from the pin trail. Scan the seen-atom frontier by its
                // [atomLevelForConflict]-derived level. Stale / duplicate entries are skipped by the
                // `seen[v]` recheck.
                for (k in 0 until seenAtomList.size) {
                    val v = seenAtomList[k]
                    if (seen[v] && cachedAtomLevel(v - numBoolVars) == currentLevel) {
                        pivot = v
                        break
                    }
                }
                if (pivot < 0) break
                // Off-trail pivot: its derived antecedents may cite trail literals above the cursor,
                // so re-arm the full rescan for the next iteration.
                rescanFromTop = true
            }
            seen[pivot] = false
            resolved[pivot] = true
            currentLevelCount--
            if (currentLevelCount == 0) {
                addLearned(learned, uipLit(pivot))
                return finalizeClause(learned, currentLevel)
            }
            val antecedents = antecedentsOf(pivot)
                ?: run {
                    // Leaf pivot — promote and drain the rest.
                    addLearned(learned, uipLit(pivot))
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

    /** Append [lit] to [learned] and record it in [litsInLearned] so the leaf-literal dedup stays
     *  O(1). Every literal reaches the clause through here, keeping the index exact. */
    private fun addLearned(learned: IntArrayList, lit: Int) {
        learned.add(lit)
        litsInLearned.add(lit)
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

    /** Antecedents of `v`, or null when `v` is a decision/leaf — or when `v` falls outside
     *  the current antecedent universe. Out-of-range atom ids can be reached only through the
     *  recursive antecedent walk in the [ClauseMinimizer] redundancy DFS (the 1UIP loop stays within `seen`/`resolved`
     *  bounds); treating them as antecedent-less leaves keeps the literal, which is always sound
     *  for minimization, rather than indexing past the atom table. */
    override fun antecedentsOf(v: Int): IntArray? {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) {
            if (v < 0) null else state.boolAntecedents[v]
        } else {
            val atomId = v - numBoolVars
            if (atomId < state.atoms.intVar.size) state.atomAntecedentsDerived(atomId) else null
        }
    }

    /**
     * Apply self-subsuming-resolution minimization, then compute backjump level + LBD
     * on the final clause and wrap into [AnalysisResult.Learned]. Single tail call from
     * every exit path of [analyze] so all exit shapes get the same post-processing.
     */
    private fun finalizeClause(learned: IntArrayList, currentLevel: Int): AnalysisResult.Learned {
        val minimized = minimizer.reduce(learned, currentLevel)
        val levels = distinctLevelsOf(minimized)
        // A proper 1UIP clause carries exactly one literal at the conflict level; that lone
        // literal becomes the unit-asserting literal after the backjump. A conflict that genuinely
        // rests on several literals at the conflict level (rare since order literals became
        // trail-resident, #708) leaves more than one — such a clause is not unit after any
        // backjump, so the engine must not try to assert it.
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
     *  its scan with `lbdOf` (whose count is just `levels.size`); finalize computes both
     *  in one pass via this helper. */
    private fun distinctLevelsOf(learned: IntArrayList): IntArray {
        if (learned.size == 0) return EmptyIntArray
        val seen = IntHashSet(learned.size)
        for (i in 0 until learned.size) seen.add(levelOf(Lit.variable(learned[i])))
        val out = seen.toIntArray()
        out.sort()
        return out
    }

    /** Unified level lookup that handles both bool vars (via [PropagationState.boolLevel])
     *  and atom vars. Atom levels come from [PropagationState.atomLevelForConflict] — the
     *  trail-resident establishment level on the current path, which is consistent for the
     *  whole analysis (the path is frozen) and so yields a sound backjump level / LBD / asserting
     *  flag (#76). */
    override fun levelOf(v: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) {
            state.boolLevel[v]
        } else {
            cachedAtomLevel(v - numBoolVars)
        }
    }

    /** [PropagationState.atomLevelForConflict] with a per-analysis memo (#561): the level is
     *  invariant during one analysis (the path is frozen), so repeat queries — common, since the
     *  same atom recurs across reasons — return the cached value instead of re-running the
     *  reconstruct/hole-record derivation. Atoms materialised mid-analysis (id past the memo arrays)
     *  fall through to the direct call. */
    private fun cachedAtomLevel(id: Int): Int {
        if (id >= atomLevelStamp.size) return state.atomLevelForConflict(id)
        if (atomLevelStamp[id] == atomLevelEpoch) return atomLevelMemo[id]
        val lv = state.atomLevelForConflict(id)
        atomLevelMemo[id] = lv
        atomLevelStamp[id] = atomLevelEpoch
        return lv
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
            if (v >= universe) {
                // An atom materialised mid-analysis — derived antecedents allocate the
                // opposing-bound atoms they cite. It has no frontier slot, so keep the
                // literal in the clause as a leaf (deduped via [litsInLearned]): adding a
                // literal only weakens the clause, while dropping it would silently strengthen
                // the nogood past what was derived.
                if (!litsInLearned.contains(lit)) {
                    addLearned(learned, lit)
                    if (levelOf(v) == currentLevel) bumpCurrentLevel()
                }
                continue
            }
            if (seen[v]) continue // already in the frontier
            if (resolved[v]) {
                // Resolved out as a pivot already. The bool implication graph is acyclic, so a
                // resolved bool never legitimately recurs and is safely skipped. Atom antecedents
                // have no trail order and can form same-level cycles (see [resolved]); a resolved
                // atom can recur as a genuine premise — typically the opposite-polarity bound of
                // the same int var. Skipping it then drops a literal the nogood needs, producing an
                // unsound clause that prunes feasible solutions and over-proves optimality.
                // Keep that literal instead (deduped via [litsInLearned]). Re-resolving the atom
                // would risk the ping-pong the guard prevents; merely adding a literal only weakens
                // the clause, so it stays sound. A second current-level literal makes the clause
                // non-asserting, which [finalizeClause] flags so the engine backtracks chronologically.
                if (v >= numBoolVars && !litsInLearned.contains(lit)) {
                    addLearned(learned, lit)
                }
                continue
            }
            val lvl = levelOf(v)
            if (lvl <= 0) continue
            seen[v] = true
            // Record for the VSIDS bump set (every conflict-side var, MiniSAT-style).
            if (v < numBoolVars) {
                bumpBoolVars.add(v)
            } else {
                bumpIntVars.add(state.atoms.intVar[v - numBoolVars])
                seenAtomList.add(v) // frontier atom — candidate for the 1UIP atom-pivot scan
            }
            if (lvl == currentLevel) {
                bumpCurrentLevel()
            } else {
                if (v < numBoolVars) {
                    val pinned = state.boolValues[v] ?: error("seen var $v not pinned")
                    addLearned(learned, Lit.make(v, !pinned))
                } else {
                    val atomId = v - numBoolVars
                    val holds = state.atomCurrentTruth(atomId)
                        ?: error("ingest atom $atomId at lower level undetermined")
                    addLearned(learned, Lit.make(v, !holds))
                }
            }
        }
    }

    /** Convert every still-seen variable into a literal in [learned], deduped via [litsInLearned]. */
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
            if (!litsInLearned.contains(lit)) addLearned(learned, lit)
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
