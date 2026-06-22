package com.eignex.klause.solver.factor.global.internals

import com.eignex.klause.solver.localsearch.LocalSearchState

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
