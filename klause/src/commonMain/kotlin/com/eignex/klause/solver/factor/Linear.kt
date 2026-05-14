package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.PropagationState
import com.eignex.klause.solver.SolverState
import com.eignex.klause.solver.ceilDivLong
import com.eignex.klause.solver.floorDivLong

enum class LinearOp { LE, EQ, GE }

/**
 * `Σ coeffs[i] * intVars[i] ⟨op⟩ bound`. Payload at `intPayload[factorId]` is the current
 * weighted sum, kept in sync incrementally by [applyIntSet]. Repair moves propose, for each
 * variable, the integer value that on its own would put the sum on the right side of [bound],
 * clamped to the variable's domain.
 */
class Linear(
    val coeffs: IntArray,
    val vars: IntArray,
    val op: LinearOp,
    val bound: Int,
) : Factor {

    init {
        require(coeffs.size == vars.size) { "coeffs/vars length mismatch" }
        require(coeffs.isNotEmpty()) { "Linear must have at least one term" }
    }

    override val boolVars: IntArray = IntArray(0)
    override val intVars: IntArray = vars

    private val coeffLookup: CoeffLookup = CoeffLookup.build(vars, coeffs)

    override fun initialize(state: SolverState, factorId: Int) {
        var sum = 0
        for (i in vars.indices) sum += coeffs[i] * state.assignment.intValue(vars[i])
        state.intPayload[factorId] = sum
    }

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        violates(state.intPayload[factorId])

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val coeff = coeffOf(intVar)
        val old = state.assignment.intValue(intVar)
        val sum = state.intPayload[factorId]
        val newSum = sum + coeff * (newValue - old)
        return (if (violates(newSum)) 1 else 0) - (if (violates(sum)) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val coeff = coeffOf(intVar)
        val cur = state.assignment.intValue(intVar)
        val oldSum = state.intPayload[factorId]
        val newSum = oldSum + coeff * (cur - oldValue)
        state.intPayload[factorId] = newSum
        return (if (violates(newSum)) 1 else 0) - (if (violates(oldSum)) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        propagateLinearBounds(state, coeffs, vars, op, bound.toLong())

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val sum = state.intPayload[factorId]
        if (!violates(sum)) return
        for (i in vars.indices) {
            val v = vars[i]
            val c = coeffs[i]
            if (c == 0) continue
            val cur = state.assignment.intValue(v)
            val sumWithout = sum - c * cur
            val target = snapTarget(c, sumWithout) ?: continue
            val clamped = state.problem.intDomains[v].clamp(target)
            if (clamped != cur) sink.addIntSet(v, clamped)
        }
    }

    private fun violates(sum: Int): Boolean = when (op) {
        LinearOp.LE -> sum > bound
        LinearOp.EQ -> sum != bound
        LinearOp.GE -> sum < bound
    }

    private fun coeffOf(intVar: Int): Int = coeffLookup.coeffOf(intVar)

    private fun snapTarget(coeff: Int, sumWithout: Int): Int? {
        val numerator = bound - sumWithout
        return when (op) {
            LinearOp.EQ -> if (numerator % coeff == 0) numerator / coeff else null
            LinearOp.LE -> if (coeff > 0) floorDiv(numerator, coeff) else ceilDiv(numerator, coeff)
            LinearOp.GE -> if (coeff > 0) ceilDiv(numerator, coeff) else floorDiv(numerator, coeff)
        }
    }

    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) < 0) q - 1 else q
    }

    private fun ceilDiv(a: Int, b: Int): Int {
        val q = a / b
        val r = a % b
        return if (r != 0 && (r xor b) >= 0) q + 1 else q
    }
}

/**
 * Shared bounds-propagation routine for `Σ coeffs[i] * vars[i] ⟨op⟩ bound`. Used by [Linear]
 * directly and by [ReifiedLinear] when its aux Boolean is pinned. Returns `false` iff the
 * domains became jointly infeasible.
 */
internal fun propagateLinearBounds(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
    op: LinearOp,
    bound: Long,
): Boolean {
    val n = vars.size
    val rLo = LongArray(n)
    val rHi = LongArray(n)
    var sumLo = 0L
    var sumHi = 0L
    for (i in 0 until n) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i].toLong()
        val a = c * d.min
        val b = c * d.max
        if (a <= b) { rLo[i] = a; rHi[i] = b } else { rLo[i] = b; rHi[i] = a }
        sumLo += rLo[i]
        sumHi += rHi[i]
    }
    when (op) {
        LinearOp.LE -> if (sumLo > bound) return false
        LinearOp.GE -> if (sumHi < bound) return false
        LinearOp.EQ -> if (sumLo > bound || sumHi < bound) return false
    }
    for (i in 0 until n) {
        val c = coeffs[i].toLong()
        if (c == 0L) continue
        val v = vars[i]
        val otherLo = sumLo - rLo[i]
        val otherHi = sumHi - rHi[i]
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            val slack = bound - otherLo
            if (c > 0) {
                if (!tightenMaxClamped(state, v, floorDivLong(slack, c))) return false
            } else {
                if (!tightenMinClamped(state, v, ceilDivLong(slack, c))) return false
            }
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            val needed = bound - otherHi
            if (c > 0) {
                if (!tightenMinClamped(state, v, ceilDivLong(needed, c))) return false
            } else {
                if (!tightenMaxClamped(state, v, floorDivLong(needed, c))) return false
            }
        }
    }
    return true
}

/**
 * Range `[sumLo, sumHi]` reachable by `Σ coeffs[i] * vars[i]` given current domains. Used by
 * reified factors to decide whether the body of a linear comparison is forced one way or the
 * other.
 */
internal fun linearSumRange(
    state: PropagationState,
    coeffs: IntArray,
    vars: IntArray,
): LongArray {
    var lo = 0L
    var hi = 0L
    for (i in vars.indices) {
        val d = state.intDomains[vars[i]]
        val c = coeffs[i].toLong()
        val a = c * d.min
        val b = c * d.max
        if (a <= b) { lo += a; hi += b } else { lo += b; hi += a }
    }
    return longArrayOf(lo, hi)
}

private fun tightenMinClamped(state: PropagationState, v: Int, newMin: Long): Boolean = when {
    newMin > Int.MAX_VALUE -> false
    newMin < Int.MIN_VALUE -> true
    else -> state.tightenIntMin(v, newMin.toInt())
}

private fun tightenMaxClamped(state: PropagationState, v: Int, newMax: Long): Boolean = when {
    newMax < Int.MIN_VALUE -> false
    newMax > Int.MAX_VALUE -> true
    else -> state.tightenIntMax(v, newMax.toInt())
}
