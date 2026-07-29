package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain

/**
 * Deferred integer-domain bounding for an SMT-LIB model. Parsing only closes each still-open side to the
 * cheap fallback box (no LP), so a valid finite [com.eignex.klause.solver.Problem] can be built; the
 * **OBBT** LP tightening ([tightenOpenIntBounds]) is deferred to the presolve phase via [DeferredIntBounds],
 * where it runs under the presolve budget instead of unbounded at load. [DeferredIntBounds.run] reproduces
 * the load-time result exactly: the LP relaxation's min/max of a variable is a sound bound (it contains
 * every integer solution), so a finite optimum snaps the open side shut over the genuinely unbounded
 * region; any side the LP leaves open falls back to the searchable range and, when that box is lossy,
 * flags the model clamped so an `unsat` over it is reported `unknown` (never a false `unsat`).
 */
class DeferredIntBounds internal constructor(
    /** OBBT input: per int var, the declared bounds with a genuinely open side left `null`. */
    private val openBounds: Array<OpenIntBounds>,
    /** Pure-integer linear rows (the relaxation OBBT tightens over). */
    private val intConstraints: List<Linear>,
    /** Real-bearing linear rows (context for the relaxation; only integer sides are written back). */
    private val realConstraints: List<Linear>,
    private val numReal: Int,
    private val fallbackLo: Long,
    private val fallbackHi: Long,
    /** True when the fallback box is not equisatisfiable with the unbounded model (so a side closed by it
     *  makes the model clamped); false when the small-model bound proves the box loses no solutions. */
    private val lossy: Boolean,
) {
    /** Run the deferred OBBT under [cancellation] (the presolve budget) and produce the final finite
     *  domains plus whether any side fell back to a lossy clamp. */
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

/**
 * Capture the deferred OBBT inputs and close every still-[PresolveDomain.Open] side to the cheap fallback
 * box so the initial [com.eignex.klause.solver.Problem] is finite. The LP tightening itself is deferred
 * (see [DeferredIntBounds]); this does no LP, so parsing only reads. Returns `null` when every domain is
 * already finite — there is no open side to close, so OBBT has nothing to do and no clamp can arise.
 */
internal fun SmtLib.Builder.prepareDeferredBounds(): DeferredIntBounds? {
    if ((0 until nextInt).none { intDomains[it] is PresolveDomain.Open }) return null
    val linears = factors.filterIsInstance<Linear>()
    val openBounds = Array(nextInt) { v ->
        when (val d = intDomains[v]) {
            is PresolveDomain.Finite -> OpenIntBounds(d.domain.min, d.domain.max)
            is PresolveDomain.Open -> OpenIntBounds(d.lo, d.hi)
        }
    }
    // The small-model magnitude bound makes the finite box equisatisfiable with the unbounded model, so an
    // `unsat` inside it stays `unsat`. When it doesn't fit — or the caller's own [unboundedIntLo]/
    // [unboundedIntHi] narrow it — the box is lossy. Feasibility-only: under an objective the box could
    // truncate an unbounded optimum, so it is never applied there.
    val small = if (objectiveSpec == null) smallModelIntBound(nextInt, factors) else null
    val boxLo = if (small != null) -small else -searchBound
    val boxHi = small ?: searchBound
    val fallbackLo = maxOf(unboundedIntLo, boxLo)
    val fallbackHi = minOf(unboundedIntHi, boxHi)
    val lossy = small == null || fallbackLo > boxLo || fallbackHi < boxHi
    // Close open sides to the fallback for the initial problem; the deferred run re-derives from
    // [openBounds] and only then decides the final (possibly tighter) domains and the clamp flag.
    for (v in 0 until nextInt) {
        val d = intDomains[v]
        if (d is PresolveDomain.Open) intDomains[v] = openOrFinite(d.lo ?: fallbackLo, d.hi ?: fallbackHi)
    }
    return DeferredIntBounds(
        openBounds,
        linears.filter { !it.hasReals },
        linears.filter { it.hasReals },
        nextReal,
        fallbackLo,
        fallbackHi,
        lossy,
    )
}
