package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Bounds a model's own equality structure implies, for the sides a relaxation leaves open.
 *
 * The equality rows drive a unimodular change of variables `x = V·y` ([mixedEchelonHermite]) whose
 * equality block comes out lower triangular. A triangular block bounds its pivot variables by forward
 * substitution ([triangularBounds]), and `V` being unimodular makes the correspondence exact over the
 * integer lattice, so those ranges push back onto the original columns ([originalBounds]).
 *
 * The distinction that makes this worth running: a bound derived this way is the **model's**, not a box
 * chosen for it. Closing a side with it leaves the model equisatisfiable, so a later `unsat` over the
 * closed model refutes the original — where a side closed by an invented box only ever supports
 * `unknown`.
 *
 * Only unconditional integer equalities participate. Null where the structure implies nothing: no
 * equalities, a reduction [cancellation] cut short, or a row outside the exact-integer fragment.
 */
internal fun structuralIntBounds(
    numVars: Int,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): TriangularBounds? {
    if (numVars == 0) return null
    val eqRows = constraints.filter { it.op == LinearOp.EQ && it.integralConstants != null }
    if (eqRows.isEmpty()) return null
    // Built sparse: a real model's equality block carries a handful of terms per row against tens of
    // thousands of columns, and the dense form of it does not fit in memory at all.
    val eq = eqRows.map { f ->
        val constants = f.integralConstants ?: return null
        val entries = HashMap<Int, BigInteger>(f.vars.size)
        for (k in f.vars.indices) {
            val v = f.vars[k]
            if (v < numVars) entries[v] = (entries[v] ?: BigInteger.ZERO) + constants.exactCoeff(k)
        }
        sparseIntRow(entries)
    }
    val rhsIn = Array(eqRows.size) { eqRows[it].integralConstants?.exactBound ?: return null }
    val mixed = mixedEchelonHermite(eq, emptyList(), numVars, rhsIn, cancellation)
    if (mixed.equalities.isEmpty() || mixed.equalityRhs.size != mixed.equalities.size) return null
    // The reduced rows carry the reduced right-hand sides: pairing them with the *input* rows' bounds
    // would attach a bound to whichever row a swap happened to move into that slot.
    val rhs = Array<BigInteger?>(mixed.equalities.size) { mixed.equalityRhs[it] }
    val y = triangularBounds(mixed.equalities, numVars, rhs, rhs)
    return mixed.originalBounds(y.lo, y.hi)
}

/** [this] as a `Long`, or null where it does not fit — the column then stays open rather than taking a
 *  wrapped bound. */
internal fun BigInteger.longOrNull(): Long? =
    if (this in LONG_MIN_EXACT..LONG_MAX_EXACT) longValue(exactRequired = false) else null

private val LONG_MIN_EXACT = BigInteger.fromLong(Long.MIN_VALUE)
private val LONG_MAX_EXACT = BigInteger.fromLong(Long.MAX_VALUE)
