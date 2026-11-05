package com.eignex.klause.factor

import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

internal object OptPresence {
    // Factor constructors call this to extend boolVars so the propagation engine wakes on presence changes.
    // Deduplicated in first-occurrence order: boolVars is a variable *scope*, and positions sharing one
    // presence variable would otherwise enter every occurrence list once per position and be flipped once
    // per position by the moves that walk a factor's scope (an even count cancelling out to no move).
    fun presenceVarIds(presents: IntArray): IntArray {
        if (presents.isEmpty()) return emptyIntArray()
        val seen = IntHashSet(presents.size)
        val out = IntArrayList(presents.size)
        for (lit in presents) {
            val v = Lit.variable(lit)
            if (seen.add(v)) out.add(v)
        }
        return out.toIntArray()
    }
}
