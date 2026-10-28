package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

internal sealed interface ExtensionalIntersection {
    data object Refuted : ExtensionalIntersection
    class Restricted(val factor: Factor) : ExtensionalIntersection
}

internal object ExtensionalComposition {
    internal class Limits(
        val work: Int = 8_388_608,
        val pairWork: Int = 2_097_152,
        val tableCells: Int = 262_144,
        val states: Int = 65_536,
        val records: Int = 131_072,
        val retainedCells: Int = 1_048_576,
        val cyclicAttempts: Int = 64,
        val cyclicWork: Int = 262_144,
        val cyclicCells: Int = 65_536,
    )

    private class Meter(val cancellation: Cancellation, val limits: Limits) {
        var remaining = limits.work
        var pairRemaining = Int.MAX_VALUE
        var retained = 0
        var cyclicAttempts = 0

        fun visit(): Boolean {
            if (remaining <= 0 || pairRemaining <= 0 || cancellation()) return false
            remaining--
            pairRemaining--
            cancellation.charge(1)
            return !cancellation()
        }
    }

    private class Tuple(val values: LongArray) {
        override fun hashCode(): Int = values.contentHashCode()
        override fun equals(other: Any?): Boolean = other is Tuple && values.contentEquals(other.values)
    }

    fun reduce(
        factors: Array<Factor>,
        cancellation: Cancellation,
        limits: Limits = Limits(),
    ): PassDelta {
        val meter = Meter(cancellation, limits)
        val occurrences = HashMap<Int, MutableList<Table>>()
        for (factor in factors) {
            if (!meter.visit()) return PassDelta()
            if (factor !is Table || !eligible(factor, limits)) continue
            for (variable in factor.xs) {
                if (!meter.visit()) return PassDelta()
                occurrences.getOrPut(variable) { ArrayList() }.add(factor)
            }
        }
        if (occurrences.isEmpty()) return PassDelta()
        val dropped = IntArrayList()
        val added = ArrayList<Factor>()
        for ((id, factor) in factors.withIndex()) {
            meter.pairRemaining = Int.MAX_VALUE
            val scope = when (factor) {
                is Table -> if (eligible(factor, limits)) factor.xs else continue
                is Mdd -> if (factor.seq.size <= MAX_SCOPE) factor.seq else continue
                else -> continue
            }
            val candidates = candidates(factor, scope, occurrences, meter) ?: break
            var current = factor
            for ((table, shared) in candidates) {
                if (shared < 2) continue
                meter.pairRemaining = limits.pairWork
                if (!meter.visit()) break
                val intersection = when (current) {
                    is Table -> if (eligible(current, limits)) intersectTables(current, table, meter) else null
                    is Mdd -> MddTableComposition.intersect(current, table, meter::visit, limits)
                    else -> break
                } ?: continue
                val next = when (intersection) {
                    ExtensionalIntersection.Refuted -> return PassDelta(infeasible = true)
                    is ExtensionalIntersection.Restricted -> intersection.factor
                }
                val cells = when (next) {
                    is Table -> next.tuples.size
                    is Mdd -> next.transitions.size + next.numStatesPerLayer.sum()
                    else -> 0
                }
                if (cells > limits.retainedCells - meter.retained) continue
                meter.retained += cells
                current = next
            }
            if (current is Table) {
                val next = intersectCycles(current, candidates.keys.toList(), meter)
                if (next === ExtensionalIntersection.Refuted) return PassDelta(infeasible = true)
                if (next is ExtensionalIntersection.Restricted) current = next.factor
            }
            if (current !== factor) {
                dropped.add(id)
                added.add(current)
            }
            if (meter.remaining <= 0 || cancellation()) break
        }
        return PassDelta(dropped.toIntArray(), added)
    }

    private fun intersectCycles(target: Table, neighbors: List<Table>, meter: Meter): ExtensionalIntersection? {
        var current = target
        for (a in neighbors.indices) for (b in a + 1 until neighbors.size) {
            meter.pairRemaining = Int.MAX_VALUE
            if (meter.cyclicAttempts >= meter.limits.cyclicAttempts || !meter.visit()) {
                return if (current === target) null else ExtensionalIntersection.Restricted(current)
            }
            val left = neighbors[a]
            val right = neighbors[b]
            repeat(left.arity * (right.arity + current.arity)) {
                if (!meter.visit()) {
                    return if (current === target) null else ExtensionalIntersection.Restricted(current)
                }
            }
            if (left.xs.none { it in right.xs && it !in current.xs }) continue
            meter.cyclicAttempts++
            meter.pairRemaining = meter.limits.cyclicWork
            val intersection = CyclicTableComposition.intersect(
                current, left, right, meter::visit, meter.limits.cyclicCells,
            ) ?: continue
            if (intersection === ExtensionalIntersection.Refuted) return intersection
            val next = (intersection as ExtensionalIntersection.Restricted).factor as Table
            if (next.tuples.size > meter.limits.retainedCells - meter.retained) continue
            meter.retained += next.tuples.size
            current = next
        }
        return if (current === target) null else ExtensionalIntersection.Restricted(current)
    }

    private fun candidates(
        factor: Factor,
        scope: IntArray,
        occurrences: Map<Int, List<Table>>,
        meter: Meter,
    ): Map<Table, Int>? {
        val counts = LinkedHashMap<Table, Int>()
        for (variable in scope) {
            if (!meter.visit()) return null
            for (table in occurrences[variable].orEmpty()) {
                if (!meter.visit()) return null
                if (factor !== table) counts[table] = (counts[table] ?: 0) + 1
            }
        }
        return counts
    }

    private fun eligible(table: Table, limits: Limits): Boolean =
        table.hi == null && table.arity <= MAX_SCOPE && table.tuples.size <= limits.tableCells &&
            table.xs.toSet().size == table.arity

    private fun intersectTables(left: Table, right: Table, meter: Meter): ExtensionalIntersection? {
        val leftColumns = IntArrayList()
        val rightColumns = IntArrayList()
        for (column in left.xs.indices) {
            if (!meter.visit()) return null
            val other = right.xs.indexOf(left.xs[column])
            if (other >= 0) {
                leftColumns.add(column)
                rightColumns.add(other)
            }
        }
        if (leftColumns.size < 2) return null
        val allowed = HashSet<Tuple>()
        for (row in 0 until right.numTuples) {
            val tuple = LongArray(rightColumns.size)
            for (i in tuple.indices) {
                if (!meter.visit()) return null
                tuple[i] = right.tuples[row * right.arity + rightColumns[i]]
            }
            allowed.add(Tuple(tuple))
        }
        val kept = LongArrayList()
        for (row in 0 until left.numTuples) {
            val tuple = LongArray(leftColumns.size)
            for (i in tuple.indices) {
                if (!meter.visit()) return null
                tuple[i] = left.tuples[row * left.arity + leftColumns[i]]
            }
            if (Tuple(tuple) !in allowed) continue
            for (column in left.xs.indices) {
                if (!meter.visit()) return null
                kept.add(left.tuples[row * left.arity + column])
            }
        }
        if (kept.size == left.tuples.size) return null
        return if (kept.isEmpty()) ExtensionalIntersection.Refuted
        else ExtensionalIntersection.Restricted(Table(left.xs, kept.toLongArray()))
    }

    internal const val MAX_SCOPE = 64
}
