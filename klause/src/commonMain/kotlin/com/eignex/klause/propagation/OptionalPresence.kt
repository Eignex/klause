package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntArrayList

internal object OptionalPresence {
    fun isDefinitelyPresent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) raw else !raw
    }

    // Every used or skipped task contributes its presence premise, so the reason remains sound
    // when learned clauses outlive the current optional schedule.
    fun withPresencePremises(
        presents: IntArray,
        state: PropagationState,
        base: IntArray?,
        tasks: IntArray? = null,
    ): IntArray? {
        if (presents.isEmpty()) return base
        val out = IntArrayList()
        base?.forEach { out.add(it) }
        for (i in tasks ?: IntArray(presents.size) { it }) {
            when {
                isDefinitelyPresent(presents, i, state) -> out.add(Lit.negate(presents[i]))
                isDefinitelyAbsent(presents, i, state) -> out.add(presents[i])
            }
        }
        return if (out.size == 0) null else out.toIntArray()
    }

    fun isDefinitelyAbsent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return false
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) !raw else raw
    }
}
