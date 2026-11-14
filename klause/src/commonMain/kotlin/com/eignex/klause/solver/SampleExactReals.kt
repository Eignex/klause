package com.eignex.klause.solver

import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.toExactRealValue
import com.eignex.klause.simplex.exact.BigFraction

/** Authoritative real coordinate, widening a finite binary64 value only when no exact witness exists. */
fun Sample.exactRealValue(id: Int): ExactRealValue = symbolicReals?.get(id)
    ?: algebraicReals?.get(id)?.toExactRealValue()
    ?: (
        exactReals?.get(id) ?: requireNotNull(BigFraction.ofDouble(approximateRealValue(id))) {
        "real coordinate $id has no exact value"
    }
    ).toExactRealValue()
