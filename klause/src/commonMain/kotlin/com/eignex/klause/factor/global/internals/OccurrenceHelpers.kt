package com.eignex.klause.factor.global.internals

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.PropagationState

/**
 * Domain antecedents over [vars] extended with the presence [premises] the filtering assumed — the
 * literals falsified by "these positions are present". A filtered position's absence would lift the
 * constraint entirely, so a reason that omits them is a nogood asserted unconditionally: the clause
 * store then carries it into states where the constraint does not apply. That also rules out the
 * `null` (root-only) answer, which would let the deduction be recorded as an unconditional root fact.
 */
internal fun antecedentsWithPremises(state: PropagationState, vars: IntArray, premises: IntArray): IntArray? {
    val base = collectHoleAndBoundAntecedents(state, vars)
    if (premises.isEmpty()) return base
    if (base == null) return premises
    val out = IntArray(base.size + premises.size)
    base.copyInto(out)
    premises.copyInto(out, base.size)
    return out
}

/** Counts how many present occurrences of [intVar] exist in [vars]. */
internal inline fun countPresentOccurrences(
    vars: IntArray,
    intVar: Int,
    state: LocalSearchState,
    crossinline isPresent: (state: LocalSearchState, idx: Int) -> Boolean,
): Int {
    var c = 0
    for (i in vars.indices) if (vars[i] == intVar && isPresent(state, i)) c++
    return c
}
