package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.smallModelIntBound
import com.eignex.klause.lp.tightenOpenIntBounds

/**
 * Close every still-[PresolveDomain.Open] integer variable to a finite domain before search. First
 * **OBBT** (the shared [tightenOpenIntBounds]): the LP relaxation's min/max of a variable is a sound
 * bound (the relaxation contains every integer solution), so a finite LP optimum snaps that side shut
 * over the genuinely unbounded region. Any side OBBT leaves open falls back to a searchable range and
 * marks the model clamped, so search still finds a SAT witness while an `unsat` over the box is reported
 * as `unknown` (never a false `unsat`). Infinity thus never reaches search.
 */
internal fun SmtLib.Builder.boundUnboundedVars() {
    obbtBounds()
    finalizeDomains()
}

/** LP-tighten every open domain side via the shared OBBT helper ([tightenOpenIntBounds]) over the
 *  current [Linear] constraints; only open sides are written back (a finite domain is left as inferred). */
private fun SmtLib.Builder.obbtBounds() {
    if ((0 until nextInt).none { intDomains[it] is PresolveDomain.Open }) return
    val bounds = Array(nextInt) { v ->
        when (val d = intDomains[v]) {
            is PresolveDomain.Finite -> OpenIntBounds(d.domain.min, d.domain.max)
            is PresolveDomain.Open -> OpenIntBounds(d.lo, d.hi)
        }
    }
    val tightened = tightenOpenIntBounds(bounds, factors.filterIsInstance<Linear>().filter { !it.hasReals })
    for (v in 0 until nextInt) {
        if (intDomains[v] is PresolveDomain.Open) {
            intDomains[v] = openOrFinite(tightened[v].lo, tightened[v].hi)
        }
    }
}

/** Close every remaining [PresolveDomain.Open] to a finite box: the small-model bound when it fits
 *  (equisatisfiable, so no flag), else the searchable fallback with the model flagged clamped. */
private fun SmtLib.Builder.finalizeDomains() {
    if ((0 until nextInt).none { intDomains[it] is PresolveDomain.Open }) return
    // The small-model magnitude bound ([smallModelIntBound]) makes the finite box equisatisfiable
    // with the unbounded model, so an `unsat` inside it stays `unsat`. When it doesn't fit — or the
    // caller's own [unboundedIntLo]/[unboundedIntHi] narrow it — the box is lossy and flags the
    // model: an `unsat` over it becomes `unknown`. Feasibility-only: under an objective the box
    // could truncate an unbounded optimum into a spurious finite one, so it is never applied there.
    val small = if (objectiveSpec == null) smallModelIntBound(nextInt, factors) else null
    val boxLo = if (small != null) -small else -searchBound
    val boxHi = small ?: searchBound
    val fallbackLo = maxOf(unboundedIntLo, boxLo)
    val fallbackHi = minOf(unboundedIntHi, boxHi)
    val lossy = small == null || fallbackLo > boxLo || fallbackHi < boxHi
    for (v in 0 until nextInt) {
        val d = intDomains[v] as? PresolveDomain.Open ?: continue
        val newLo = d.lo ?: fallbackLo.also { if (lossy) domainsClamped = true }
        val newHi = d.hi ?: fallbackHi.also { if (lossy) domainsClamped = true }
        intDomains[v] = openOrFinite(newLo, newHi)
    }
}
