package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Deferred integer-domain bounding, shared by the front-ends (SMT-LIB, MPS) whose models declare
 * unbounded integers. Parsing only closes each open side to the cheap fallback box (no LP), so a valid
 * finite [com.eignex.klause.solver.Problem] can be built; the **OBBT** LP tightening ([tightenOpenIntBounds])
 * is deferred to the presolve phase, where it runs under the solve deadline instead of unbounded at load.
 * [run] reproduces the load-time result exactly: the LP relaxation's min/max of a variable is a sound bound
 * (it contains every integer solution), so a finite optimum snaps the open side shut over the genuinely
 * unbounded region; any side the LP leaves open falls back to the searchable range and, when that box is
 * lossy, flags the model clamped so an `unsat` over it is reported `unknown` (never a false `unsat`).
 */
class DeferredIntBounds internal constructor(
    /** OBBT input: per int var, the declared bounds with a genuinely open side left `null`. */
    private val openBounds: Array<OpenIntBounds>,
    /** Pure-integer linear rows (the relaxation OBBT tightens over). */
    private val intConstraints: List<Linear>,
    /** Real-bearing linear rows (context for the relaxation; only integer sides are written back). Empty
     *  for a front-end that gives OBBT no real context. */
    private val realConstraints: List<Linear>,
    /** Number of real columns the [realConstraints] range over (zero when there is no real context). */
    private val numReal: Int,
    private val fallbackLo: Long,
    private val fallbackHi: Long,
    /** True when the fallback box is not equisatisfiable with the unbounded model (so a side closed by it
     *  makes the model clamped); false when the small-model bound proves the box loses no solutions. */
    private val lossy: Boolean,
) {
    /** Run the deferred OBBT under [cancellation] (the solve deadline) and produce the final finite domains
     *  plus whether any side fell back to a lossy clamp. */
    fun run(cancellation: Cancellation): BoundedIntDomains {
        // Refute over the genuinely open ranges first. A certificate there rules out the unbounded model
        // itself, so the verdict needs no clamp caveat — where an `unsat` found inside the fallback box
        // would only ever be reportable as `unknown`.
        if (lossy && unboundedlyInfeasible(openBounds, intConstraints, cancellation)) {
            val domains = Array(openBounds.size) { IntDomain(0L, 0L) }
            return BoundedIntDomains(
                domains,
                clamped = false,
                openLo = BooleanArray(openBounds.size),
                openHi = BooleanArray(openBounds.size),
                openlyInfeasible = true,
            )
        }
        val tightened = tightenOpenIntBounds(
            openBounds,
            intConstraints,
            cancellation = cancellation,
            realConstraints = realConstraints,
            realLower = DoubleArray(numReal) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(numReal) { Double.POSITIVE_INFINITY },
        )
        // Where OBBT leaves a side open, the model's own structure may still bound it: the equality rows
        // drive a unimodular change of variables whose triangular block bounds its pivots by forward
        // substitution, and those ranges push back onto the original variables. A bound obtained that way
        // is the model's, not an invented box, so it closes the side WITHOUT making the model clamped.
        val structural = structuralBounds(openBounds.size, intConstraints)
        var clamped = false
        val openLo = BooleanArray(openBounds.size)
        val openHi = BooleanArray(openBounds.size)
        val domains = Array(openBounds.size) { v ->
            val lo = tightened[v].lo ?: structural?.lo?.getOrNull(v)?.longOrNull() ?: fallbackLo.also {
                openLo[v] = true
                if (lossy) clamped = true
            }
            val hi = tightened[v].hi ?: structural?.hi?.getOrNull(v)?.longOrNull() ?: fallbackHi.also {
                openHi[v] = true
                if (lossy) clamped = true
            }
            if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
        }
        return BoundedIntDomains(domains, clamped, openLo, openHi)
    }
}

/** Result of [DeferredIntBounds.run]. */
class BoundedIntDomains internal constructor(
    /** The closed finite integer domains, one per integer variable. */
    val domains: Array<IntDomain>,
    /** True when a side fell back to a lossy clamp, so an `unsat` over the box is only `unknown`. */
    val clamped: Boolean,
    /** Sides the box invented rather than derived: `true` where OBBT left the low side genuinely open and
     *  the domain's `min` is the fallback. Carried into `Problem.openIntLo` so the LP relaxation can build
     *  the column over its true range instead of the box. */
    val openLo: BooleanArray,
    /** Sides where the high bound is the fallback; see [openLo]. */
    val openHi: BooleanArray,
    /**
     * The model was refuted over its genuinely open ranges, so it has no solution at all — not merely
     * none inside the search box. [domains] are a placeholder the caller never searches; the verdict is
     * `unsat` outright, with none of the clamp caveat an in-box refutation would carry.
     */
    val openlyInfeasible: Boolean = false,
)

/**
 * Bounds implied by the model's own equality structure, or null when there are none to derive.
 *
 * Only equality rows participate: they are what pin variables down exactly, and the transformation uses
 * them to choose a basis whose triangular block bounds its pivot variables. Inequalities ride along
 * through the same change of variables. A side comes back null wherever the structure implies nothing,
 * which is the honest answer — inventing one there is precisely what the clamp does.
 */
private fun structuralBounds(numVars: Int, constraints: List<Linear>): TriangularBounds? {
    if (numVars == 0) return null
    val eqRows = constraints.filter { it.op == LinearOp.EQ && it.isIntegerCore }
    if (eqRows.isEmpty()) return null
    val eq = Array(eqRows.size) { r ->
        val row = Array(numVars) { BigInteger.ZERO }
        val f = eqRows[r]
        for (k in f.vars.indices) {
            val v = f.vars[k]
            if (v < numVars) row[v] = row[v] + BigInteger.fromLong(f.coeff(k))
        }
        row
    }
    val mixed = mixedEchelonHermite(eq, emptyArray(), numVars)
    if (mixed.equalities.isEmpty()) return null
    val rhs = Array<BigInteger?>(mixed.equalities.size) { BigInteger.fromLong(eqRows[it].bound) }
    val y = triangularBounds(mixed.equalities, rhs, rhs)
    return mixed.originalBounds(y.lo, y.hi)
}

/** This value as a [Long], or null when it does not fit — a bound past `Long` cannot close a side here. */
private fun BigInteger.longOrNull(): Long? =
    if (this in BigInteger.fromLong(Long.MIN_VALUE)..BigInteger.fromLong(Long.MAX_VALUE)) longValue() else null
