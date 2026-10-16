package com.eignex.klause.solver.objective

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

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
