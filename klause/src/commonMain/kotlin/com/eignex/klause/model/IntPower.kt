package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Fixed nonnegative integer power, with `0^0 = 1`. */
@Serializable
@SerialName("intpower")
data class IntPower(
    /** Integer operand. */
    val base: IntExpr,
    /** Fixed nonnegative integer exponent. */
    val exponent: Int,
) : IntExpr {
    init { require(exponent >= 0) { "Power requires a nonnegative integer exponent" } }
}
