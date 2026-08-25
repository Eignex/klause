package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.util.Bits

/** What the open-model presolve phase concluded. */
sealed interface OpenPresolveResult {
    /**
     * The model with every provable open side closed.
     *
     * @property spec the same model over tighter bounds; every other part of it is carried through.
     * @property closedSides how many open sides the phase proved a bound for.
     */
    class Tightened(val spec: ProblemSpec, val closedSides: Int) : OpenPresolveResult

    /**
     * The model has no solution.
     *
     * Derived over the genuinely open ranges, not inside an invented box, so it refutes the unbounded
     * model itself — the distinction that makes this reportable as `unsat` rather than as `unknown`.
     */
    data object Refuted : OpenPresolveResult
}

/**
 * Presolve a model whose integer sides are open, by proving bounds for them.
 *
 * The finite lane's presolve cannot run here: its passes read finite CP domains, and an open column has
 * none. What an open model can be given is the tightening that closes its sides — optimization-based
 * bound tightening over the unconditional linear rows, behind a feasibility-based interval prefilter.
 *
 * Only unconditional [Linear] rows participate. A reified row's truth is not decided yet and a wide row
 * carries no `Long` reading, so both are skipped: the phase then proves fewer bounds, never a wrong one.
 *
 * [cancellation] is polled between variables and threaded into each LP solve, so a budget spent partway
 * through leaves the bounds it had already proved — every one of them sound on its own.
 */
fun ProblemSpec.presolveOpen(cancellation: Cancellation = Cancellation.Never): OpenPresolveResult {
    val columns = numIntVars
    if (columns == 0) return OpenPresolveResult.Tightened(this, closedSides = 0)
    val declared = Array(columns) { v ->
        OpenIntBounds(
            if (intBounds.hasLower(v)) intBounds.lower(v) else null,
            if (intBounds.hasUpper(v)) intBounds.upper(v) else null,
        )
    }
    if (declared.none { it.lo == null || it.hi == null }) return OpenPresolveResult.Tightened(this, closedSides = 0)

    val rows = factors.filterIsInstance<Linear>()
    val tightened = tightenOpenIntBounds(
        declared,
        rows.filter { it.realVars.isEmpty() },
        cancellation = cancellation,
        realConstraints = rows.filter { it.realVars.isNotEmpty() },
        realLower = realLower,
        realUpper = realUpper,
    )
    if (tightened.refuted) return OpenPresolveResult.Refuted

    val bounds = tightened.bounds
    var closed = 0
    for (v in 0 until columns) {
        if (declared[v].lo == null && bounds[v].lo != null) closed++
        if (declared[v].hi == null && bounds[v].hi != null) closed++
    }
    if (closed == 0) return OpenPresolveResult.Tightened(this, closedSides = 0)
    return OpenPresolveResult.Tightened(withBounds(bounds), closedSides = closed)
}

/** This model over [bounds], carrying every other part of it through unchanged. */
private fun ProblemSpec.withBounds(bounds: Array<OpenIntBounds>): ProblemSpec {
    val lower = LongArray(bounds.size)
    val upper = LongArray(bounds.size)
    var openLo: Bits? = null
    var openHi: Bits? = null
    for (v in bounds.indices) {
        val lo = bounds[v].lo
        if (lo == null) (openLo ?: Bits(bounds.size).also { openLo = it }).set(v) else lower[v] = lo
        val hi = bounds[v].hi
        if (hi == null) (openHi ?: Bits(bounds.size).also { openHi = it }).set(v) else upper[v] = hi
    }
    return ProblemSpec(
        numBoolVars = numBoolVars,
        intBounds = IntBounds.fromModelBounds(lower, upper, openLo, openHi),
        factors = factors,
        seedDeductions = seedDeductions,
        cancellation = cancellation,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )
}
