package com.eignex.klause.schema

import com.eignex.klause.model.ContinuousRealExpr
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntPower
import com.eignex.klause.model.IntTerm

/** Exact integer power with a fixed nonnegative exponent. */
fun IntTerm.pow(exponent: Int): IntExpr = IntPower(toIntExpr(), exponent)

/** Exact continuous power with a fixed nonnegative integer exponent. */
fun FloatTerm.pow(exponent: Int): ContinuousRealExpr = toContinuousRealExpr().pow(exponent)

/** Exact continuous power of an existing expression. */
fun ContinuousRealExpr.pow(exponent: Int): ContinuousRealExpr = ContinuousRealExpr.Power(this, exponent)
