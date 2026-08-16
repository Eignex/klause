package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.EmptyDoubleArray
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Deferred integer-domain bounding, shared by the front-ends (SMT-LIB, MPS) whose models declare
 * unbounded integers. Parsing only closes each open side to the cheap fallback box (no LP), so a valid
 * finite [com.eignex.klause.solver.Problem] can be built; the **OBBT** LP tightening ([tightenOpenIntBounds])
 * is deferred to the presolve phase, where it runs under the solve deadline instead of unbounded at load.
 * [run] reproduces the load-time result exactly: the LP relaxation's min/max of a variable is a sound bound
 * (it contains every integer solution), so a finite optimum snaps the open side shut over the genuinely
 * unbounded region; any side the LP leaves open falls back to the searchable range and, when that box is
 * lossy, flags the model clamped so an `unsat` over it is reported `unknown` (never a false `unsat`).
 */
class DeferredIntBounds internal constructor(
    /** OBBT input: per int var, the declared bounds with a genuinely open side left `null`. */
    private val openBounds: Array<OpenIntBounds>,
    /** Pure-integer linear rows (the relaxation OBBT tightens over). */
    private val intConstraints: List<Linear>,
    /** Real-bearing linear rows (context for the relaxation; only integer sides are written back). Empty
     *  for a front-end that gives OBBT no real context. */
    private val realConstraints: List<Linear>,
    /** Number of real columns the [realConstraints] range over (zero when there is no real context). */
    private val numReal: Int,
    private val fallbackLo: Long,
    private val fallbackHi: Long,
    /** True when the fallback box is not equisatisfiable with the unbounded model (so a side closed by it
     *  makes the model clamped); false when the small-model bound proves the box loses no solutions. */
    private val lossy: Boolean,
    /** True when [intConstraints] are the *whole* model — no booleans, no reals, no factor of any other
     *  kind — so a point satisfying them is a complete solution rather than a partial assignment the rest
     *  of the model might reject. Only then may [BoundedIntDomains.openSolution] be offered. */
    private val conjunctive: Boolean = false,
    /** Declared lower bounds of those real columns, or empty for a front-end that declares none — where a
     *  column reads as unbounded below, which is what a missing bound means. Load-bearing for the dual
     *  bound: a continuous column free below leaves the whole relaxation unbounded, and a MIP's columns
     *  are overwhelmingly `0 ≤ y`. */
    private val realLower: DoubleArray = EmptyDoubleArray,
    /** Declared upper bounds of the real columns; see [realLower]. */
    private val realUpper: DoubleArray = EmptyDoubleArray,
) {
    /** Run the deferred OBBT under [cancellation] (the solve deadline) and produce the final finite domains
     *  plus whether any side fell back to a lossy clamp. */
    fun run(cancellation: Cancellation): BoundedIntDomains {
        // Refute over the genuinely open ranges first. A certificate there rules out the unbounded model
        // itself, so the verdict needs no clamp caveat — where an `unsat` found inside the fallback box
        // would only ever be reportable as `unknown`.
        if (lossy && unboundedlyInfeasible(openBounds, intConstraints, cancellation)) {
            val domains = Array(openBounds.size) { IntDomain(0L, 0L) }
            return BoundedIntDomains(
                domains,
                clamped = false,
                openLo = BooleanArray(openBounds.size),
                openHi = BooleanArray(openBounds.size),
                openlyInfeasible = true,
            )
        }
        val tightened = tightenOpenIntBounds(
            openBounds,
            intConstraints,
            cancellation = cancellation,
            realConstraints = realConstraints,
            realLower = declaredRealLower(),
            realUpper = declaredRealUpper(),
        )
        // Where OBBT leaves a side open, the model's own structure may still bound it: the equality rows
        // drive a unimodular change of variables whose triangular block bounds its pivots by forward
        // substitution, and those ranges push back onto the original variables. A bound obtained that way
        // is the model's, not an invented box, so it closes the side WITHOUT making the model clamped.
        val structural = structuralBounds(openBounds.size, intConstraints, cancellation)
        var clamped = false
        val openLo = BooleanArray(openBounds.size)
        val openHi = BooleanArray(openBounds.size)
        val domains = Array(openBounds.size) { v ->
            val lo = tightened[v].lo ?: structural?.lo?.getOrNull(v)?.longOrNull() ?: fallbackLo.also {
                openLo[v] = true
                if (lossy) clamped = true
            }
            val hi = tightened[v].hi ?: structural?.hi?.getOrNull(v)?.longOrNull() ?: fallbackHi.also {
                openHi[v] = true
                if (lossy) clamped = true
            }
            if (lo <= hi) IntDomain(lo, hi) else IntDomain(lo, lo)
        }
        // Only where a side is still the invented box is there anything to gain: elsewhere the search runs
        // over the model's own domains and needs no witness handed to it.
        val solution = if (clamped && conjunctive) {
            unitCubeSolution(openBounds, intConstraints, cancellation)
        } else {
            null
        }
        return BoundedIntDomains(domains, clamped, openLo, openHi, openSolution = solution)
    }

    /**
     * Whether nothing anywhere improves on [value] — the certificate that turns an optimum proved inside
     * the search box into an optimum outright.
     *
     * An optimisation model can be bounded through its objective alone: the feasible region runs to
     * infinity in a column's direction and only cost stops it, so no bound tightening closes that side and
     * the box has to be invented. Asking the question this way sidesteps the box — "beats [value]" is
     * itself a linear row, and refuting it over the *genuinely open* ranges refutes it everywhere.
     *
     * False means "no conclusion", never "something better exists": an objective with no integer terms, a
     * target that would wrap, or a refutation that does not close all answer false, leaving the caller the
     * clamped verdict it would have reported anyway.
     *
     * [intCoefficients] and [constant] are the objective as the solver evaluates it, so [value] is
     * `constant + Σ intCoefficients·x` at the incumbent. Boolean objective weights are not covered.
     */
    fun noBetterThan(
        intCoefficients: LongArray,
        constant: Long,
        maximize: Boolean,
        value: Long,
        cancellation: Cancellation = Cancellation.Never,
    ): Boolean {
        val vars = intCoefficients.indices.filter { intCoefficients[it] != 0L }
        if (vars.isEmpty()) return false
        // Coefficients and the objective are integral, so "strictly better" is better by a whole unit.
        val target = addOrNull(value, if (maximize) 1L else -1L)?.let { addOrNull(it, -constant) } ?: return false
        val row = Linear(
            LongArray(vars.size) { intCoefficients[vars[it]] },
            vars.toIntArray(),
            if (maximize) LinearOp.GE else LinearOp.LE,
            target,
        )
        return unboundedlyInfeasible(openBounds, intConstraints + row, cancellation)
    }

    /**
     * The same certificate as [noBetterThan], for an objective that carries continuous terms.
     *
     * [noBetterThan] turns "strictly better" into "better by a whole unit" so a non-strict row can state
     * it, which needs the objective to be integral — and a MIP objective almost always is not. This states
     * the row strict instead and refutes it in exact rational arithmetic, where strictness is carried
     * rather than rounded away. See [nothingBeatsOverOpenRanges].
     *
     * False means "no conclusion", exactly as in [noBetterThan].
     */
    fun noWorseThan(
        intCoefficients: LongArray,
        realCoefficients: DoubleArray,
        constant: Long,
        maximize: Boolean,
        value: Long,
        cancellation: Cancellation = Cancellation.Never,
    ): Boolean = nothingBeatsOverOpenRanges(
        openBounds,
        intConstraints,
        realConstraints,
        declaredRealLower(),
        declaredRealUpper(),
        OpenObjective(intCoefficients, realCoefficients, constant, maximize),
        value,
        cancellation,
    )

    /** The real columns' lower bounds, defaulting an undeclared column to unbounded below. */
    private fun declaredRealLower(): DoubleArray = realBox(realLower, numReal, Double.NEGATIVE_INFINITY)

    /** The real columns' upper bounds, defaulting an undeclared column to unbounded above. */
    private fun declaredRealUpper(): DoubleArray = realBox(realUpper, numReal, Double.POSITIVE_INFINITY)
}

