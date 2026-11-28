package com.eignex.klause.propagation

import com.eignex.util.collections.IntArrayList

// The "reason → resolvent" step of 1UIP analysis, separate from the [ConflictAnalyzer] graph walk so one CDCL
// core serves several learned-constraint algebras: [ClauseResolvent] by set union, [PbConflictResolvent] by
// cutting planes.
internal interface ConflictResolvent {
    fun reset(universe: Int)

    // Called once for the seed reason and once per resolved pivot's antecedents.
    fun resolve(reason: IntArray, currentLevel: Int)

    // Resolving the last current-level pivot drops this to zero; its literal is the asserting one.
    val liveAtCurrentLevel: Int

    fun isFrontier(v: Int): Boolean

    // Frontier atoms with no pin-trail position, swept once the trail is exhausted. A superset of the frontier
    // atoms, so the scan re-checks [isFrontier].
    val offTrailFrontier: IntArrayList

    fun resolveOut(pivot: Int)

    fun addAsserting(pivot: Int)

    // For the leaf-pivot and trail-exhausted exit paths.
    fun drainFrontier()

    fun finalizeResult(currentLevel: Int): ConflictAnalyzer.AnalysisResult

    // Bump sets are valid after a completed analysis.
    val bumpBoolVars: IntArrayList

    val bumpIntVars: IntArrayList
}
