package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.exactBoundsInfeasible
import com.eignex.klause.lp.longOrNull
import com.eignex.klause.lp.openLpInfeasible
import com.eignex.klause.lp.structuralIntBounds
import com.eignex.klause.lp.tightenOpenIntBounds
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation

/** What the open-model presolve phase concluded. */
sealed interface OpenPresolveResult {
    /**
     * The model with every provable open side closed.
     *
     * @property spec the same model over tighter bounds; every other part of it is carried through.
     * @property closedSides how many open sides the phase proved a bound for.
     */
    class Tightened(val spec: Problem, val closedSides: Int) : OpenPresolveResult

    /**
     * The model has no solution.
     *
     * Derived over the genuinely open ranges, not inside an invented box, so it refutes the unbounded
     * model itself — the distinction that makes this reportable as `unsat` rather than as `unknown`.
     */
    data object Refuted : OpenPresolveResult
}

/**
 * Presolve a model whose integer sides are open: source-safe factor passes, then [closeOpenBounds].
 *
 * The two are separable, and callers that route from the prepared model run them apart — the factor
 * passes decide which lane owns what, so they have to finish before ownership is selected.
 */
fun Problem.presolveOpen(cancellation: Cancellation = Cancellation.Never): OpenPresolveResult =
    presolveOpen(PresolveConfig.DEFAULT, null, false, cancellation, null)

/** Internal open preparation with the caller's resolved source-presolve policy. */
internal fun Problem.presolveOpen(
    config: PresolveConfig = PresolveConfig.DEFAULT,
    linearObjective: LinearObjective? = null,
    solutionSetSensitive: Boolean = false,
    cancellation: Cancellation = Cancellation.Never,
    presolveBudget: PresolveBudget? = null,
): OpenPresolveResult {
    val preparationCancellation = Cancellation { cancellation() || presolveBudget?.remaining() == 0L }
    val source = PresolvePipeline.prepareSource(
        this,
        config,
        linearObjective,
        solutionSetSensitive,
        preparationCancellation,
        presolveBudget,
    )
    if (source.infeasible) return OpenPresolveResult.Refuted
    return source.problem.closeOpenBounds(preparationCancellation)
}

/**
 * Close what the model's own rows prove about its open integer sides.
 *
 * Runs on a model source-safe preparation has already produced, and only ever narrows bounds: the factor
 * set it reads is the one the caller's component plan was selected from, and stays so.
 *
 * Sides close by optimization-based bound tightening over the unconditional linear rows, behind a
 * feasibility-based interval prefilter.
 *
 * Ahead of that runs an exact refutation over the same rows, in arithmetic that does not wrap. It answers
 * only in the refuting direction, so it costs a bounded pass and can only ever add a verdict.
 *
 * Behind it, each side the tightening leaves open is offered what the equality structure implies
 * ([structuralIntBounds]), which reaches the columns a `Long`/`Double` relaxation cannot represent. Both
 * kinds of bound are the model's own, so closing a side with either keeps it equisatisfiable — the
 * property that lets a later `unsat` be reported as one.
 *
 * Unconditional [Linear] rows and rows whose reifying Boolean is fixed by a unit clause participate. A
 * wide row carries no `Long` reading, so it is skipped: the phase then proves fewer bounds, never a
 * wrong one.
 *
 * [cancellation] is polled between variables and threaded into each LP solve, so a budget spent partway
 * through leaves the bounds it had already proved — every one of them sound on its own.
 */
internal fun Problem.closeOpenBounds(cancellation: Cancellation = Cancellation.Never): OpenPresolveResult {
    val problem = this
    val columns = problem.numIntVars
    if (columns == 0) return OpenPresolveResult.Tightened(problem, closedSides = 0)
    val declared = Array(columns) { v ->
        OpenIntBounds(
            if (problem.intBounds.hasLower(v)) problem.intBounds.lower(v) else null,
            if (problem.intBounds.hasUpper(v)) problem.intBounds.upper(v) else null,
        )
    }
    if (declared.none { it.lo == null || it.hi == null }) {
        return OpenPresolveResult.Tightened(problem, closedSides = 0)
    }

    val rows = problem.openPresolveRows()
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
        realLower = problem.realLower,
        realUpper = problem.realUpper,
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
    // A declared value set the proved range empties refutes the model whether or not that range also
    // closed an open side, so the intersection runs ahead of the no-closure shortcut rather than behind it.
    val tighter = problem.withBounds(bounds) ?: return OpenPresolveResult.Refuted
    if (closed == 0) return OpenPresolveResult.Tightened(problem, closedSides = 0)
    return OpenPresolveResult.Tightened(tighter, closedSides = closed)
}

/** Unconditional integer rows plus the integer rows a root unit clause asserts. */
private fun Problem.openPresolveRows(): List<Linear> {
    val rows = ArrayList<Linear>()
    for (factor in factors) {
        if (factor is Linear) rows += roundIntegerRow(factor)
    }
    for (row in rootFixedReifiedRows(factors.asList())) rows += roundIntegerRow(row)
    return rows
}

/** Divide a whole integer inequality by its coefficient gcd, rounding its bound inward. */
private fun roundIntegerRow(row: Linear): Linear {
    val constants = row.integerConstants ?: return row
    val gcd = PresolveShared.gcdOf(constants.coeffs)
    if (gcd <= 1L) return row
    val bound = when (row.op) {
        LinearOp.LE -> floorDivLong(constants.bound, gcd)
        LinearOp.GE -> ceilDivLong(constants.bound, gcd)
        else -> return row
    }
    return Linear(
        LongArray(constants.coeffs.size) { constants.coeffs[it] / gcd },
        row.vars.copyOf(),
        row.op,
        bound,
    )
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

/**
 * This model over [bounds], carrying every other part of it through unchanged, or `null` when the tighter
 * range leaves a declared value set empty.
 *
 * A column that declares a value set keeps it: the proved bounds intersect that set rather than replacing
 * it, so a non-contiguous declaration does not widen back to its hull on the way through. The
 * intersection can empty a column the endpoints alone would not have crossed, and an empty column is a
 * refutation.
 */
private fun Problem.withBounds(bounds: Array<OpenIntBounds>): Problem? {
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
    val rebounded = declaredIntDomains.rebounded(IntBounds.fromModelBounds(lower, upper, openLo, openHi))
        ?: return null
    return Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        declaredIntDomains = rebounded,
        factors = factors,
        impliedFactorMask = impliedFactorMask,
        hasSymmetryBreaking = hasSymmetryBreaking,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )
}