/** [declared] padded to [n] columns, with anything the front-end did not state left at [absent]. */
private fun realBox(declared: DoubleArray, n: Int, absent: Double): DoubleArray =
    if (declared.size >= n) declared else DoubleArray(n) { declared.getOrNull(it) ?: absent }

/** `a + b`, or null when it would wrap — a wrapped target is a row the model never stated. */
private fun addOrNull(a: Long, b: Long): Long? {
    val sum = a + b
    return if (((a xor sum) and (b xor sum)) < 0L) null else sum
}

/** Result of [DeferredIntBounds.run]. */
class BoundedIntDomains internal constructor(
    /** The closed finite integer domains, one per integer variable. */
    val domains: Array<IntDomain>,
    /** True when a side fell back to a lossy clamp, so an `unsat` over the box is only `unknown`. */
    val clamped: Boolean,
    /** Sides the box invented rather than derived: `true` where OBBT left the low side genuinely open and
     *  the domain's `min` is the fallback. Carried into `Problem.openIntLo` so the LP relaxation can build
     *  the column over its true range instead of the box. */
    val openLo: BooleanArray,
    /** Sides where the high bound is the fallback; see [openLo]. */
    val openHi: BooleanArray,
    /**
     * The model was refuted over its genuinely open ranges, so it has no solution at all — not merely
     * none inside the search box. [domains] are a placeholder the caller never searches; the verdict is
     * `unsat` outright, with none of the clamp caveat an in-box refutation would carry.
     */
    val openlyInfeasible: Boolean = false,
    /**
     * A complete integer solution found without any bounds at all, or null when none was — which is the
     * usual answer, since the cube test that produces it is incomplete.
     *
     * The mirror of [openlyInfeasible] on the satisfying side. Where that refutes a model the invented box
     * could only have reported `unknown`, this decides one the box was never wide enough to reach: the
     * point is verified against every row and declared bound before it is offered, and it is offered only
     * when those rows are the whole model.
     *
     * A caller that must produce *every* solution, or the best one, cannot use it — it is one witness, with
     * no claim to being the only or the best. Such a caller ignores it and searches as before.
     */
    val openSolution: LongArray? = null,
)

