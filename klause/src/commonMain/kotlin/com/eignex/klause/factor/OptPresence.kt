package com.eignex.klause.factor

import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

internal object OptPresence {
    // Deduplicated because boolVars is a scope: a shared presence variable listed per position would be
    // flipped once per position by scope-walking moves, and an even count cancels to no move.
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
