package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.lowering.divModLinearFormula
import com.eignex.klause.lowering.divModPolynomialFormula

internal fun divModEncoder(factor: DivMod, target: EncodingTarget): Encoder = when (target) {
    EncodingTarget.LINEAR -> Encoder(
        if (factor.constantDivisor != null) EncoderSupport.EQUIVALENT else EncoderSupport.CONDITIONED,
        factor.divisor?.let { listOf(SourceIntId(it)) }.orEmpty(),
    ) { view, encoding ->
        view as DivMod
        val divisor = view.constantDivisor ?: encoding.fixedInteger(SourceIntId(checkNotNull(view.divisor)))
            ?: return@Encoder EncoderDecline.CONDITIONS
        encoding.linearFormula(divModLinearFormula(view, divisor))
        null
    }
    EncodingTarget.POLYNOMIAL -> Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        encoding.polynomial(divModPolynomialFormula(view as DivMod) { encoding.integer(SourceIntId(it)) })
        null
    }
    else -> Encoder(EncoderSupport.UNSUPPORTED)
}
