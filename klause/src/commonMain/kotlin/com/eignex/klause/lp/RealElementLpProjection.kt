package com.eignex.klause.lp

import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.LinearOp

internal fun RealElement.emitLpRelaxation(builder: RelaxationBuilder) {
    builder.linearRow(LinearOp.GE, intArrayOf(idx), longArrayOf(1), indexOffset.toLong())
    builder.linearRow(LinearOp.LE, intArrayOf(idx), longArrayOf(1), indexOffset.toLong() + size - 1)
    // A finite box invented for an open source index cannot select its real equality.
    if (!builder.statesBothBounds(idx)) return
    val domain = builder.liveDomain(idx)
    if (domain.min != domain.max) return
    val position = position(domain.min) ?: return
    val output = builder.realColumn(result)
    if (output < 0) return
    if (arrIsVars) {
        val selected = builder.realColumn(entries[position])
        if (selected < 0) return
        builder.realRowForIntValue(idx, domain.min, intArrayOf(output, selected), doubleArrayOf(1.0, -1.0),
            LinearOp.EQ, 0.0)
    } else {
        builder.realRowForIntValue(idx, domain.min, intArrayOf(output), doubleArrayOf(1.0),
            LinearOp.EQ, constants[position])
    }
}
