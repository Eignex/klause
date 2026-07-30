package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain

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
        val tightened = tightenOpenIntBounds(
            openBounds,
            intConstraints,
            cancellation = cancellation,
            realConstraints = realConstraints,
            realLower = DoubleArray(numReal) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(numReal) { Double.POSITIVE_INFINITY },
        )
        var clamped = false
        val domains = Array(openBounds.size) { v ->
            val lo = tightened[v].lo ?: fallbackLo.also { if (lossy) clamped = true }
            val hi = tightened[v].hi ?: fallbackHi.also { if (lossy) clamped = true }
            if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
        }
        return BoundedIntDomains(domains, clamped)
    }
}

/** Result of [DeferredIntBounds.run]. */
class BoundedIntDomains internal constructor(
    /** The closed finite integer domains, one per integer variable. */
    val domains: Array<IntDomain>,
    /** True when a side fell back to a lossy clamp, so an `unsat` over the box is only `unknown`. */
    val clamped: Boolean,
)
