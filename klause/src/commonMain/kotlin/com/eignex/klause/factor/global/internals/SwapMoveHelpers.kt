package com.eignex.klause.factor.global.internals

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink

// Swaps two variables' values when each lies in the other's root domain.
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
        if (vb !in state.rootDomains[a] || va !in state.rootDomains[b]) continue
        sink.addCompound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
        emitted++
    }
}

// Rotates three distinct values (a←c, b←a, c←b). Like a swap it preserves the value multiset, keeping an
// all-different factor feasible, but reaches in one step what would take two improving swaps.
internal inline fun proposeRandomRotations(
    state: LocalSearchState,
    vars: IntArray,
    sink: MoveSink,
    cap: Int,
    stride: Int,
    crossinline isPresent: (state: LocalSearchState, idx: Int) -> Boolean,
) {
    if (vars.size < 3) return
    var emitted = 0
    var attempts = 0
    while (emitted < cap && attempts < cap * stride) {
        attempts++
        val ai = state.rng.nextInt(vars.size)
        val bi = state.rng.nextInt(vars.size)
        val ci = state.rng.nextInt(vars.size)
        if (ai == bi || bi == ci || ai == ci) continue
        if (!isPresent(state, ai) || !isPresent(state, bi) || !isPresent(state, ci)) continue
        val a = vars[ai]
        val b = vars[bi]
        val c = vars[ci]
        val va = state.assignment.intValue(a)
        val vb = state.assignment.intValue(b)
        val vc = state.assignment.intValue(c)
        if (va == vb || vb == vc || va == vc) continue
        if (vc !in state.rootDomains[a] || va !in state.rootDomains[b] ||
            vb !in state.rootDomains[c]
        ) {
            continue
        }
        sink.addCompound(listOf(Move.IntSet(a, vc), Move.IntSet(b, va), Move.IntSet(c, vb)))
        emitted++
    }
}
