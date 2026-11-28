package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.lattice.TriangularBounds
import com.eignex.klause.lp.lattice.mixedEchelonHermite
import com.eignex.klause.lp.lattice.originalBounds
import com.eignex.klause.lp.lattice.sparseIntRow
import com.eignex.klause.lp.lattice.triangularBounds
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.toLong

// The equality rows drive a unimodular change of variables `x = V·y` whose equality block is lower
// triangular; forward substitution bounds the pivots, and unimodularity maps those ranges exactly back onto
// the original columns. A bound derived this way is the model's own, so closing a side with it keeps the
// model equisatisfiable and a later `unsat` refutes the original.
internal fun structuralIntBounds(
    numVars: Int,
    constraints: List<Linear>,
    cancellation: ExecutionControl = ExecutionControl.Never,
): TriangularBounds? {
    if (numVars == 0) return null
    val eqRows = constraints.filter { it.op == LinearOp.EQ && it.integralConstants != null }
    if (eqRows.isEmpty()) return null
    // Built sparse: the dense equality block of a large model does not fit in memory.
    val eq = eqRows.map { f ->
        val constants = f.integralConstants ?: return null
        val entries = HashMap<Int, BigInt>(f.vars.size)
        for (k in f.vars.indices) {
            val v = f.vars[k]
            if (v < numVars) entries[v] = (entries[v] ?: BIG_ZERO) + constants.exactCoeff(k)
        }
        sparseIntRow(entries)
    }
    val rhsIn = Array(eqRows.size) { eqRows[it].integralConstants?.exactBound ?: return null }
    val mixed = mixedEchelonHermite(eq, emptyList(), numVars, rhsIn, cancellation)
    if (mixed.equalities.isEmpty() || mixed.equalityRhs.size != mixed.equalities.size) return null
    // The reduced rows carry the reduced right-hand sides: pairing them with the *input* rows' bounds
    // would attach a bound to whichever row a swap happened to move into that slot.
    val rhs = Array<BigInt?>(mixed.equalities.size) { mixed.equalityRhs[it] }
    val y = triangularBounds(mixed.equalities, numVars, rhs, rhs)
    return mixed.originalBounds(y.lo, y.hi)
}

internal fun BigInt.longOrNull(): Long? = if (fitsLong()) toLong() else null
