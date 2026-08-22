package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.rootFixedReifiedRows
import com.eignex.klause.lp.DeferredIntBounds
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.util.Bits

/**
 * Capture deferred OBBT inputs without closing an open side. The parser keeps the model's bounds in
 * [IntBounds]; a finite search box is chosen only by the backend that explicitly materializes one.
 */
internal fun SmtLib.Builder.prepareDeferredBounds(): DeferredIntBounds? {
    if ((0 until nextInt).none { intDomains[it] is PresolveDomain.Open }) return null
    // A reified row whose literal a unit clause fixes is an ordinary constraint of the model; without
    // this it reaches neither the bound tightening nor the open-domain refutation, both of which read
    // linear rows only.
    val linears = factors.filterIsInstance<Linear>() + rootFixedReifiedRows(factors)
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
    return DeferredIntBounds(
        openBounds,
        linears.filter { it.isIntegerCore },
        linears.filter { it.hasReals },
        nextReal,
        fallbackLo,
        fallbackHi,
        lossy,
        conjunctive = nextBool == 0 && nextReal == 0 && factors.all { it is Linear && it.isIntegerCore },
    )
}

/**
 * Supply finite working ranges only while rewriting an out-of-range declared value onto digit columns.
 *
 * The ranges are not retained as the source model's bounds: [modelIntBounds] is snapshotted before this
 * runs. They are solely a representability analysis for the digit encoding, whose fresh columns are
 * themselves finite.
 */
internal fun SmtLib.Builder.closeForDigitization(inventedLo: BooleanArray, inventedHi: BooleanArray) {
    val small = smallModelIntBound(nextInt, factors)
    val fallbackLo = maxOf(unboundedIntLo, if (small != null) -small else -searchBound)
    val fallbackHi = minOf(unboundedIntHi, small ?: searchBound)
    for (v in 0 until nextInt) {
        val domain = intDomains[v] as? PresolveDomain.Open ?: continue
        if (domain.lo == null) inventedLo[v] = true
        if (domain.hi == null) inventedHi[v] = true
        intDomains[v] = openOrFinite(domain.lo ?: fallbackLo, domain.hi ?: fallbackHi)
    }
}

/** Snapshot the inferred model bounds without turning an open side into a sentinel or search clamp. */
internal fun SmtLib.Builder.modelIntBounds(): IntBounds {
    val lower = LongArray(nextInt)
    val upper = LongArray(nextInt)
    var openLo: Bits? = null
    var openHi: Bits? = null
    for (v in 0 until nextInt) {
        when (val domain = intDomains[v]) {
            is PresolveDomain.Finite -> {
                lower[v] = domain.domain.min
                upper[v] = domain.domain.max
            }

            is PresolveDomain.Open -> {
                lower[v] = domain.lo ?: 0L
                upper[v] = domain.hi ?: 0L
                if (domain.lo == null) (openLo ?: Bits(nextInt).also { openLo = it }).set(v)
                if (domain.hi == null) (openHi ?: Bits(nextInt).also { openHi = it }).set(v)
            }
        }
    }
    return IntBounds.fromModelBounds(lower, upper, openLo, openHi)
}
