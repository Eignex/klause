package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal class AffineDistinctGroup(val vars: IntArray, val offsets: LongArray, val sourceRows: IntArray)

internal class Xcsp3AffineDistinctness(private val workLimit: Int = 1_000_000) {
    private val edges = linkedMapOf<Int, MutableMap<Int, MutableMap<Long, MutableList<Int>>>>()
    private var rows = 0
    private var edgeCount = 0
    private var stopped = false
    private var work = 0

    fun record(relation: LinRelation, op: LinearOp, factorIndex: Int) {
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
        val forward = edges.getOrPut(first) { linkedMapOf() }.getOrPut(second) { linkedMapOf() }
        val existing = forward[row.bound]
        if (existing != null) {
            existing.add(factorIndex)
            return
        }
        val sources = arrayListOf(factorIndex)
        forward[row.bound] = sources
        edges.getOrPut(second) { linkedMapOf() }.getOrPut(first) { linkedMapOf() }[-row.bound] = sources
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
            for (seed in anchor.getValue(vars[1]).keys) {
                val offsets = LongArray(vars.size)
                offsets[1] = seed
                var consistent = true
                for (i in 2 until vars.size) {
                    var selected: Long? = null
                    for (offset in anchor.getValue(vars[i]).keys) {
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
                val sourceRows = ArrayList<Int>()
                for (i in vars.indices) {
                    for (j in i + 1 until vars.size) {
                        if (!spend(cancellation)) return result
                        val difference = subtractExact(offsets[j], offsets[i])
                        if (difference == null || difference !in edges.getValue(vars[i]).getValue(vars[j])) {
                            consistent = false
                            break
                        }
                        sourceRows.addAll(edges.getValue(vars[i]).getValue(vars[j]).getValue(difference))
                    }
                    if (!consistent) break
                }
                if (consistent) result.add(AffineDistinctGroup(vars, offsets, sourceRows.toIntArray()))
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
    val removed = HashSet<Int>()
    for (group in index.groups(cancellation)) {
        if (cancellation()) break
        val operands = group.vars.indices.map { i ->
            Distinct.Operand(
                intArrayOf(Term.ofIntVar(group.vars[i])),
                listOf(BigFraction.ONE),
                BigFraction.ofLong(group.offsets[i]),
            )
        }
        if (cancellation()) break
        factors.add(Distinct(operands))
        classes.inherit(factors.size - 1, group.sourceRows)
        group.sourceRows.forEach { removed.add(it) }
    }
    if (removed.isEmpty()) return
    classes.compact(removed)
    var factorIndex = 0
    factors.removeAll { factorIndex++ in removed }
}
