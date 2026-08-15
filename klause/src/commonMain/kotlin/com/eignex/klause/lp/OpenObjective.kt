package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.Cancellation

/**
 * The objective an in-box optimum has to be measured against, as the solver evaluates it.
 *
 * [intCoefficients] is indexed by integer variable, [realCoefficients] by real variable, and the value at
 * a point is `constant + Σ intCoefficients·x + Σ realCoefficients·r`.
 */
internal class OpenObjective(
    val intCoefficients: LongArray,
    val realCoefficients: DoubleArray,
    val constant: Long,
    val maximize: Boolean,
)

/**
 * Whether nothing anywhere beats [value] — the certificate that turns an optimum found inside the search
 * box into the model's optimum, for an objective that need not be integral.
 *
 * "Something beats [value]" is a row, and refuting it over the **genuinely open** ranges refutes it
 * everywhere. The relaxation drops integrality and nothing else, so an integer point beating [value] would
 * be a point of the relaxation beating it; no such point means no such integer point.
 *
 * The one thing that row needs is *strictness*. [DeferredIntBounds.noBetterThan] gets it by rounding — an
 * integral objective improves by whole units, so `< value` can be stated as `≤ value − 1` — and a
 * continuous term takes that away. Here the row is stated strict and the model decided in exact rational
 * arithmetic, where a strict row's right-hand side is carried by an infinitesimal rather than approximated
 * ([rationalOutcome]). No rounding step, so no integrality requirement.
 *
 * Deliberately not a dual-bound comparison, which is the other way to ask this and the one that does not
 * work here: an open side enters the model as a probe-magnitude stand-in for infinity, and the safe dual
 * bound scales its rounding-error term by each column's box, so a reduced cost that float error nudges
 * below zero is multiplied by that magnitude and the bound collapses far below anything usable. Refuting
 * exactly asks the same question and never meets that magnitude.
 *
 * `false` always means "no conclusion", never "something better exists": an unexpressible relaxation, an
 * overflow, a feasible point, an indeterminate solve and a spent budget all answer `false`, leaving the
 * caller the clamped verdict it would have reported anyway.
 */
@Suppress("ReturnCount")
internal fun nothingBeatsOverOpenRanges(
    openBounds: Array<OpenIntBounds>,
    intConstraints: List<Linear>,
    realConstraints: List<Linear>,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    objective: OpenObjective,
    value: Long,
    cancellation: Cancellation = Cancellation.Never,
): Boolean {
    if (openBounds.isEmpty()) return false
    // Nothing is open ⇒ the search already ran over the real model and its optimum needs no certificate.
    if (openBounds.none { it.lo == null || it.hi == null }) return false
    // A split column pair needs `−c` as well as `c`, so a coefficient that cannot be negated is one the
    // row cannot state.
    if (objective.intCoefficients.any { it == Long.MIN_VALUE }) return false
    if (objective.realCoefficients.any { !it.isFinite() }) return false
    // The row states the objective's own value, which excludes the constant the solver adds back.
    val target = subOrNull(value, objective.constant)?.toDouble() ?: return false
    val rx = openRelaxation(openBounds, intConstraints, realConstraints, realLower, realUpper)
    val cols = ArrayList<Int>()
    val vals = ArrayList<Double>()
    for (v in objective.intCoefficients.indices) {
        val c = objective.intCoefficients[v]
        if (c == 0L || v >= rx.posCol.size) continue
        cols.add(rx.posCol[v])
        vals.add(c.toDouble())
        val neg = rx.negCol[v]
        if (neg >= 0) {
            cols.add(neg)
            vals.add(-c.toDouble())
        }
    }
    for (rv in objective.realCoefficients.indices) {
        val c = objective.realCoefficients[rv]
        if (c == 0.0) continue
        // A real column in no row of the relaxation has no column here to name, and leaving its term out
        // would state a different objective than the one being certified.
        val pos = rx.realPos[rv] ?: return false
        cols.add(pos)
        vals.add(c)
        rx.realNeg[rv]?.let {
            cols.add(it)
            vals.add(-c)
        }
    }
    if (cols.isEmpty()) return false
    rx.builder.addRealRow(
        cols.toIntArray(),
        vals.toDoubleArray(),
        if (objective.maximize) Relation.GE else Relation.LE,
        target,
        strict = true,
    )
    val model = try {
        rx.builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return false
    }
    if (model.n == 0 || cancellation()) return false
    return solveAndCertify(model, cancellation = cancellation).verdict == LpVerdict.INFEASIBLE
}

/** `a - b`, or null when it would wrap — a wrapped target is a row the model never stated. */
private fun subOrNull(a: Long, b: Long): Long? {
    val d = a - b
    return if (((a xor b) and (a xor d)) < 0L) null else d
}
