package com.eignex.klause.arithmetic.difference

import com.eignex.klause.factor.arithmetic.LinearOp

internal class DifferenceEdge(
    val source: Int,
    val target: Int,
    val bound: Long,
    val guard: Int = ALWAYS,
    val domainBound: Boolean = false,
) {
    internal companion object {
        const val ALWAYS: Int = -1
    }
}

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
