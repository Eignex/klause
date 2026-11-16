package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector

internal interface SimplexPhasePricing {
    fun objectiveDuals(): DoubleArray
    fun dotColumn(values: DoubleArray, column: Int): Double
}

internal interface PrimalBasisOperations : SimplexPhasePricing {
    fun basicValues(out: DoubleArray): DoubleArray
    fun transposeGradient(gradient: DoubleArray, out: DoubleArray)
    fun spike(column: Int): IndexedVector
    fun refactorAtSafePoint(): PrimalPhaseRefactor?
    fun foldPivot(row: Int, entering: Int, leaving: Int): PivotFold
}

internal enum class PrimalPhaseRefactor {
    UNCHANGED,
    RESTART,
    BASIS_CHANGED,
    FAILED,
}

internal enum class PrimalPhaseResult {
    COMPLETE,
    RESTART,
    BASIS_CHANGED,
    FAILED,
    NUMERICAL_FAILURE,
    INFEASIBLE,
    UNBOUNDED,
}

internal enum class PivotFold {
    /** The update went in and the factors are fit to carry on. */
    UPDATED,

    /** The refactor policy rebuilt the same basis; the factors are fresh. */
    REBUILT,

    /** Numerical repair installed a different ordered basis; iteration-local state must be abandoned. */
    BASIS_CHANGED,

    /** Neither the update nor the rebuild behind it left a usable basis. */
    FAILED,
}
