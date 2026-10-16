package com.eignex.klause.theory

import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.lowering.distinctLinearFormula
import com.eignex.klause.lowering.distinctPolynomialFormula

internal object DistinctEncoders {
    val linear = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        val formula = distinctLinearFormula(factor as Distinct, encoding.cancellation)
            ?: return@Encoder EncoderDecline.RESOURCE
        encoding.linearFormula(formula)
        null
    }

    val polynomial = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        val formula = distinctPolynomialFormula(factor as Distinct, encoding::integer, encoding::real,
            encoding::conditional, encoding.cancellation) ?: return@Encoder EncoderDecline.RESOURCE
        encoding.polynomial(formula)
        null
    }
}
