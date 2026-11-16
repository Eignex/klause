package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector

internal interface DualBasisOperations : SimplexPhasePricing {
    val updateCount: Int
    fun basicValues(out: DoubleArray): DoubleArray
    fun pivotalRow(row: Int): IndexedVector
    fun spike(column: Int): IndexedVector
    fun refactorAtSafePoint(): SimplexBasisRefactor?
    fun recoverNumerically(): SimplexBasisRefactor
    fun foldPivot(row: Int, entering: Int, leaving: Int): PivotFold
    fun columnNnz(column: Int): Int
    fun sampleBoundSupport(column: Int, leavingRow: Int, enforced: BooleanArray?): TheoryProbe
}

internal sealed interface TheoryProbe {
    data class Success(val nonFreeBasics: Int) : TheoryProbe

    data object ArithmeticDeclined : TheoryProbe

    data object ResourceStopped : TheoryProbe
}

internal enum class SimplexBasisRefactor {
    UNCHANGED,
    BASIS_CHANGED,
    FAILED,
}
