package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.tightenOpenIntBounds

/**
 * Close every still-[PresolveDomain.Open] integer variable to a finite domain before search. First
 * **OBBT** (the shared [tightenOpenIntBounds]): the LP relaxation's min/max of a variable is a sound
 * bound (the relaxation contains every integer solution), so a finite LP optimum snaps that side shut
 * over the genuinely unbounded region. Any side OBBT leaves open falls back to a searchable range and
 * marks the model clamped, so search still finds a SAT witness while an `unsat` over the box is reported
 * as `unknown` (never a false `unsat`). Infinity thus never reaches search.
 */
internal fun SmtLibQfLia.Builder.boundUnboundedVars() {
    obbtBounds()
    finalizeDomains()
}

/** LP-tighten every open domain side via the shared OBBT helper ([tightenOpenIntBounds]) over the
 *  current [Linear] constraints; only open sides are written back (a finite domain is left as inferred). */
private fun SmtLibQfLia.Builder.obbtBounds() {
    if ((0 until nextInt).none { intDomains[it] is PresolveDomain.Open }) return
    val bounds = Array(nextInt) { v ->
        when (val d = intDomains[v]) {
            is PresolveDomain.Finite -> OpenIntBounds(d.domain.min, d.domain.max)
            is PresolveDomain.Open -> OpenIntBounds(d.lo, d.hi)
        }
    }
    val tightened = tightenOpenIntBounds(bounds, factors.filterIsInstance<Linear>())
    for (v in 0 until nextInt) {
        if (intDomains[v] is PresolveDomain.Open) {
            intDomains[v] = openOrFinite(tightened[v].lo, tightened[v].hi)
        }
    }
}

/** Close every remaining [PresolveDomain.Open] to the searchable fallback and flag the model clamped. */
private fun SmtLibQfLia.Builder.finalizeDomains() {
    // A side still open falls back to a searchable range: the caller's own finite [unboundedIntLo] /
    // [unboundedIntHi] when set, else ±[searchBound]. Clamping is lossy, so it flags the model — an
    // `unsat` over the box becomes `unknown`.
    val fallbackLo = maxOf(unboundedIntLo, -searchBound)
    val fallbackHi = minOf(unboundedIntHi, searchBound)
    for (v in 0 until nextInt) {
        val d = intDomains[v] as? PresolveDomain.Open ?: continue
        val newLo = d.lo ?: fallbackLo.also { domainsClamped = true }
        val newHi = d.hi ?: fallbackHi.also { domainsClamped = true }
        intDomains[v] = openOrFinite(newLo, newHi)
    }
}
