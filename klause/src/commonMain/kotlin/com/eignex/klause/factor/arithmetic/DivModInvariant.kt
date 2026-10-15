package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.factor.saturatedAdd
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class DivModInvariant(private val relation: DivMod) : Invariant {
    private fun grade(state: LocalSearchState, value: (Int) -> Long): Int {
        val wordDivisor = relation.divisor?.let(value) ?:
            relation.constantDivisor?.takeIf { it.fitsLong() }?.toLongExact()
        if (wordDivisor != null) {
            val outputs = relation.wordOutputs(value(relation.dividend), wordDivisor)
                ?: return compressViolation(Long.MAX_VALUE, state.violationSoftCap)
            return compressViolation(saturatedAdd(distance(outputs.first, value(relation.quotient)),
                distance(outputs.second, value(relation.remainder))), state.violationSoftCap)
        }
        val outputs = relation.outputs(bigIntOf(value(relation.dividend)),
            relation.divisor?.let { bigIntOf(value(it)) } ?: checkNotNull(relation.constantDivisor))
            ?: return compressViolation(Long.MAX_VALUE, state.violationSoftCap)
        fun error(expected: BigInt, actual: Long): Long =
            if (expected.fitsLong()) distance(expected.toLongExact(), actual) else Long.MAX_VALUE
        return compressViolation(saturatedAdd(error(outputs.first, value(relation.quotient)),
            error(outputs.second, value(relation.remainder))), state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        grade(state, state.assignment::intValue) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = grade(state, state.assignment::intValue)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        grade(state) { if (it == intVar) newValue else state.assignment.intValue(it) } -
            grade(state, state.assignment::intValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        grade(state, state.assignment::intValue) -
            grade(state) { if (it == intVar) oldValue else state.assignment.intValue(it) }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val current = grade(state, state.assignment::intValue)
        if (current == 0) return
        val a = state.assignment.intValue(relation.dividend)
        val b = relation.divisor?.let { bigIntOf(state.assignment.intValue(it)) }
            ?: checkNotNull(relation.constantDivisor)
        val outputs = relation.outputs(bigIntOf(a), b)
        val changes = linkedMapOf<Int, Long>()
        fun candidate(variable: Int, value: BigInt) {
            if (!value.fitsLong()) return
            val target = value.toLongExact()
            if (target !in state.rootDomains[variable] || target == state.assignment.intValue(variable)) return
            if (grade(state) { if (it == variable) target else state.assignment.intValue(it) } <= current) {
                sink.addChannelingIntSet(state, variable, target)
            }
        }
        if (outputs != null) {
            candidate(relation.quotient, outputs.first)
            candidate(relation.remainder, outputs.second)
            if (outputs.first.fitsLong() && outputs.second.fitsLong() &&
                (relation.quotient != relation.remainder || outputs.first == outputs.second)) {
                changes[relation.quotient] = outputs.first.toLongExact()
                changes[relation.remainder] = outputs.second.toLongExact()
                if (changes.all { (v, value) -> value in state.rootDomains[v] } &&
                    grade(state) { changes[it] ?: state.assignment.intValue(it) } <= current) {
                    val parts = changes.filter { (v, value) -> value != state.assignment.intValue(v) }
                        .map { (v, value) -> Move.IntSet(v, value) }
                    if (parts.isNotEmpty()) sink.addChannelingCompound(state, parts)
                }
            }
        }
        candidate(relation.dividend, b * bigIntOf(state.assignment.intValue(relation.quotient)) +
            bigIntOf(state.assignment.intValue(relation.remainder)))
        relation.divisor?.let { variable ->
            for (value in listOf(-1L, 1L)) candidate(variable, bigIntOf(value))
        }
    }
}
