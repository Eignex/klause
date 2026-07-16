package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList

/**
 * First-UIP (Unique Implication Point) conflict analyzer — the classical CDCL clause-learning
 * routine (Marques-Silva-Sakallah 1996, popularised by Chaff / MiniSAT). Walks the
 * implication graph backwards from a conflict, resolving each step against the antecedent
 * literals of the resolved variable, until exactly one variable at the current decision level
 * remains — the UIP. The conjunction of negated literals on the learned clause is forbidden by
 * the original problem, so adding it prunes any future search path that would re-derive the
 * same conflict.
 *
 * This class is the graph-walking driver: it implements [ReasonGraph] (the frozen implication
 * graph — variable universe, decision levels, antecedents), seeds the analysis from a conflict
 * reason, and selects the pivot to resolve at each step in reverse-assignment order along the
 * pin trail. The "reason → resolvent" step — folding an antecedent into the accumulating nogood,
 * detecting the UIP, and materialising the result — is delegated to a [ConflictResolvent]
 * ([ClauseResolvent] by default), so the same driver serves any learned-constraint algebra (#1119).
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

    /** The clause resolvent — the default, and the sound fallback when PB resolution can't proceed. */
    private val clauseResolvent: ConflictResolvent = ClauseResolvent(state, this)

    /** The pseudo-Boolean cutting-planes resolvent (#1119 Phase 3), built only when PB learning is on and
     *  the problem is pure-Boolean (order-literal atoms carry no PB reason). */
    private val pbResolvent: PbConflictResolvent? =
        if (state.pbLearning && state.problem.numIntVars == 0) PbConflictResolvent(state, this) else null

    /** The resolvent that produced the most recent result — its bump sets feed VSIDS. */
    private var lastResolvent: ConflictResolvent = clauseResolvent

    // Reusable per-analysis scratch — [universe] is the live var space (bool vars + atoms) for the
    // current analysis, exposed to the minimizer and resolvent through [universeSize].
    private var universe = 0

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

    /** Bool vars seen during the last analysis (the VSIDS bump set). Valid only when the
     *  last call returned [AnalysisResult.Learned]; cleared at the start of each analysis. */
    fun lastBumpBoolVars(): IntArrayList = lastResolvent.bumpBoolVars

    /** Underlying int vars seen during the last analysis (via touched atom-lits). */
    fun lastBumpIntVars(): IntArrayList = lastResolvent.bumpIntVars

    override fun universeSize(): Int = universe

    sealed interface AnalysisResult {
        /** A learned constraint (clause or pseudo-Boolean) the engine can backjump on and store.
         *  Shared shape so the driver, the backjump handler, and the learned database treat clause and
         *  PB nogoods uniformly (#1119 Phase 3). */
        sealed interface LearnedConstraint : AnalysisResult {
            /** Level to pop the trail back to so the constraint becomes asserting. */
            val backjumpLevel: Int

            /** Literal Block Distance (distinct decision levels; lower ⇒ kept longer). */
            val lbd: Int

            /** Distinct decision levels touched, sorted ascending (assumption-core projection). */
            val decisionLevels: IntArray

            /** True iff popping to [backjumpLevel] makes the constraint unit-propagate its asserting literal. */
            val asserting: Boolean

            /** Literals used by the engine's assert guards (already-true check) and the relearn-cycle hash. */
            val guardLiterals: IntArray
        }

        /** A learned conflict clause with its backjump target and glue metric. */
        data class Learned(
            /** The learned clause (disjunction of literals); at least one must hold beyond the conflict point. */
            val literals: IntArray,
            /** Level to pop the trail back to; the clause is unit there, forcing the asserting literal. */
            override val backjumpLevel: Int,
            /** Literal Block Distance: distinct decision levels in [literals] (lower ⇒ glue-like, kept longer). */
            override val lbd: Int,
            /** Distinct decision levels appearing in [literals]. Sorted ascending. Used
             *  by the engine to project a conflict back to the subset of assumption-
             *  level pins (decision levels 1..|seed|) that participated — feeds the
             *  assumption-core extraction path in [com.eignex.klause.solver.result.satisfyUnderAssumptions]. */
            override val decisionLevels: IntArray,
            /** True iff the clause is a proper 1UIP clause — exactly one literal at the
             *  conflict level — so that after popping to [backjumpLevel] it becomes unit
             *  and forces its asserting literal. When false (a conflict that genuinely rests on
             *  more than one literal at the conflict level — rare since order literals became
             *  trail-resident, #708), the engine must fall back to chronological backtracking
             *  instead of trying to assert a non-unit clause. */
            override val asserting: Boolean = true,
        ) : LearnedConstraint {
            override val guardLiterals: IntArray get() = literals
            override fun equals(other: Any?): Boolean = other is Learned &&
                literals.contentEquals(other.literals) &&
                backjumpLevel == other.backjumpLevel &&
                lbd == other.lbd &&
                decisionLevels.contentEquals(other.decisionLevels)
            override fun hashCode(): Int = 31 * (31 * (31 * literals.contentHashCode() + backjumpLevel) + lbd) +
                decisionLevels.contentHashCode()
            override fun toString(): String =
                "Learned(literals=${literals.toList()}, backjumpLevel=$backjumpLevel, lbd=$lbd, " +
                    "levels=${decisionLevels.toList()})"
        }

        /**
         * A learned pseudo-Boolean constraint `Σ weightsᵢ·literalsᵢ ≥ degree` (all weights > 0) derived by
         * cutting-planes conflict analysis (#1119 Phase 3). Stronger than any single clause the same
         * conflict could yield; materialized into the learned database as a [com.eignex.klause.factor.bool
         * .PseudoBooleanPropagator].
         */
        data class LearnedPb(
            val weights: LongArray,
            val literals: IntArray,
            val degree: Long,
            override val backjumpLevel: Int,
            override val lbd: Int,
            override val decisionLevels: IntArray,
            override val asserting: Boolean = true,
        ) : LearnedConstraint {
            override val guardLiterals: IntArray get() = literals
            override fun equals(other: Any?): Boolean = other is LearnedPb &&
                weights.contentEquals(other.weights) &&
                literals.contentEquals(other.literals) &&
                degree == other.degree &&
                backjumpLevel == other.backjumpLevel
            override fun hashCode(): Int =
                31 * (31 * (31 * weights.contentHashCode() + literals.contentHashCode()) + degree.hashCode()) +
                    backjumpLevel
            override fun toString(): String =
                "LearnedPb(Σw·ℓ≥$degree, lits=${literals.toList()}, weights=${weights.toList()}, " +
                    "backjumpLevel=$backjumpLevel, lbd=$lbd)"
        }

        /** Analysis couldn't produce a usable nogood (no conflict reason, or non-Clause failure). */
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
        return analyzeFromSeed(seedReason, conflictLevelOf(seedReason), seedFactorId = conflictFactorId)
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

    /**
     * Drive 1UIP from [seedReason]. When pseudo-Boolean learning is on, run the cutting-planes resolvent
     * first; if it reports [PbConflictResolvent.failed] (a reason it can't handle, or arithmetic
     * overflow) re-run the same conflict through the always-sound clause resolvent. [seedFactorId] is the
     * failing factor's id (so the PB resolvent can load its coefficient-carrying constraint), or -1 for an
     * externally-supplied clause seed.
     */
    private fun analyzeFromSeed(seedReason: IntArray, currentLevel: Int, seedFactorId: Int = -1): AnalysisResult {
        if (currentLevel <= 0) return AnalysisResult.NotApplicable

        val numBoolVars = state.problem.numBoolVars
        val atomCount = state.atoms.intVar.size
        universe = numBoolVars + atomCount
        if (atomLevelStamp.size < atomCount) {
            atomLevelStamp = IntArray(atomCount)
            atomLevelMemo = IntArray(atomCount)
            atomLevelEpoch = 0 // fresh arrays read as epoch 0, so don't start at 0
        }
        atomLevelEpoch++

        pbResolvent?.let { pb ->
            pb.seedFactorId = seedFactorId
            val r = runResolution(pb, seedReason, currentLevel)
            if (!pb.failed) {
                lastResolvent = pb
                return r
            }
        }
        lastResolvent = clauseResolvent
        return runResolution(clauseResolvent, seedReason, currentLevel)
    }

    /**
     * The 1UIP loop over one [resolvent]. The frontier (owned by [resolvent]) spans
     * [0, numBoolVars + atomCount): low indices are bool vars, high indices are virtual atom-vars. Both
     * share the unified pin trail [PropagationState.boolPinOrder] for reverse-order pivot selection; an
     * atom materialised mid-analysis has no pin position and is swept from the
     * [ConflictResolvent.offTrailFrontier] fallback after the trail is exhausted at currentLevel.
     */
    private fun runResolution(resolvent: ConflictResolvent, seedReason: IntArray, currentLevel: Int): AnalysisResult {
        val numBoolVars = state.problem.numBoolVars
        resolvent.reset(universe)

        resolvent.resolve(seedReason, currentLevel)

        if (resolvent.liveAtCurrentLevel == 0) {
            return resolvent.finalizeResult(currentLevel)
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
            // Resolved / lower-level literals (no longer frontier) are skipped. `scanFrom` is the
            // trail top on a re-armed rescan, else the descending cursor.
            var pivot = -1
            var pivotPos = -1
            val scanFrom = if (rescanFromTop) state.boolPinOrder.size - 1 else pinCursor
            for (i in scanFrom downTo 0) {
                val v = state.boolPinOrder[i]
                if (!resolvent.isFrontier(v)) continue
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
                // [ConflictResolvent.isFrontier] recheck.
                val offTrail = resolvent.offTrailFrontier
                for (k in 0 until offTrail.size) {
                    val v = offTrail[k]
                    if (resolvent.isFrontier(v) && cachedAtomLevel(v - numBoolVars) == currentLevel) {
                        pivot = v
                        break
                    }
                }
                if (pivot < 0) break
                // Off-trail pivot: its derived antecedents may cite trail literals above the cursor,
                // so re-arm the full rescan for the next iteration.
                rescanFromTop = true
            }
            resolvent.resolveOut(pivot)
            if (resolvent.liveAtCurrentLevel == 0) {
                resolvent.addAsserting(pivot)
                return resolvent.finalizeResult(currentLevel)
            }
            val antecedents = antecedentsOf(pivot)
                ?: run {
                    // Leaf pivot — promote and drain the rest.
                    resolvent.addAsserting(pivot)
                    resolvent.drainFrontier()
                    return resolvent.finalizeResult(currentLevel)
                }
            resolvent.resolve(antecedents, currentLevel)
        }
        resolvent.drainFrontier()
        return resolvent.finalizeResult(currentLevel)
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
     * Unified level lookup that handles both bool vars (via [PropagationState.boolLevel])
     * and atom vars. Atom levels come from [PropagationState.atomLevelForConflict] — the
     * trail-resident establishment level on the current path, which is consistent for the
     * whole analysis (the path is frozen) and so yields a sound backjump level / LBD / asserting
     * flag (#76).
     */
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
}
