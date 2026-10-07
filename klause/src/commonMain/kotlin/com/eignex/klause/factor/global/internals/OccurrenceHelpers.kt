package com.eignex.klause.factor.global.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongHashSet

/**
 * Reason that the variables of [hall] take values only among the union of their own domains, a set no larger
 * than the variables (a Hall set), with the presence [premises]: per variable, its bound lifted to the nearest
 * value outside the union, and each hole inside its bounds that the union lacks. Holes inside the union, and every
 * variable outside [hall], play no part.
 */
internal fun hallReason(state: PropagationState, hall: IntArray, premises: IntArray): IntArray? {
    val values = LongHashSet()
    for (x in hall) state.intDomains[x].values.forEach { values.add(it) }
    val seen = IntHashSet()
    val out = IntArrayList()
    fun add(lit: Int) {
        if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
    }
    val now = state.undo.size
    for (x in hall) {
        val d = state.intDomains[x]
        val root = state.rootDomains[x]
        var lo = d.min
        while (lo > root.min && root.lower(lo) in values) lo = root.lower(lo)
        if (lo > root.min) add(state.boundLiteral(x, true, lo, now, state.currentLevel))
        var hi = d.max
        while (hi < root.max && root.higher(hi) in values) hi = root.higher(hi)
        if (hi < root.max) add(state.boundLiteral(x, false, hi, now, state.currentLevel))
        d.forEachHole { k ->
            if (k !in values && k in root) {
                add(if (state.undoLogging) state.exclusionLiteral(x, k, now) else Lit.make(state.atomVarEq(x, k), true))
            }
        }
    }
    premises.forEach { add(it) }
    return if (out.size == 0 && state.currentLevel == 0) null else out.toIntArray()
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
