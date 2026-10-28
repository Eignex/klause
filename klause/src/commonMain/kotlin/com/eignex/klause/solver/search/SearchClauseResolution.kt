package com.eignex.klause.solver.search

internal fun resolveSearchClauses(left: IntArray, right: IntArray, pivot: Int): IntArray? {
    if (pivot !in left || pivot xor 1 !in right) return null
    if (pivot xor 1 in left || pivot in right) return null
    val resolved = LinkedHashSet<Int>(left.size + right.size)
    for (literal in left) if (literal != pivot) {
        if (literal xor 1 in resolved) return null
        resolved.add(literal)
    }
    for (literal in right) if (literal != (pivot xor 1)) {
        if (literal xor 1 in resolved) return null
        resolved.add(literal)
    }
    return resolved.toIntArray()
}
