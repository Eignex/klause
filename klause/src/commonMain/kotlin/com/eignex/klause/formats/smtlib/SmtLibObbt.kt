package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.DeferredIntBounds
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound

/**
 * Capture the deferred OBBT inputs and close every still-[PresolveDomain.Open] side to the cheap fallback
 * box so the initial [com.eignex.klause.solver.Problem] is finite. The LP tightening itself is deferred
 * (see [DeferredIntBounds]); this does no LP, so parsing only reads. Returns `null` when every domain is
 * already finite — there is no open side to close, so OBBT has nothing to do and no clamp can arise.
 *
 * [inventedLo] and [inventedHi] are written per variable: true where that side came from the box rather
 * than from the model. They are what tell the digit lowering which bound it may widen.
 */
internal fun SmtLib.Builder.prepareDeferredBounds(
    inventedLo: BooleanArray,
    inventedHi: BooleanArray,
): DeferredIntBounds? {
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
        if (d is PresolveDomain.Open) {
            if (d.lo == null) inventedLo[v] = true
            if (d.hi == null) inventedHi[v] = true
            intDomains[v] = openOrFinite(d.lo ?: fallbackLo, d.hi ?: fallbackHi)
        }
    }
    return DeferredIntBounds(
        openBounds,
        linears.filter { it.isIntegerCore },
        linears.filter { it.hasReals },
        nextReal,
        fallbackLo,
        fallbackHi,
        lossy,
    )
}
