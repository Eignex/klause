package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction

internal class SourceIntegerAggregateSplit(
    val below: SourceInequality,
    val whenBelow: SourceDerivation,
    val whenAbove: SourceDerivation,
) : SourceDerivation.Extension

internal class SourceIntegerRowAssumption(val row: SourceInequality) : SourceDerivation.Extension

internal fun SourceInequality.adjacentIntegerAbove(): SourceInequality =
    SourceInequality(coefficients.mapValues { it.value.negated() }, (rhs + BigFraction.ONE).negated())
