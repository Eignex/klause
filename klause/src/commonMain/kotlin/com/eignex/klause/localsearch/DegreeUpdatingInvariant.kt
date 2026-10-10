package com.eignex.klause.localsearch

// The assignment is already committed. Fuse payload updates with the exact degree to avoid discarded deltas.
internal interface DegreeUpdatingInvariant : Invariant {
    fun applyBoolFlipDegree(state: LocalSearchState, factorId: Int, boolVar: Int): Int

    fun applyIntSetDegree(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int
}
