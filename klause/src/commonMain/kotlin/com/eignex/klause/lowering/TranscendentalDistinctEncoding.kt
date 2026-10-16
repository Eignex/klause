package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.TranscendentalDistinct
import com.eignex.klause.ir.TranscendentalFormula

internal fun transcendentalDistinctEncoding(
    distinct: TranscendentalDistinct,
    checkpoint: () -> Unit,
): TranscendentalFormula {
    checkpoint()
    val operands = distinct.operands
    val comparisons = ArrayList<TranscendentalFormula>()
    for (first in operands.indices) {
        for (second in first + 1 until operands.size) {
            checkpoint()
            comparisons += TranscendentalFormula.Atom(operands[first], PolynomialRelation.NE, operands[second])
        }
    }
    checkpoint()
    return TranscendentalFormula.And(comparisons)
}
