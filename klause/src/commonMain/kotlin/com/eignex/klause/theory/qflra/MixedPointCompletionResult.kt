package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction

internal sealed interface MixedPointCompletionResult {
    class Point(val values: List<BigFraction>) : MixedPointCompletionResult
    data object Refuted : MixedPointCompletionResult
    data object Declined : MixedPointCompletionResult
}
