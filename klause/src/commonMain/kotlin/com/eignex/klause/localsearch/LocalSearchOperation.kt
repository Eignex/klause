package com.eignex.klause.localsearch

internal interface SteppedIntInvariant : Invariant {
    fun applyIntSetSteps(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Sequence<Unit>
}

internal object LocalSearchScoringInterrupted : RuntimeException()

internal const val LOCAL_SEARCH_OPERATION_BATCH = 256
