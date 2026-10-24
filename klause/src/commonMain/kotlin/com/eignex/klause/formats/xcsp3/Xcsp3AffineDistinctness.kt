package com.eignex.klause.formats.xcsp3

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.util.Cancellation

internal class AffineDistinctGroup(val vars: IntArray, val offsets: LongArray)

internal class Xcsp3AffineDistinctness(private val workLimit: Int = 1_000_000) {
    private val edges = linkedMapOf<Int, MutableMap<Int, MutableSet<Long>>>()
    private var rows = 0
    private var edgeCount = 0
    private var stopped = false
    private var work = 0

    fun record(relation: LinRelation, op: LinearOp) {
        if (stopped) return
        if (++rows > MAX_ROWS) {
            stop()
            return
        }
        val row = relation as? LinRelation.LongRel ?: return
        if (op != LinearOp.NE || row.vars.size != 2) return
        val positive = row.coeffs.indexOf(1L)
        val negative = row.coeffs.indexOf(-1L)
        if (positive < 0 || negative < 0 || row.bound == Long.MIN_VALUE) return
        val first = row.vars[positive]
        val second = row.vars[negative]
        if (first == second) return
        if (edges.size + (if (first in edges) 0 else 1) + (if (second in edges) 0 else 1) > MAX_VARIABLES) {
            stop()
            return
        }
        val forward = edges.getOrPut(first) { linkedMapOf() }.getOrPut(second) { linkedSetOf() }
        if (!forward.add(row.bound)) return
        edges.getOrPut(second) { linkedMapOf() }.getOrPut(first) { linkedSetOf() }.add(-row.bound)
        if (++edgeCount > MAX_EDGES || forward.size > MAX_ALTERNATIVES) stop()
    }

    fun groups(cancellation: Cancellation): List<AffineDistinctGroup> {
        if (stopped) return emptyList()
        val result = ArrayList<AffineDistinctGroup>()
        val visited = HashSet<Int>()
        for (root in edges.keys) {
            if (!spend(cancellation)) break
            if (!visited.add(root)) continue
            val component = arrayListOf(root)
            var cursor = 0
            while (cursor < component.size) {
                for (next in edges.getValue(component[cursor++]).keys) {
                    if (!spend(cancellation)) return result
                    if (visited.add(next)) component.add(next)
                }
            }
            if (component.size < 3 || component.any { edges.getValue(it).size != component.size - 1 }) continue
            component.sort()
            val vars = component.toIntArray()
            val anchor = edges.getValue(vars[0])
            val pivot = edges.getValue(vars[1])
            for (seed in anchor.getValue(vars[1])) {
                val offsets = LongArray(vars.size)
                offsets[1] = seed
                var consistent = true
                for (i in 2 until vars.size) {
                    var selected: Long? = null
                    for (offset in anchor.getValue(vars[i])) {
                        if (!spend(cancellation)) return result
                        val difference = subtractExact(offset, seed) ?: continue
                        if (difference !in pivot.getValue(vars[i])) continue
                        // Ambiguous triangles need a search; this bounded recognizer declines them.
                        if (selected != null) {
                            selected = null
                            break
                        }
                        selected = offset
                    }
                    if (selected == null) {
                        consistent = false
                        break
                    }
                    offsets[i] = selected
                }
                if (!consistent) continue
                for (i in vars.indices) {
                    for (j in i + 1 until vars.size) {
                        if (!spend(cancellation)) return result
                        val difference = subtractExact(offsets[j], offsets[i])
                        if (difference == null || difference !in edges.getValue(vars[i]).getValue(vars[j])) {
                            consistent = false
                            break
                        }
                    }
                    if (!consistent) break
                }
                if (consistent) result.add(AffineDistinctGroup(vars, offsets))
            }
        }
        return result
    }

    private fun spend(cancellation: Cancellation): Boolean {
        cancellation.charge(1)
        return ++work <= workLimit && !cancellation()
    }

    private fun stop() {
        stopped = true
        edges.clear()
    }

    private companion object {
        const val MAX_ROWS = 65_536
        const val MAX_VARIABLES = 512
        const val MAX_EDGES = 32_768
        const val MAX_ALTERNATIVES = 8
    }
}

private fun subtractExact(left: Long, right: Long): Long? {
    val difference = left - right
    return if (((left xor right) and (left xor difference)) < 0L) null else difference
}

internal fun Compiler.Builder.recoverAffineDistinctness(index: Xcsp3AffineDistinctness, cancellation: Cancellation) {
    for (group in index.groups(cancellation)) {
        if (cancellation()) return
        // Every channel must fit before any auxiliary is emitted. The source rows remain authoritative.
        if (group.vars.indices.any { i ->
            val offset = group.offsets[i]
            val domain = domains[group.vars[i]]
            if (offset >= 0) domain.max > Long.MAX_VALUE - offset else domain.min < Long.MIN_VALUE - offset
        }) continue
        val channels = IntArray(group.vars.size) { i ->
            materializeVar(IntComb.Narrow(
                LinComb(mapOf(group.vars[i] to 1L), group.offsets[i]),
            ))
        }
        postAllDifferent(channels)
    }
}
