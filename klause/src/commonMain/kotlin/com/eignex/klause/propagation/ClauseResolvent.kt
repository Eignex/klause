package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.EmptyBooleanArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * The classical clause resolvent: the accumulating disjunction of literals that 1UIP resolution builds,
 * resolved by set union over the negated antecedent literals. This is the default (and, until pseudo-Boolean
 * cutting planes land, only) [ConflictResolvent].
 *
 * The frontier is the `seen` set of variables whose resolution is not yet finished; a variable leaves the
 * frontier either by being resolved through as a pivot ([resolveOut]) or by dropping into the nogood as a
 * lower-level literal. [ReasonGraph.levelOf] / [ReasonGraph.antecedentsOf] and the per-analysis atom-level
 * memo live on the driving [ReasonGraph]; this resolvent reads them through [graph] so the level view is
 * shared with the driver's pivot scan and the [ClauseMinimizer].
 */
internal class ClauseResolvent(private val state: PropagationState, private val graph: ReasonGraph) :
    ConflictResolvent {

    /** Learned-clause post-processing (self-subsuming + binary minimization); reads levels and
     *  antecedents back through the shared [ReasonGraph] view so its atom-level memo is shared. */
    private val minimizer = ClauseMinimizer(state, graph)

    // Reusable per-analysis scratch — grown once and cleared per call instead of reallocating
    // O(numVars) BooleanArrays on every conflict. [universe] is the live var space (bool vars + atoms)
    // for the current analysis; all loops bound by it, since a buffer may be larger than the current
    // universe after a deeper earlier conflict.
    private var universe = 0
    private var seen = EmptyBooleanArray

    // Variables already resolved out as a pivot this analysis. Every frontier variable at the conflict
    // level carries a pin trail position and the driver resolves them in reverse-assignment order, so a
    // resolved variable's own premises all sit below it and it can never recur as a genuine premise. An
    // order literal with no trail position never joins the frontier at the conflict level: it is either
    // resolved through where it is cited ([substituteOffTrail]) or kept as a leaf. Re-ingesting a resolved
    // variable would let the 1UIP loop ping-pong forever (and grow [bumpIntVars] until OOM), so the
    // guard stays.
    private var resolved = EmptyBooleanArray

    // Variables encountered (resolved through or kept) during the most recent analysis —
    // the canonical CDCL VSIDS bump set (every var seen while walking the implication
    // graph, not just the decision vars at the conflict levels). Recorded
    // as a side effect of [resolve]; bool-var ids in [bumpBoolVars], underlying int-var
    // ids (decoded from touched atoms) in [bumpIntVars]. Reused across analyses to avoid
    // per-conflict allocation; the engine reads them after [analyze] when a clause is learned.
    override val bumpBoolVars = IntArrayList()
    override val bumpIntVars = IntArrayList()

    // Atom-vars marked seen this analysis. The 1UIP pivot scan walks the unified pin trail
    // ([PropagationState.boolPinOrder]); this list is the fallback frontier for atoms that are seen
    // but NOT on the trail — ones materialised mid-analysis (off the trail, no pin position) — so
    // the scan can still find them without sweeping all `atomCount` atoms (O(frontier), not
    // O(total)). A superset of the currently-seen atoms (never pruned), so the scan re-checks
    // [isFrontier]. Cleared per analysis.
    override val offTrailFrontier = IntArrayList()

    // Literals waiting to be folded in. [resolve] pushes the reason here and drains it, so substituting
    // an order literal for its own premises costs no recursion depth however long the derived-reason
    // chain gets. Reused across analyses, cleared per call.
    private val pending = IntArrayList()

    // Order literals already resolved through by substitution this analysis — the [resolved] role for
    // variables that have no frontier slot. Keyed by var id rather than indexed by [universe] because
    // building a derived reason materialises atoms, so ids grow past the universe the analysis started
    // with.
    private val substituted = IntHashSet()

    // O(1) membership index for the leaf-literal dedup in [resolve] / [drainFrontier], replacing
    // per-literal linear scans of `learned` that made analysis quadratic in clause size. Every
    // literal added is `Lit.make(v, !currentTruth(v))` — one literal per variable, so this is
    // equivalent to a by-variable dedup. Reused across analyses, cleared per call.
    private val litsInLearned = IntHashSet()

    // The accumulating nogood (disjunction of literals). Reused across analyses (cleared per call)
    // rather than reallocated, since a conflict is on the hot path; [finalizeResult] copies it out
    // to an IntArray, so nothing outlives the analysis.
    private val learned = IntArrayList()

    // Frontier variables still at the conflict level; 1UIP terminates when resolving a pivot drops
    // this to zero. Incremented as [resolve] admits new current-level variables, decremented by
    // [resolveOut].
    private var currentLevelCount = 0

    override val liveAtCurrentLevel: Int get() = currentLevelCount

    override fun isFrontier(v: Int): Boolean = seen[v]

    override fun reset(universe: Int) {
        this.universe = universe
        seen = scratch(seen, universe)
        resolved = scratch(resolved, universe)
        offTrailFrontier.clear()
        pending.clear()
        substituted.clear()
        litsInLearned.clear()
        bumpBoolVars.clear()
        bumpIntVars.clear()
        currentLevelCount = 0
        learned.clear()
    }

    /** Return [arr] if already ≥ [n], else a fresh array; either way clear `[0, n)` to false. */
    private fun scratch(arr: BooleanArray, n: Int): BooleanArray {
        val a = if (arr.size >= n) arr else BooleanArray(n)
        a.fill(false, 0, n)
        return a
    }

    override fun resolveOut(pivot: Int) {
        seen[pivot] = false
        resolved[pivot] = true
        currentLevelCount--
    }

    override fun addAsserting(pivot: Int) {
        addLearned(uipLit(pivot))
    }

    /** Append [lit] to [learned] and record it in [litsInLearned] so the leaf-literal dedup stays
     *  O(1). Every literal reaches the clause through here, keeping the index exact. */
    private fun addLearned(lit: Int) {
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

    /**
     * Add each literal in [reason] to either the working `seen` set (if it's at [currentLevel] —
     * resolution will continue through it) or directly to [learned] (lower level — it's part of the
     * final clause). Increments [currentLevelCount] for each new-at-current-level variable so the
     * driver can track resolution progress. An order literal that has no pin trail position is
     * resolved through here instead of joining the frontier ([substituteOffTrail]).
     */
    override fun resolve(reason: IntArray, currentLevel: Int) {
        for (lit in reason) pending.add(lit)
        val numBoolVars = state.problem.numBoolVars
        while (pending.size > 0) {
            val lit = pending.last()
            pending.truncateTo(pending.size - 1)
            val v = Lit.variable(lit)
            if (v >= numBoolVars && substituteOffTrail(v, currentLevel)) continue
            if (v >= universe) {
                // An order literal materialised mid-analysis that [substituteOffTrail] could not resolve
                // through. It has no frontier slot, so keep the literal in the clause as a leaf (deduped
                // via [litsInLearned]): adding a literal only weakens the clause, while dropping it would
                // silently strengthen the nogood past what was derived.
                if (!litsInLearned.contains(lit)) {
                    addLearned(lit)
                    if (graph.levelOf(v) == currentLevel) currentLevelCount++
                }
                continue
            }
            if (seen[v]) continue // already in the frontier
            if (resolved[v]) {
                // Reverse establishment order means a resolved variable's premises all sit below it, so it
                // cannot recur as a genuine premise and this branch is unreachable. Keep the literal
                // rather than rely on that: dropping one silently strengthens the nogood past what was
                // derived, which prunes feasible solutions and over-proves optimality, whereas adding one
                // only weakens the clause. Re-resolving would risk the ping-pong [resolved] prevents. A
                // second conflict-level literal leaves the clause non-asserting, which [finalizeResult]
                // flags so the engine backtracks chronologically.
                if (!litsInLearned.contains(lit)) addLearned(lit)
                continue
            }
            val lvl = graph.levelOf(v)
            if (lvl <= 0) continue
            seen[v] = true
            // Record for the VSIDS bump set (every conflict-side var).
            if (v < numBoolVars) {
                bumpBoolVars.add(v)
            } else {
                bumpIntVars.add(state.atoms.intVar[v - numBoolVars])
                offTrailFrontier.add(v) // frontier atom — candidate for the 1UIP atom-pivot scan
            }
            if (lvl == currentLevel) {
                currentLevelCount++
            } else {
                if (v < numBoolVars) {
                    val pinned = state.boolValues[v] ?: error("seen var $v not pinned")
                    addLearned(Lit.make(v, !pinned))
                } else {
                    val atomId = v - numBoolVars
                    val holds = state.atomCurrentTruth(atomId)
                        ?: error("ingest atom $atomId at lower level undetermined")
                    addLearned(Lit.make(v, !holds))
                }
            }
        }
    }

    /**
     * Resolve order literal [v] out of the reason where it is cited, pushing its own premises onto
     * [pending], and report whether it was consumed.
     *
     * 1UIP resolves the frontier in reverse establishment order, which the driver reads off the pin
     * trail. An order literal materialised after its bound had already crossed never went on the trail,
     * so the driver can only reach it once the whole trail is exhausted — long past the facts its reason
     * cites, which then recur as already-resolved premises and cost the clause its literals. Resolving it
     * where it is cited instead puts its premises in the frontier below the citing literal's position,
     * since they all precede the bound move that established it.
     *
     * Only a literal whose threshold is exactly its variable's live endpoint qualifies: that move *is* its
     * establishment, so both [ReasonGraph.levelOf] and the reason are the real ones. For any looser
     * threshold [PropagationState.atomAntecedentsDerived] yields no reason and the literal stays a leaf.
     * Below the conflict level nothing is ever resolved out, so those literals stay leaves too.
     */
    private fun substituteOffTrail(v: Int, currentLevel: Int): Boolean {
        val atomId = v - state.problem.numBoolVars
        if (atomId >= state.atoms.intVar.size || state.atoms.lvl[atomId] >= 0) return false
        if (substituted.contains(v)) return true
        if (graph.levelOf(v) != currentLevel) return false
        val premises = state.atomAntecedentsDerived(atomId) ?: return false
        substituted.add(v)
        bumpIntVars.add(state.atoms.intVar[atomId])
        for (lit in premises) pending.add(lit)
        return true
    }

    /** Convert every still-seen variable into a literal in [learned], deduped via [litsInLearned]. */
    override fun drainFrontier() {
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
            if (!litsInLearned.contains(lit)) addLearned(lit)
        }
    }

    /**
     * Apply self-subsuming-resolution minimization, then compute backjump level + LBD on the final
     * clause and wrap into [ConflictAnalyzer.AnalysisResult.Learned]. Single tail call from every exit
     * path of the driver so all exit shapes get the same post-processing.
     */
    override fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult {
        val minimized = minimizer.reduce(learned, currentLevel)
        val levels = distinctLevelsOf(minimized)
        // A proper 1UIP clause carries exactly one literal at the conflict level; that lone
        // literal becomes the unit-asserting literal after the backjump. A conflict that genuinely
        // rests on several literals at the conflict level (rare while order literals are
        // trail-resident) leaves more than one — such a clause is not unit after any
        // backjump, so the engine must not try to assert it.
        var atConflictLevel = 0
        for (i in 0 until minimized.size) {
            if (graph.levelOf(Lit.variable(minimized[i])) == currentLevel) atConflictLevel++
        }
        return ConflictAnalyzer.AnalysisResult.Learned(
            minimized.toIntArray(),
            backjumpLevelOf(minimized, currentLevel),
            levels.size,
            levels,
            asserting = atConflictLevel == 1,
        )
    }

    /** Sorted-ascending array of distinct decision levels touched by [learned]. Shares its scan with
     *  `lbdOf` (whose count is just `levels.size`); finalize computes both in one pass via this helper. */
    private fun distinctLevelsOf(learned: IntArrayList): IntArray {
        if (learned.size == 0) return EmptyIntArray
        val seen = IntHashSet(learned.size)
        for (i in 0 until learned.size) seen.add(graph.levelOf(Lit.variable(learned[i])))
        val out = seen.toIntArray()
        out.sort()
        return out
    }

    /**
     * Backjump target: the second-highest decision level among the learned literals' variables. The
     * asserting literal (UIP) sits at [currentLevel]; we want to pop back to the level just past the
     * next-highest, so the learned clause becomes unit (only the UIP literal remains undetermined) and
     * propagation can re-fire it as a forced pin.
     */
    private fun backjumpLevelOf(learned: IntArrayList, currentLevel: Int): Int {
        var best = 0
        for (i in 0 until learned.size) {
            val lvl = graph.levelOf(Lit.variable(learned[i]))
            if (lvl < currentLevel && lvl > best) best = lvl
        }
        return best
    }
}
