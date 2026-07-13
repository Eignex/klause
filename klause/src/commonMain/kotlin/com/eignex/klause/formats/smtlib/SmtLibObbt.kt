package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.SEARCHABLE_UNBOUNDED_CLAMP
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.safeObjectiveLowerBound
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.math.ceil
import kotlin.math.floor

/** Searchable fallback range for a variable OBBT could not bound: the finite search still finds a
 *  witness within it (SAT), and [SmtLibQfLia.Builder.domainsClamped] downgrades any `unsat` to
 *  `unknown` because such an `unsat` only holds within this box. */
private val SEARCH_FALLBACK: Long = SEARCHABLE_UNBOUNDED_CLAMP

/**
 * Turn the effectively-infinite `[Long.MIN, Long.MAX]` domain of every unbounded integer variable into
 * a finite one before search. First **OBBT** (optimization-based bound tightening): the LP relaxation's
 * min/max of the variable is a sound bound (the relaxation contains every integer solution), so a
 * finite LP optimum snaps the domain to it. Any side the LP leaves unbounded falls back to a searchable
 * range and marks the model clamped — so search still finds a SAT witness within it, while an `unsat`
 * over the box is reported as `unknown` (never a false `unsat`). Infinity thus never reaches search.
 */
internal fun SmtLibQfLia.Builder.boundUnboundedVars() {
    obbtBounds()
    finalizeDomains()
}

/** LP-tighten every domain side still reaching the unbounded sentinel. The relaxation is built over a
 *  [Problem.preFolded] problem so it never triggers the root bake (which would iterate the huge span). */
private fun SmtLibQfLia.Builder.obbtBounds() {
    val lo = unboundedIntLo
    val hi = unboundedIntHi
    if ((0 until nextInt).none { intDomains[it].min <= lo || intDomains[it].max >= hi }) return
    // The LP relaxation does exact `Long` arithmetic on the bounds, which overflows on a literal
    // Long.MIN/MAX span — so present each unbounded side to the LP as the ±Long/4 [NEG_INF]/[POS_INF]
    // sentinel (still "effectively infinite" for the relaxation, but overflow-safe).
    val lpDomains = Array(nextInt) { i ->
        val d = intDomains[i]
        val cLo = if (d.min < NEG_INF) NEG_INF else d.min
        val cHi = if (d.max > POS_INF) POS_INF else d.max
        if (cLo == d.min && cHi == d.max) d else IntDomain(cLo, cHi)
    }
    val p = Problem(
        numBoolVars = nextBool,
        numIntVars = nextInt,
        intDomains = lpDomains,
        factors = factors.toTypedArray(),
        preFolded = true,
    )
    val session = PropagationSession(p)
    val objective = LongArray(nextInt)
    for (v in 0 until nextInt) {
        val d = intDomains[v]
        var newMin = d.min
        var newMax = d.max
        if (d.max >= hi) lpBound(p, session, objective, v, maximize = true)?.let { if (it < newMax) newMax = it }
        if (d.min <= lo) lpBound(p, session, objective, v, maximize = false)?.let { if (it > newMin) newMin = it }
        if (newMin != d.min || newMax != d.max) {
            intDomains[v] = if (newMin <= newMax) IntDomain(newMin, newMax) else IntDomain(newMin, newMin)
        }
    }
}

/** A sound finite LP bound on `x[v]` (max when [maximize], else min), or null when the LP leaves it
 *  unbounded / infeasible / fails. Maximisation is `−min(−x)`; the Neumaier–Shcherbina safe bound keeps
 *  it sound under floating error. [objective] is the reusable single-variable objective (zeroed on exit). */
private fun SmtLibQfLia.Builder.lpBound(
    p: Problem,
    session: PropagationSession,
    objective: LongArray,
    v: Int,
    maximize: Boolean,
): Long? {
    objective[v] = if (maximize) -1L else 1L
    val relaxation = CpToLpRelaxation(p, LinearObjective(intCoefficients = objective)).build(session)
    objective[v] = 0L
    if (relaxation.model.n == 0) return null
    val result = try {
        RevisedSimplex(relaxation.model).solvePrimal()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    val safe = safeObjectiveLowerBound(relaxation.model, result.duals) ?: return null
    val bound = safe + relaxation.objectiveConstant.toDouble()
    if (!bound.isFinite()) return null
    // A bound at the ±Long/4 sentinel means the LP hit the artificial cap, not a real bound — the
    // variable is unbounded in that direction, so leave it for the searchable fallback.
    return if (maximize) {
        floor(-bound).toLong().let { if (it >= POS_INF) null else it }
    } else {
        ceil(bound).toLong().let { if (it <= NEG_INF) null else it }
    }
}

/** Clamp any side still at the unbounded sentinel to a searchable range and flag the model clamped. */
private fun SmtLibQfLia.Builder.finalizeDomains() {
    val lo = unboundedIntLo
    val hi = unboundedIntHi
    // A side still at the unbounded sentinel falls back to a searchable range: the caller's own
    // [unboundedIntLo]/[unboundedIntHi] when finite, else ±[SEARCH_FALLBACK] (the sentinel itself is
    // unsearchable). Clamping is lossy, so it flags the model — an `unsat` over the box is `unknown`.
    val fallbackLo = maxOf(lo, -SEARCH_FALLBACK)
    val fallbackHi = minOf(hi, SEARCH_FALLBACK)
    for (v in 0 until nextInt) {
        val d = intDomains[v]
        var newMin = d.min
        var newMax = d.max
        if (newMin <= lo) {
            newMin = fallbackLo
            domainsClamped = true
        }
        if (newMax >= hi) {
            newMax = fallbackHi
            domainsClamped = true
        }
        if (newMin != d.min || newMax != d.max) {
            intDomains[v] = if (newMin <= newMax) IntDomain(newMin, newMax) else IntDomain(newMin, newMin)
        }
    }
}
