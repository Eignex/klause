package com.eignex.klause.solver.factor.global.internals

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** Random feasibility-preserving swap: picks two distinct variables and swaps their values if
 *  both values lie in each other's domain. [isPresent] gates on optional presence by index. */
internal inline fun proposeRandomSwaps(
    state: LocalSearchState,
    vars: IntArray,
    sink: MoveSink,
    cap: Int,
    stride: Int,
    crossinline isPresent: (state: LocalSearchState, idx: Int) -> Boolean,
) {
    if (vars.size < 2) return
    var emitted = 0
    var attempts = 0
    while (emitted < cap && attempts < cap * stride) {
        attempts++
        val ai = state.rng.nextInt(vars.size)
        val bi = state.rng.nextInt(vars.size)
        val a = vars[ai]
        val b = vars[bi]
        if (a == b) continue
        if (!isPresent(state, ai) || !isPresent(state, bi)) continue
        val va = state.assignment.intValue(a)
        val vb = state.assignment.intValue(b)
        if (va == vb) continue
        if (vb !in state.problem.intDomains[a] || va !in state.problem.intDomains[b]) continue
        sink.addCompound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
        emitted++
    }
}
