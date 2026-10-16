package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.Cancellation

internal fun PreparedSourcePlan.sourceCoordinates(cancellation: Cancellation): VarRemap? {
    if (cancellation()) return null
    val bools = IntArray(sourceBoolCount)
    val ints = IntArray(sourceIntCount)
    for (id in bools.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        bools[id] = booleanCoordinate(id)
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        ints[id] = integerCoordinate(id)
    }
    return VarRemap(bools, ints)
}

internal fun SearchHints.remapped(mapping: VarRemap, cancellation: Cancellation): SearchHints? {
    val groups = ArrayList<SearchGroup>(tiers.size)
    for (group in tiers) {
        cancellation.charge(1)
        if (cancellation()) return null
        val bools = mappedRoles(group.boolVars, mapping.boolCount, mapping::bool, cancellation) ?: return null
        val ints = mappedRoles(group.intVars, mapping.intCount, mapping::int, cancellation) ?: return null
        if (bools.isNotEmpty() || ints.isNotEmpty()) groups += group.copy(boolVars = bools, intVars = ints)
    }
    return if (cancellation()) null else copy(tiers = groups)
}

internal fun VarRemap.followedBy(next: VarRemap, cancellation: Cancellation): VarRemap? {
    if (cancellation()) return null
    val bools = IntArray(boolCount)
    val ints = IntArray(intCount)
    for (id in bools.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val target = bool(id)
        if (target >= next.boolCount) return null
        bools[id] = if (target < 0) -1 else next.bool(target)
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val target = int(id)
        if (target >= next.intCount) return null
        ints[id] = if (target < 0) -1 else next.int(target)
    }
    return VarRemap(bools, ints)
}

private fun mappedRoles(
    ids: IntArray,
    count: Int,
    rename: (Int) -> Int,
    cancellation: Cancellation,
): IntArray? {
    val result = ArrayList<Int>(ids.size)
    for (id in ids) {
        cancellation.charge(1)
        if (cancellation() || id !in 0 until count) return null
        val target = rename(id)
        if (target >= 0) result += target
    }
    return result.toIntArray()
}
