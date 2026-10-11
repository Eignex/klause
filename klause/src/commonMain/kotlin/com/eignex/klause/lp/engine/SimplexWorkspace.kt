package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.util.IntArrayList

internal class SimplexWorkspace(m: Int, numVars: Int) {
    val dualRhs = DoubleArray(m)
    val dualValues = DoubleArray(m)
    val qualityRhs = DoubleArray(m)
    val basicRhs = DoubleArray(m)
    val boundChange = DoubleArray(m)
    val dualBeta = DoubleArray(m)
    val primalBeta = DoubleArray(m)
    val phaseOneBeta = DoubleArray(m)
    val phaseOneGradient = DoubleArray(m)
    val phaseOneDuals = DoubleArray(m)
    val alphaValues = DoubleArray(m)
    val pivotRowEntries = DoubleArray(numVars)
    val enteringRatios = DoubleArray(numVars)
    val eligibleColumns = IntArrayList(numVars)
    val eligibleOrdered = IntArray(numVars)
    val theoryColumns = IntArrayList(numVars)
    val touchedColumns = IntArray(numVars)
    val columnEpochs = IntArray(numVars)
    val reconcileQueue = IntArrayList(m)
    // A backend can reuse the form it produced when the same carrier reaches an update.
    // Separate roles retain that identity instead of overwriting one shared vector.
    val spikeVec = IndexedVector(m)
    val pricingSpikeVec = IndexedVector(m)
    val pivotEtaVec = IndexedVector(m)
    val rhsVec = IndexedVector(m)
    val dualVec = IndexedVector(m)

    fun beginDualPass() {
        columnEpochs.fill(0)
    }
}
