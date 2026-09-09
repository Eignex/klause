package com.eignex.klause.simplex.basis

internal class BasisArithmeticException(message: String) : ArithmeticException(message)

internal enum class BasisUpdate {
    APPLIED,
    REFACTORIZE,
    SINGULAR,
}

internal enum class RefactorizeReason {
    FACTOR_ASKED,
    UPDATES_WORN,
}

internal data class BasisKernel(val dimension: Int, val entries: Int)
internal data class BasisSolveQuality(val residualInfinityNorm: Double, val relativeResidual: Double)
internal class BasisRepair(val columns: IntArray, val unitRows: IntArray) {
    val repaired: Boolean get() = unitRows.any { it >= 0 }
}

internal interface BasisSnapshot : AutoCloseable

// One mutable owner per fixed source matrix. Headings name source columns in original basis-slot order.
// Accepted updates adopt the new basis even when advising a rebuild; SINGULAR preserves the old factors.
internal interface BasisSolver : AutoCloseable {
    val n: Int
    val nnz: Int
    val updateCount: Int
    val singular: Boolean
    val rcond: Double
    val refactorizeReason: RefactorizeReason? get() = null
    val kernel: BasisKernel? get() = null

    fun refactorize(basicIndex: IntArray): Boolean
    fun ftran(x: IndexedVector, expectedDensity: Double = 1.0)
    fun btran(x: IndexedVector, expectedDensity: Double = 1.0)
    fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector? = null): BasisUpdate
    fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean = false): BasisSolveQuality

    fun refactorizeRepairing(basicIndex: IntArray): BasisRepair? =
        if (refactorize(basicIndex)) BasisRepair(basicIndex.copyOf(), IntArray(n) { -1 }) else null

    fun snapshot(): BasisSnapshot? = null
    fun restore(snapshot: BasisSnapshot): Boolean = false
    override fun close() {}
}
