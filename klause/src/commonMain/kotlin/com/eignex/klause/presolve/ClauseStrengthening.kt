package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList

internal object ClauseStrengthening {
    fun strengthenSource(problem: Problem, cancellation: Cancellation): SourceDelta {
        val rows = ArrayList<Row>()
        val occurrences = HashMap<Int, IntArrayList>()
        for ((origin, factor) in problem.factors.withIndex()) {
            if (!spend(cancellation, 1)) return SourceDelta()
            if (factor !is Clause || factor.tautological) continue
            var clean = true
            for (literal in factor.literals) {
                if (!spend(cancellation, 1)) return SourceDelta()
                if (Lit.variable(literal) >= problem.numBoolVars) clean = false
            }
            if (!clean) continue
            val size = factor.literals.size
            val sortDepth = Int.SIZE_BITS - size.countLeadingZeroBits()
            if (!spend(cancellation, size.toLong() * (sortDepth + 8))) return SourceDelta()
            val literals = factor.literals.sortedArray()
            if (cancellation()) return SourceDelta()
            val slot = rows.size
            rows.add(Row(origin, literals))
            for (literal in literals) {
                if (!spend(cancellation, 2)) return SourceDelta()
                occurrences.getOrPut(literal) { IntArrayList() }.add(slot)
            }
        }
        // Reserve the final delta scan and storage before any replacement can become visible.
        if (!spend(cancellation, 4L * rows.size)) return SourceDelta()
        val changed = IntArrayList(rows.size)
        val added = ArrayList<Factor>(rows.size)
        // Deletions cannot create an occurrence absent from this index; current rows reject stale entries.
        for (donor in rows) {
            val literals = donor.literals
            for (pivot in literals) {
                if (!spend(cancellation, 1)) return finish(rows, changed, added)
                var candidates = occurrences[Lit.negate(pivot)] ?: continue
                for (literal in literals) {
                    if (!spend(cancellation, 1)) return finish(rows, changed, added)
                    if (literal == pivot) continue
                    val bucket = occurrences.getValue(literal)
                    if (bucket.size < candidates.size) candidates = bucket
                }
                for (k in 0 until candidates.size) {
                    if (!spend(cancellation, 1)) return finish(rows, changed, added)
                    val slot = candidates[k]
                    val target = rows[slot]
                    if (target.literals.size < literals.size) continue
                    val implied = containsResolvent(literals, pivot, target.literals, cancellation)
                        ?: return finish(rows, changed, added)
                    if (!implied) continue
                    val size = target.literals.size - 1
                    if (cancellation()) return finish(rows, changed, added)
                    if (size == 0) return SourceDelta(infeasible = true)
                    if (!spend(cancellation, 17L * size + 1)) return finish(rows, changed, added)
                    val replacement = IntArray(size)
                    var next = 0
                    for (literal in target.literals) {
                        if (cancellation()) return finish(rows, changed, added)
                        if (literal != Lit.negate(pivot)) replacement[next++] = literal
                    }
                    val clause = Clause(replacement)
                    if (cancellation()) return finish(rows, changed, added)
                    if (target.replacement == null) changed.add(slot)
                    target.literals = replacement
                    target.replacement = clause
                    cancellation.recordClauseStrengthening()
                }
            }
        }
        return finish(rows, changed, added)
    }

    private class Row(val origin: Int, var literals: IntArray, var replacement: Clause? = null)

    private fun spend(cancellation: Cancellation, units: Long): Boolean {
        if (cancellation()) return false
        cancellation.charge(units)
        return !cancellation()
    }

    private fun containsResolvent(
        donor: IntArray,
        pivot: Int,
        target: IntArray,
        cancellation: Cancellation,
    ): Boolean? {
        for (literal in donor) {
            val required = if (literal == pivot) Lit.negate(pivot) else literal
            var lower = 0
            var upper = target.lastIndex
            var found = false
            while (lower <= upper) {
                if (!spend(cancellation, 1)) return null
                val middle = lower + (upper - lower) / 2
                when {
                    target[middle] < required -> lower = middle + 1
                    target[middle] > required -> upper = middle - 1
                    else -> {
                        found = true
                        break
                    }
                }
            }
            if (!found) return false
        }
        return true
    }

    private fun finish(rows: List<Row>, changed: IntArrayList, added: ArrayList<Factor>): SourceDelta {
        val dropped = IntArray(changed.size)
        for (k in 0 until changed.size) {
            val row = rows[changed[k]]
            dropped[k] = row.origin
            added.add(checkNotNull(row.replacement))
        }
        return SourceDelta(droppedIndices = dropped, addedFactors = added)
    }
}
