package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList

/**
 * The conflict resolvent under construction during 1UIP analysis, abstracted over the learned-constraint
 * kind. [ConflictAnalyzer] owns the implication-graph walk — seeding from the conflict reason and
 * selecting the pivot to resolve at each step in reverse-assignment order; the resolvent owns the
 * "reason → resolvent" step: how an antecedent folds into the accumulating nogood ([resolve]), when the
 * unique implication point is reached ([liveAtCurrentLevel]), and how the finished nogood materialises
 * ([finalizeResult]).
 *
 * Splitting the resolvent from the graph walk is the single generalization that lets one CDCL core serve
 * more than one learned-constraint algebra. The default and only implementation is
 * [ClauseResolvent] — a disjunction of literals resolved by the classical set-union step. A pseudo-Boolean
 * cutting-planes resolvent, whose [resolve] is coefficient cancellation with rounding and saturation and
 * whose [liveAtCurrentLevel] is a slack condition, slots in as a second implementation without touching
 * the driver, the trail, VSIDS, or the learned-constraint database.
 */
internal interface ConflictResolvent {
    /** Begin a fresh analysis over a [universe]-sized variable space (bool vars followed by atom vars);
     *  clears the frontier, nogood, and bump sets. */
    fun reset(universe: Int)

    /**
     * Fold every literal of [reason] into the resolvent. A frontier variable at [currentLevel] becomes a
     * resolution pivot (reflected in [liveAtCurrentLevel]); a variable at a lower level becomes a literal
     * of the final nogood. Called once for the seed reason and once per resolved pivot's antecedents.
     */
    fun resolve(reason: IntArray, currentLevel: Int)

    /** Count of frontier variables still at the conflict level. 1UIP resolves pivots until this reaches
     *  one; resolving that last pivot ([resolveOut]) drops it to zero and its literal is the asserting one. */
    val liveAtCurrentLevel: Int

    /** Whether variable [v] is in the resolution frontier — read by the driver's pivot scan. */
    fun isFrontier(v: Int): Boolean

    /** Frontier atom-vars with no pin-trail position (materialised mid-analysis). The driver sweeps this
     *  fallback frontier for a conflict-level pivot once the pin trail is exhausted. A superset of the
     *  currently-frontier atoms (never pruned), so the scan re-checks [isFrontier]. */
    val offTrailFrontier: IntArrayList

    /** Resolve [pivot] out of the frontier — the driver has chosen it as the next resolution pivot.
     *  Decrements [liveAtCurrentLevel]. */
    fun resolveOut(pivot: Int)

    /** Append [pivot]'s asserting (UIP) literal to the nogood — resolution has reached the UIP. */
    fun addAsserting(pivot: Int)

    /** Move every remaining frontier variable into the nogood as a leaf literal, for the leaf-pivot and
     *  trail-exhausted exit paths. */
    fun drainFrontier()

    /** Minimize and package the accumulated nogood with its backjump level, LBD, and asserting flag. */
    fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult

    /** VSIDS bump set (bool var ids) from the resolution just performed — every conflict-side variable
     *  the graph walk touched. Valid after a completed analysis. */
    val bumpBoolVars: IntArrayList

    /** VSIDS bump set (underlying int var ids, decoded from touched atoms) from the resolution just
     *  performed. Valid after a completed analysis. */
    val bumpIntVars: IntArrayList
}
