package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.exactBoundsInfeasible
import com.eignex.klause.lp.longOrNull
import com.eignex.klause.lp.openLpInfeasible
import com.eignex.klause.lp.structuralIntBounds
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
 * Ahead of that runs an exact refutation over the same rows, in arithmetic that does not wrap. It answers
 * only in the refuting direction, so it costs a bounded pass and can only ever add a verdict.
 *
 * Behind it, each side the tightening leaves open is offered what the equality structure implies
 * ([structuralIntBounds]), which reaches the columns a `Long`/`Double` relaxation cannot represent. Both
 * kinds of bound are the model's own, so closing a side with either keeps it equisatisfiable — the
 * property that lets a later `unsat` be reported as one.
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
    val intRows = rows.filter { it.realVars.isEmpty() }
    // Exact arithmetic refutes ahead of the tightening below, which reads the same rows in `Long` and
    // `Double`. A coefficient times an open bound is the product that leaves 64 bits, so the systems
    // whose forced values are largest are the ones only this pass reaches.
    if (exactBoundsInfeasible(declared, intRows, cancellation)) return OpenPresolveResult.Refuted
    // Then the relaxation over the same open ranges. A Farkas ray reaches the systems no bound ever
    // crosses, which is what both the pass above and the tightening below need to conclude anything.
    if (openLpInfeasible(declared, intRows, cancellation)) return OpenPresolveResult.Refuted
    val tightened = tightenOpenIntBounds(
        declared,
        intRows,
        cancellation = cancellation,
        realConstraints = rows.filter { it.realVars.isNotEmpty() },
        realLower = realLower,
        realUpper = realUpper,
    )
    if (tightened.refuted) return OpenPresolveResult.Refuted

    val bounds = fillFromStructure(tightened.bounds, intRows, cancellation)
    // Both a tightened and a structural bound are necessary conditions on their column, so a pair that
    // crosses has no integer point between them — over the open ranges, not inside a box.
    if (bounds.any { crossed(it) }) return OpenPresolveResult.Refuted
    var closed = 0
    for (v in 0 until columns) {
        if (declared[v].lo == null && bounds[v].lo != null) closed++
        if (declared[v].hi == null && bounds[v].hi != null) closed++
    }
    if (closed == 0) return OpenPresolveResult.Tightened(this, closedSides = 0)
    return OpenPresolveResult.Tightened(withBounds(bounds), closedSides = closed)
}

/**
 * [tightened] with each still-open side filled from what the model's equality structure implies.
 *
 * Run only while a side is still open: the Hermite reduction behind it is the expensive half, and a
 * column the relaxation already closed has nothing left to gain. A structural bound is the model's own,
 * so unlike a fallback box it closes a side without costing equisatisfiability.
 */
private fun fillFromStructure(
    tightened: Array<OpenIntBounds>,
    rows: List<Linear>,
    cancellation: Cancellation,
): Array<OpenIntBounds> {
    if (tightened.none { it.lo == null || it.hi == null }) return tightened
    val structural = structuralIntBounds(tightened.size, rows, cancellation) ?: return tightened
    return Array(tightened.size) { v ->
        val b = tightened[v]
        if (b.lo != null && b.hi != null) {
            b
        } else {
            OpenIntBounds(
                b.lo ?: structural.lo.getOrNull(v)?.longOrNull(),
                b.hi ?: structural.hi.getOrNull(v)?.longOrNull(),
            )
        }
    }
}

/** Whether [b] pins a column to an empty range. */
private fun crossed(b: OpenIntBounds): Boolean {
    val lo = b.lo ?: return false
    val hi = b.hi ?: return false
    return lo > hi
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
