package com.eignex.klause.localsearch

import com.eignex.klause.ir.Lit

internal object OptionalPresence {
    fun isPresentInAssignment(presents: IntArray, idx: Int, state: LocalSearchState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.assignment.boolValue(v)
        return if (Lit.isPositive(lit)) raw else !raw
    }
}
