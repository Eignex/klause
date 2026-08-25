package com.eignex.klause.factor

import com.eignex.klause.solver.Lit

// extra prefixes aux vars (e.g. a reification var) that the factor tracks but aren't in the literals.
internal fun IntArray.litVars(vararg extra: Int): IntArray {
    val seen = LinkedHashSet<Int>()
    for (v in extra) seen.add(v)
    for (lit in this) seen.add(Lit.variable(lit))
    val out = IntArray(seen.size)
    var i = 0
    for (v in seen) out[i++] = v
    return out
}
