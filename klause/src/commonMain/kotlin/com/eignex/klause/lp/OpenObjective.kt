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
 * Whether the LP relaxation over the **genuinely open** ranges already proves that nothing anywhere beats
 * [value] — the certificate that turns an optimum found inside the search box into the model's optimum.
 *
 * The relaxation drops integrality and nothing else, so its optimum is a bound on every integer solution's
 * objective, in the box and outside it alike. For a minimisation, a relaxation optimum at or above [value]
 * therefore says no integer point improves on [value], and the incumbent is optimal outright.
 *
 * This is the general form of the refutation [DeferredIntBounds.noBetterThan] runs. That one asks whether
 * "beats [value]" is infeasible, which needs the objective to be integral — "strictly better" has to
 * become "better by a whole unit" before a non-strict row can express it. Comparing against the dual bound
 * needs no such step, so it reaches the models whose objective carries continuous terms, which is most of
 * a MIP corpus.
 *
 * Two bounds are available and the stronger is preferred: the exact 128-bit integer dual bound when the
 * relaxation is all-integer, and otherwise the Neumaier-Shcherbina safe bound, which accounts for
 * floating-point error by directed rounding and so is a proof rather than an estimate (Neumaier and
 * Shcherbina, *Safe bounds in linear and mixed-integer linear programming*, Mathematical Programming 99,
 * 2004). Where neither is available the answer is `false`.
 *
 * The safe bound is weak wherever a column is genuinely open, and measurably so: an open side enters the
 * model as a probe-magnitude stand-in for infinity, and the safe bound scales its rounding-error term by
 * each column's box, so a reduced cost that float error nudges below zero is multiplied by that magnitude
 * and the bound collapses far below anything usable. It is still a bound — the collapse is downward — so
 * the certificate stays sound and merely goes quiet. Closing that needs an exact dual bound for models
 * with continuous columns, which [integerCertify] declines to give (#127).
 *
 * `false` always means "no conclusion", never "something better exists": an unexpressible relaxation, an
 * overflow, an unbounded or indeterminate solve, and a spent budget all answer `false`, leaving the caller
 * the clamped verdict it would have reported anyway.
 */
internal fun noWorseThanDualBound(
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
    // The relaxation minimises. A maximisation is certified by minimising the negated objective and
    // comparing against the negated incumbent, so one code path serves both directions.
    val sign = if (objective.maximize) -1L else 1L
    val intCost = LongArray(objective.intCoefficients.size) {
        val c = objective.intCoefficients[it]
        if (c == Long.MIN_VALUE) return false
        sign * c
    }
    val realCost = DoubleArray(objective.realCoefficients.size) { sign * objective.realCoefficients[it] }
    if (intCost.all { it == 0L } && realCost.all { it == 0.0 }) return false
    val rx = openRelaxation(
        openBounds,
        intConstraints,
        realConstraints,
        realLower = realLower,
        realUpper = realUpper,
        intCost = intCost,
        realCost = realCost,
    )
    val model = try {
        rx.builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return false
    }
    if (model.n == 0 || cancellation()) return false
    val outcome = solveAndCertify(model, cancellation = cancellation)
    if (outcome.verdict != LpVerdict.OPTIMAL) return false
    // `value` is the incumbent including the objective constant, which the relaxation does not carry.
    val target = subOrNull(value, objective.constant)?.let { mulOrNull(sign, it) } ?: return false
    outcome.exactLowerBound?.let { return it >= target }
    val safe = outcome.safeLowerBound ?: return false
    // Past 2^53 a `Long` target no longer round-trips through `Double`, and a target that rounded *down*
    // would let a bound below it read as above. Those decline rather than compare.
    if (target > EXACT_DOUBLE_LONG || target < -EXACT_DOUBLE_LONG) return false
    return safe.isFinite() && safe >= target.toDouble()
}

/** Largest magnitude a `Long` keeps exactly through a `Double`. */
private const val EXACT_DOUBLE_LONG = 1L shl 53

/** `a - b`, or null when it would wrap. */
private fun subOrNull(a: Long, b: Long): Long? {
    val d = a - b
    return if (((a xor b) and (a xor d)) < 0L) null else d
}

/** `a * b`, or null when it would wrap. */
private fun mulOrNull(a: Long, b: Long): Long? {
    val p = a * b
    return if (a != 0L && (p / a != b || (a == -1L && b == Long.MIN_VALUE))) null else p
}
