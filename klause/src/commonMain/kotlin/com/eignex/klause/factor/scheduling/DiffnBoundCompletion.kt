package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.binarySearchInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.plus
import com.eignex.klause.util.subExact
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class DiffnBoundCompletion(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val factorId: Int,
) {
    private var remainingTerms = TERM_CAP
    private var remainingVisits = VISIT_CAP

    fun complete(geometry: List<Move.IntSet>): List<Move.IntSet> {
        val targets = IntArray(TARGET_CAP)
        var count = 0
        var visits = 0
        for (part in geometry) {
            for (id in state.projection.intOccurrences[part.varId]) {
                if (visits++ == DISCOVERY_CAP || count == TARGET_CAP || !visit()) break
                val row = state.problem.factors[id] as? Linear ?: continue
                if (row.op != LinearOp.LE) continue
                val constants = row.integerConstants ?: continue
                if (!spend(row.vars.size)) return geometry
                for (i in row.vars.indices) {
                    val v = row.vars[i]
                    if (constants.coeffs[i] != -1L || !sink.allowsInt(v)) continue
                    if (state.projection.intOccurrences[v].binarySearchInt(factorId) >= 0) continue
                    if ((0 until count).any { targets[it] == v }) continue
                    targets[count++] = v
                    if (count == TARGET_CAP) break
                }
            }
            if (count == TARGET_CAP || visits >= DISCOVERY_CAP) break
        }
        val parts = ArrayList(geometry)
        for (i in 0 until count) {
            val v = targets[i]
            val target = lowerTarget(v, parts) ?: continue
            if (target < state.assignment.intValue(v)) parts.add(Move.IntSet(v, target))
        }
        return if (parts.size == geometry.size) geometry else parts
    }

    private fun lowerTarget(v: Int, parts: List<Move.IntSet>): Long? {
        var lower = bigIntOf(state.rootDomains[v].min)
        var found = false
        for (id in state.projection.intOccurrences[v]) {
            if (!visit()) return null
            val row = state.problem.factors[id] as? Linear ?: continue
            if (row.op != LinearOp.LE) continue
            val constants = row.integerConstants ?: return null
            if (!spend(row.vars.size)) return null
            val index = row.vars.indexOf(v)
            val coefficient = constants.coeffs[index]
            if (coefficient >= 0L) continue
            if (coefficient != -1L) return null
            val required = requiredLower(row, index, parts) ?: return null
            if (required > lower) lower = required
            found = true
        }
        if (!found || lower > bigIntOf(state.assignment.intValue(v))) return null
        return state.rootDomains[v].ceilingOrNull(lower.toLongExact())
    }

    private fun requiredLower(row: Linear, outputIndex: Int, parts: List<Move.IntSet>): BigInt? {
        val constants = checkNotNull(row.integerConstants)
        try {
            var sum = 0L
            for (i in row.vars.indices) {
                if (i == outputIndex) continue
                sum = addExact(sum, mulExact(constants.coeffs[i], value(row.vars[i], parts)))
            }
            return bigIntOf(subExact(sum, constants.bound))
        } catch (_: CheckedLongOverflowException) {
            if (!spend(row.vars.size)) return null
            var sum = BIG_ZERO
            for (i in row.vars.indices) {
                if (i == outputIndex) continue
                sum += bigIntOf(constants.coeffs[i]) * bigIntOf(value(row.vars[i], parts))
            }
            return sum - bigIntOf(constants.bound)
        }
    }

    private fun value(v: Int, parts: List<Move.IntSet>): Long =
        parts.firstOrNull { it.varId == v }?.newValue ?: state.assignment.intValue(v)

    private fun spend(terms: Int): Boolean {
        if (terms > remainingTerms) return false
        remainingTerms -= terms
        return true
    }

    private fun visit(): Boolean {
        if (remainingVisits == 0) return false
        remainingVisits--
        return true
    }

    private companion object {
        const val TARGET_CAP = 2
        const val DISCOVERY_CAP = 128
        const val TERM_CAP = 16_384
        const val VISIT_CAP = 16_384
    }
}
