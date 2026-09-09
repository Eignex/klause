package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.hfactor.BundledHfactor
import java.util.IdentityHashMap
import com.eignex.koblas.sparse.basis.IndexedVector as HfactorVector

// Comparison-only bridge. Stable carrier identity lets HFactor reuse its prepared FTRAN/BTRAN state.
internal class HfactorBasisSolver(matrix: SparseMatrix) : BasisSolver {
    private val delegate = BundledHfactor().basisSolver(matrix)
    private val carriers = IdentityHashMap<IndexedVector, HfactorVector>()
    override val n: Int get() = delegate.n
    override val nnz: Int get() = delegate.nnz
    override val updateCount: Int get() = delegate.updateCount
    override val singular: Boolean get() = delegate.singular
    override val rcond: Double get() = delegate.rcond

    override fun refactorize(basicIndex: IntArray): Boolean = delegate.refactorize(basicIndex)

    override fun ftran(x: IndexedVector, expectedDensity: Double) {
        val carrier = copyIn(x)
        delegate.ftran(carrier, expectedDensity)
        copyOut(carrier, x)
    }

    override fun btran(x: IndexedVector, expectedDensity: Double) {
        val carrier = copyIn(x)
        delegate.btran(carrier, expectedDensity)
        copyOut(carrier, x)
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate =
        BasisUpdate.valueOf(delegate.update(pivotRow, entering, copyIn(spike), pivotEta?.let(::copyIn)).name)

    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality {
        val quality = delegate.solveQuality(rhs, copyIn(solution), transpose)
        return BasisSolveQuality(quality.residualInfinityNorm, quality.relativeResidual)
    }

    override fun close() {
        delegate.close()
        carriers.clear()
    }

    private fun copyIn(vector: IndexedVector): HfactorVector {
        val carrier = carriers.getOrPut(vector) { HfactorVector(vector.size) }
        carrier.clear()
        vector.forEachStored { i, value -> carrier.store(i, value) }
        return carrier
    }

    private fun copyOut(carrier: HfactorVector, vector: IndexedVector) {
        vector.clear()
        carrier.forEachStored { i, value -> vector.store(i, value) }
    }
}
