package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.LinearOp

/**
 * One difference constraint `target − source ≤ bound`, the edge `source → target` of the constraint
 * graph. [guard] is the Boolean literal that must hold for it to be asserted, or [ALWAYS] when the row is
 * unconditional — a reified row only constrains once the search has decided its aux variable.
 *
 * [domainBound] marks an edge that states a declared domain side rather than a row of the model. Both
 * endpoints of such an edge are the constant node and one variable, so a model whose columns are all
 * bounded makes that node a hub of degree `2n` and every shortest-path search spans the whole graph. The
 * propagator therefore keeps these out of the graph and folds them into the distance query instead; see
 * [com.eignex.klause.propagation.difference.DifferenceSystemPropagator].
 */
internal class DifferenceEdge(
    val source: Int,
    val target: Int,
    val bound: Long,
    val guard: Int = ALWAYS,
    val domainBound: Boolean = false,
) {
    internal companion object {
        /** [guard] value for a row that holds unconditionally. */
        const val ALWAYS: Int = -1
    }
}

/**
 * The `target − source ≤ bound` form of a two-term row, or `null` when its shape is not a difference.
 *
 * A row qualifies when, after dividing out the coefficients' common factor, the pair is exactly `+1` and
 * `−1`. A one-term row qualifies too, as a difference against [zero] — the node standing for the constant.
 * Everything else (three terms, a sum, mismatched magnitudes, a disequality) is outside the fragment.
 *
 * Emits into [out] because an equality contributes both directions. Returns false when the row does not
 * qualify, leaving [out] untouched.
 */
@Suppress("ReturnCount")
internal fun appendDifferenceEdges(
    vars: IntArray,
    coeff: (Int) -> Long,
    op: LinearOp,
    bound: Long,
    zero: Int,
    guard: Int,
    out: MutableList<DifferenceEdge>,
): Boolean {
    if (op == LinearOp.NE) return false
    val n = vars.size
    if (n == 0 || n > 2) return false
    var g = 0L
    for (k in 0 until n) g = gcdOf(g, coeff(k))
    if (g == 0L) return false
    val c0 = coeff(0) / g
    val c1 = if (n == 2) coeff(1) / g else 0L
    val a = vars[0]
    val b = if (n == 2) vars[1] else zero
    val hi: Int
    val lo: Int
    when {
        n == 1 && c0 == 1L -> {
            hi = a
            lo = zero
        }

        n == 1 && c0 == -1L -> {
            hi = zero
            lo = a
        }

        c0 == 1L && c1 == -1L -> {
            hi = a
            lo = b
        }

        c0 == -1L && c1 == 1L -> {
            hi = b
            lo = a
        }

        else -> return false
    }
    when (op) {
        // Flooring is exact over the integers: it admits no value the original row forbids.
        LinearOp.LE -> out.add(DifferenceEdge(lo, hi, floorDiv(bound, g), guard))

        // `hi − lo ≥ b` is `lo − hi ≤ −b`, and the integer-tight form of `≥` ceils.
        LinearOp.GE -> out.add(DifferenceEdge(hi, lo, -ceilDiv(bound, g), guard))

        LinearOp.EQ -> {
            if (bound % g != 0L) return false // no integer point satisfies it; leave it to the general path
            out.add(DifferenceEdge(lo, hi, bound / g, guard))
            out.add(DifferenceEdge(hi, lo, -(bound / g), guard))
        }

        LinearOp.NE -> return false
    }
    return true
}

/**
 * The same for the row's *negation*, which a reified row asserts when its aux is false.
 *
 * Over the integers `¬(Σ ≤ b)` is `Σ ≥ b + 1` and `¬(Σ ≥ b)` is `Σ ≤ b − 1`, both differences again — so
 * a reified difference constrains under either polarity, and the false branch is as informative as the
 * true one. `=` negates to a disequality, which is not a difference, so it contributes nothing.
 */
internal fun appendNegatedDifferenceEdges(
    vars: IntArray,
    coeff: (Int) -> Long,
    op: LinearOp,
    bound: Long,
    zero: Int,
    guard: Int,
    out: MutableList<DifferenceEdge>,
): Boolean = when {
    op == LinearOp.LE && bound != Long.MAX_VALUE ->
        appendDifferenceEdges(vars, coeff, LinearOp.GE, bound + 1, zero, guard, out)

    op == LinearOp.GE && bound != Long.MIN_VALUE ->
        appendDifferenceEdges(vars, coeff, LinearOp.LE, bound - 1, zero, guard, out)

    else -> false
}

private fun gcdOf(a: Long, b: Long): Long {
    var x = if (a < 0) -a else a
    var y = if (b < 0) -b else b
    while (y != 0L) {
        val t = x % y
        x = y
        y = t
    }
    return x
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && ((a xor b) < 0L)) q - 1 else q
}

private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && ((a xor b) > 0L)) q + 1 else q
}
