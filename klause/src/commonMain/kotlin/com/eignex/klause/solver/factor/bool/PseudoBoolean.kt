package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet

/**
 * `Σ weights(i) * lit(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload(factorId)` is the current weighted sum. Terms pair
 * [weights] with [literals]; the sum is compared by [op] against [bound].
 */
class PseudoBoolean(val weights: IntArray, val literals: IntArray, val op: PbOp, val bound: Int) : Factor {

    override val intVars: IntArray = EmptyIntArray

    override fun structuralKey(): String = "pb:$op:$bound:" + literals.indices.sortedBy { literals[it] }.joinToString(
        ",",
    ) { "${literals[it]}=${weights[it]}" }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        PseudoBoolean(weights, literals.remapLits(boolMap), op, bound)

    override val boolVars: IntArray = literals.litVars()

    override fun asPropagator(): Propagator = PseudoBooleanPropagator(boolVars, intVars, weights, literals, op, bound)

    override fun asInvariant(): Invariant = PseudoBooleanInvariant(boolVars, weights, literals, op, bound)
}

/**
 * Build a clause-form antecedent set for a pin emitted by [propagatePbBounds] /
 * `propagatePbNotEqual`: each currently-pinned constraint literal (excluding the var
 * about to be pinned, which is still unassigned) expressed in its currently-*false*
 * polarity, plus an optional context literal (e.g. the reif var for
 * `ReifiedPseudoBoolean`). Returns `null` when nothing was pinned and no context lit —
 * meaning the pin is a level-0 fact with no logical preconditions.
 */
internal fun pbFalseFormAntecedents(
    state: PropagationState,
    literals: IntArray,
    excludeVar: Int,
    extraLit: Int, // 0 == no extra literal
): IntArray? {
    var n = 0
    if (extraLit != 0) n++
    val seen = IntHashSet()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        if (state.boolValues[v] != null) n++
    }
    if (n == 0) return null
    val out = IntArray(n)
    var w = 0
    if (extraLit != 0) out[w++] = extraLit
    seen.clear()
    for (lit in literals) {
        val v = Lit.variable(lit)
        if (v == excludeVar) continue
        if (extraLit != 0 && v == Lit.variable(extraLit)) continue
        if (!seen.add(v)) continue
        val b = state.boolValues[v] ?: continue
        out[w++] = Lit.make(v, !b)
    }
    return out
}

/**
 * Shared bounds-propagation routine for `Σ weights`i` * lit_i ⟨op⟩ bound`. Used by
 * [PseudoBoolean] directly and by `ReifiedPseudoBoolean` when its aux Boolean is pinned.
 * Returns `false` iff the constraint is infeasible. [extraLit] is an optional context
 * literal (currently false in state) to include in every pin's antecedents — used by
 * `ReifiedPseudoBoolean` to thread its reif-var pin into each implied propagation.
 */
internal fun propagatePbBounds(
    state: PropagationState,
    weights: IntArray,
    literals: IntArray,
    op: PbOp,
    bound: Long,
    extraLit: Int = 0,
): Boolean {
    val n = literals.size
    val litLo = LongArray(n)
    val litHi = LongArray(n)
    var sumLo = 0L
    var sumHi = 0L
    for (i in 0 until n) {
        val w = weights[i].toLong()
        val v = Lit.variable(literals[i])
        val b = state.boolValues[v]
        val lo: Long
        val hi: Long
        when {
            b == null -> {
                lo = minOf(0L, w)
                hi = maxOf(0L, w)
            }

            Lit.evaluate(literals[i], b) -> {
                lo = w
                hi = w
            }

            else -> {
                lo = 0L
                hi = 0L
            }
        }
        litLo[i] = lo
        litHi[i] = hi
        sumLo += lo
        sumHi += hi
    }
    when (op) {
        PbOp.LE -> if (sumLo > bound) return false
        PbOp.GE -> if (sumHi < bound) return false
        PbOp.EQ -> if (sumLo > bound || sumHi < bound) return false
    }
    for (i in 0 until n) {
        val w = weights[i].toLong()
        if (w == 0L) continue
        val v = Lit.variable(literals[i])
        if (state.boolValues[v] != null) continue
        val otherLo = sumLo - litLo[i]
        val otherHi = sumHi - litHi[i]
        val trueOk = pbFeasible(op, otherLo + w, otherHi + w, bound)
        val falseOk = pbFeasible(op, otherLo, otherHi, bound)
        if (!trueOk && !falseOk) return false
        if (!trueOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, !Lit.isPositive(literals[i]), ant)) return false
        } else if (!falseOk) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
            if (!state.pinBool(v, Lit.isPositive(literals[i]), ant)) return false
        }
    }
    return true
}

private fun pbFeasible(op: PbOp, lo: Long, hi: Long, bound: Long): Boolean = when (op) {
    PbOp.LE -> lo <= bound
    PbOp.GE -> hi >= bound
    PbOp.EQ -> lo <= bound && hi >= bound
}
