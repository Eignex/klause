package com.eignex.klause.theory

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.lowering.allDifferentLinearFormula
import com.eignex.klause.lowering.allDifferentPolynomialFormula

internal object AllDifferentEncoder {
    val linear = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        val formula = allDifferentLinearFormula(factor as AllDifferent, encoding.cancellation)
            ?: return@Encoder EncoderDecline.RESOURCE
        encoding.linearFormula(formula)
        null
    }

    val polynomial = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        val formula = allDifferentPolynomialFormula(factor as AllDifferent,
            { encoding.integer(SourceIntId(it)) }, encoding.cancellation)
            ?: return@Encoder EncoderDecline.RESOURCE
        encoding.polynomial(formula)
        null
    }
}
