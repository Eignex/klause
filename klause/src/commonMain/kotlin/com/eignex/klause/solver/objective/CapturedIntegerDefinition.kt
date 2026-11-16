package com.eignex.klause.solver.objective

import com.eignex.klause.ir.DefinitionHint
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

internal class CapturedIntegerDefinition(
    private val hint: DefinitionHint.Integer,
    private val inputs: Array<FunctionalObjective.Operand>,
    private val outputSlot: Int,
) : FunctionalObjective.Node {
    override val out: Int get() = hint.output

    override fun compute(valOf: (Int) -> Long): Long {
        val value = when (val source = hint) {
            is DefinitionHint.Abs -> return inputs[0].value(valOf).let {
                if (it == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it)
            }
            is DefinitionHint.Product -> bigIntOf(inputs[0].value(valOf)) * bigIntOf(inputs[1].value(valOf))
            is DefinitionHint.Plus -> bigIntOf(inputs[0].value(valOf)) + bigIntOf(inputs[1].value(valOf))
            is DefinitionHint.Linear -> {
                var numerator = bigIntOf(source.bound)
                for (k in inputs.indices) {
                    if (k == outputSlot) continue
                    numerator -= bigIntOf(source.coefficients[k]) * bigIntOf(inputs[k].value(valOf))
                }
                numerator / bigIntOf(source.coefficients[outputSlot])
            }
            else -> error("a wide integer definition is required")
        }
        return value.saturatedDefinitionValue()
    }
}

// Outside the signed-word output range, the source factor remains violated after domain clamping.
private fun BigInt.saturatedDefinitionValue(): Long = when {
    fitsLong() -> toLongExact()
    this < BIG_ZERO -> Long.MIN_VALUE
    else -> Long.MAX_VALUE
}
