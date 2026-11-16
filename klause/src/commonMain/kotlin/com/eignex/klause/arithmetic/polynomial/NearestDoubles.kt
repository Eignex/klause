package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.util.ExecutionControl

// [values] with each root narrowed under [control] until [AlgebraicReal.nearestDouble] is settled. Each split
// removes a rounding bound from the isolating interval and lands within one unit in the last place of its midpoint,
// so a wide interval halves per split and every root settles after finitely many splits, one on a bound included. A
// split that makes no progress ends that root's narrowing. A stop or a representation limit keeps every interval
// narrowed so far. Returns [values] itself when every root is already settled.
internal fun narrowToNearestDoubles(
    values: List<AlgebraicReal>,
    control: ExecutionControl,
    limits: PolynomialLimits = PolynomialLimits(),
): List<AlgebraicReal> {
    if (values.all { it.nearestDouble != null }) return values
    val narrowed = values.toMutableList()
    val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(control, limits))
    try {
        for (id in narrowed.indices) {
            while (true) {
                val current = narrowed[id]
                val split = (current.binaryRounding() as? BinaryRounding.Split)?.at ?: break
                val next = arithmetic.splitAt(current, split)
                if (next.interval == current.interval) break
                narrowed[id] = next
            }
        }
    } catch (_: PolynomialInterrupted) {
        // Every coordinate keeps the narrowest interval it reached.
    } catch (_: PolynomialResourceLimit) {
        // As for a stop: the exact roots are unchanged.
    }
    return narrowed
}
