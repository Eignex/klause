package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.newLpSolver
import com.eignex.klause.lp.safeVariableBound

/**
 * Close every still-[PresolveDomain.Open] integer variable to a finite domain before search. First
 * **OBBT** (optimization-based bound tightening): the LP relaxation's min/max of a variable is a sound
 * bound (the relaxation contains every integer solution), so a finite LP optimum snaps that side shut.
 * The relaxation is built directly from the linear factors; an unbounded variable side is expressed as a
 * genuinely infinite LP bound ([LpBuilder.addFreeVar]) and the LP engine reports "unbounded" for an
 * optimum that only reaches its own frontier — so a derived bound is always real, over the true
 * unbounded region. Any side OBBT leaves open falls back to a searchable range and marks the model
 * clamped, so search still finds a SAT witness while an `unsat` over the box is reported as `unknown`
 * (never a false `unsat`). Infinity thus never reaches search.
 */
internal fun SmtLibQfLia.Builder.boundUnboundedVars() {
    obbtBounds()
    finalizeDomains()
}

/** LP-tighten every open domain side via OBBT over a linear relaxation of the current [Linear] factors.
 *  Only linear constraints enter (fewer constraints ⇒ a looser but still-sound relaxation); each CP
 *  variable is one LP column, unbounded sides expressed as genuine ±∞. */
private fun SmtLibQfLia.Builder.obbtBounds() {
    val openVars = (0 until nextInt).filter { intDomains[it] is PresolveDomain.Open }
    if (openVars.isEmpty()) return
    val linears = factors.filterIsInstance<Linear>()
    for (v in openVars) {
        val d = intDomains[v] as? PresolveDomain.Open ?: continue
        var newLo = d.lo
        var newHi = d.hi
        if (d.openAbove) obbtSolve(linears, v, maximize = true)?.let { newHi = it }
        if (d.openBelow) obbtSolve(linears, v, maximize = false)?.let { newLo = it }
        intDomains[v] = openOrFinite(newLo, newHi)
    }
}

/** A sound finite LP bound on `target` (its max when [maximize], else its min), or null when the LP
 *  leaves it unbounded / infeasible / overflows. Each CP variable is a single LP column (coefficient 1);
 *  only `target`'s column carries the objective cost (`−1` maximizing, `+1` minimizing). */
private fun SmtLibQfLia.Builder.obbtSolve(linears: List<Linear>, target: Int, maximize: Boolean): Long? {
    val builder = LpBuilder()
    val col = IntArray(nextInt)
    for (v in 0 until nextInt) {
        val cost = if (v == target) (if (maximize) -1L else 1L) else 0L
        col[v] = when (val d = intDomains[v]) {
            is PresolveDomain.Finite -> builder.addVar(d.domain.min, d.domain.max, cost)
            is PresolveDomain.Open -> builder.addFreeVar(d.lo, d.hi, cost)
        }
    }
    for (f in linears) addFactorRow(builder, col, f)
    val model = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return null
    }
    val result = try {
        newLpSolver(model).solvePrimal()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    return model.safeVariableBound(result, col[target], maximize)
}

/** Add linear factor [f] (`Σ coeffs·vars op bound`) as an LP row over the mapped columns. Skips a
 *  non-`≤`/`≥`/`=` relation (a sound loosening: fewer constraints). */
private fun addFactorRow(builder: LpBuilder, col: IntArray, f: Linear) {
    val rel = when (f.op) {
        LinearOp.LE -> Relation.LE
        LinearOp.GE -> Relation.GE
        LinearOp.EQ -> Relation.EQ
        else -> return
    }
    val cols = IntArray(f.vars.size) { col[f.vars[it]] }
    builder.addRow(cols, f.coeffs.copyOf(), rel, f.bound)
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
