package com.eignex.klause.factor

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

internal fun IntArray.litVars(vararg extra: Int): IntArray {
    val seen = IntHashSet()
    val out = IntArrayList()
    for (v in extra) if (seen.add(v)) out.add(v)
    for (lit in this) {
        val v = Lit.variable(lit)
        if (seen.add(v)) out.add(v)
    }
    return out.toIntArray()
}
