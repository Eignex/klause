package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.lowering.wideDivModRows
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class WideDivModInvariant(private val relation: WideDivMod) : Invariant {
    private val identity = wideDivModRows(relation).first()

    private fun grade(state: LocalSearchState, value: (Int) -> Long): Int {
        val expected = integerDivMod(relation.dividend.evaluate(value), relation.divisor, DivisionConvention.EUCLIDEAN)
            ?: return compressViolation(Long.MAX_VALUE, state.violationSoftCap)
        val error = (expected.first - relation.quotient.evaluate(value)).abs() +
            (expected.second - relation.remainder.evaluate(value)).abs()
        return compressViolation(if (error > bigIntOf(Long.MAX_VALUE)) Long.MAX_VALUE else error.toLongExact(),
            state.violationSoftCap)
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
        val constants = checkNotNull(identity.integralConstants)
        var residual = BIG_ZERO - constants.exactBound
        for (i in identity.vars.indices) residual += constants.exactCoeff(i) *
            bigIntOf(state.assignment.intValue(identity.vars[i]))
        for (i in 0 until minOf(identity.vars.size, MAX_REPAIRS)) {
            val coefficient = constants.exactCoeff(i)
            if (coefficient.isZero()) continue
            val target = bigIntOf(state.assignment.intValue(identity.vars[i])) - residual / coefficient
            if (!target.fitsLong()) continue
            val variable = identity.vars[i]
            val value = target.toLongExact()
            if (value in state.rootDomains[variable] && value != state.assignment.intValue(variable) &&
                grade(state) { if (it == variable) value else state.assignment.intValue(it) } <= current) {
                sink.addChannelingIntSet(state, variable, value)
            }
        }
    }

    private companion object {
        const val MAX_REPAIRS = 16
    }
}