/**
 * Bounds implied by the model's own equality structure, or null when there are none to derive.
 *
 * Only equality rows participate: they are what pin variables down exactly, and the transformation uses
 * them to choose a basis whose triangular block bounds its pivot variables. Inequalities ride along
 * through the same change of variables. A side comes back null wherever the structure implies nothing,
 * which is the honest answer — inventing one there is precisely what the clamp does.
 */
private fun structuralBounds(numVars: Int, constraints: List<Linear>, cancellation: Cancellation): TriangularBounds? {
    if (numVars == 0) return null
    val eqRows = constraints.filter { it.op == LinearOp.EQ && it.isIntegerCore }
    if (eqRows.isEmpty()) return null
    val eq = Array(eqRows.size) { r ->
        val row = Array(numVars) { BigInteger.ZERO }
        val f = eqRows[r]
        for (k in f.vars.indices) {
            val v = f.vars[k]
            if (v < numVars) row[v] = row[v] + BigInteger.fromLong(f.coeff(k))
        }
        row
    }
    val rhsIn = Array(eqRows.size) { BigInteger.fromLong(eqRows[it].bound) }
    val mixed = mixedEchelonHermite(eq, emptyArray(), numVars, rhsIn, cancellation)
    if (mixed.equalities.isEmpty() || mixed.equalityRhs.size != mixed.equalities.size) return null
    // The reduced rows carry the reduced right-hand sides: pairing them with the *input* rows' bounds
    // would attach a bound to whichever row a swap happened to move into that slot.
    val rhs = Array<BigInteger?>(mixed.equalities.size) { mixed.equalityRhs[it] }
    val y = triangularBounds(mixed.equalities, rhs, rhs)
    return mixed.originalBounds(y.lo, y.hi)
}

/** This value as a [Long], or null when it does not fit — a bound past `Long` cannot close a side here. */
private fun BigInteger.longOrNull(): Long? =
    if (this in BigInteger.fromLong(Long.MIN_VALUE)..BigInteger.fromLong(Long.MAX_VALUE)) longValue() else null
