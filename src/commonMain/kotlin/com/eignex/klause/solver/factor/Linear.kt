package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

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
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
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
