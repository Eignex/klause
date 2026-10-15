package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.ceilDiv
import com.eignex.klause.factor.arithmetic.internals.floorDiv
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal class AffineAbsInvariant(private val factor: AffineAbs) : Invariant {
    private val branches = listOf(
        factor.result.comparedTo(factor.operand, BIG_ONE, LinearOp.EQ),
        factor.result.comparedTo(factor.operand, -BIG_ONE, LinearOp.EQ),
    )

    private fun residual(state: LocalSearchState, changed: Int = -1, value: Long = 0L): BigInt {
        val at: (Int) -> Long = { variable ->
            if (variable == changed) value else state.assignment.intValue(variable)
        }
        return (factor.operand.value(at).abs() - factor.result.value(at)).abs()
    }

    private fun score(state: LocalSearchState, changed: Int = -1, value: Long = 0L): Int {
        val residual = residual(state, changed, value)
        return compressViolation(if (residual.fitsLong()) residual.toLongExact() else Long.MAX_VALUE,
            state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = residual(state) != BIG_ZERO

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, intVar, newValue) - score(state)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state) - score(state, intVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val before = score(state)
        for (branch in branches) {
            val constants = checkNotNull(branch.integralConstants)
            var activity = BIG_ZERO
            for (index in branch.vars.indices) {
                activity += constants.exactCoeff(index) * bigIntOf(state.assignment.intValue(branch.vars[index]))
            }
            for (index in branch.vars.indices) {
                val variable = branch.vars[index]
                val coefficient = constants.exactCoeff(index)
                val numerator = constants.exactBound - activity +
                    coefficient * bigIntOf(state.assignment.intValue(variable))
                for (target in listOf(floorDiv(numerator, coefficient), ceilDiv(numerator, coefficient)).distinct()) {
                    if (!target.fitsLong()) continue
                    val value = state.rootDomains[variable].clamp(target.toLongExact())
                    if (value != state.assignment.intValue(variable) && score(state, variable, value) <= before) {
                        sink.addChannelingIntSet(state, variable, value)
                    }
                }
            }
        }
    }
}
