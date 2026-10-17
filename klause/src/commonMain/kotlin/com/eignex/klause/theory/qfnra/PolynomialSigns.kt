package com.eignex.klause.theory.qfnra

import com.eignex.klause.ir.PolynomialRelation

internal object PolynomialSigns {
    fun relationSigns(relation: PolynomialRelation): Int = when (relation) {
        PolynomialRelation.EQ -> ZERO_SIGN
        PolynomialRelation.NE -> NONZERO_SIGNS
        PolynomialRelation.LT -> NEGATIVE_SIGN
        PolynomialRelation.LE -> NEGATIVE_SIGN or ZERO_SIGN
        PolynomialRelation.GT -> POSITIVE_SIGN
        PolynomialRelation.GE -> POSITIVE_SIGN or ZERO_SIGN
    }

    fun negateSigns(signs: Int): Int = (signs and ZERO_SIGN) or
        (if (signs and NEGATIVE_SIGN == 0) 0 else POSITIVE_SIGN) or
        (if (signs and POSITIVE_SIGN == 0) 0 else NEGATIVE_SIGN)

    fun multiplySigns(left: Int, right: Int): Int =
        (if ((left or right) and ZERO_SIGN == 0) 0 else ZERO_SIGN) or
            (if ((left and NEGATIVE_SIGN != 0 && right and POSITIVE_SIGN != 0) ||
                (left and POSITIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0)
            ) NEGATIVE_SIGN else 0) or
            (if ((left and NEGATIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0) ||
                (left and POSITIVE_SIGN != 0 && right and POSITIVE_SIGN != 0)
            ) POSITIVE_SIGN else 0)

    fun addSigns(left: Int, right: Int): Int {
        if (left == ZERO_SIGN) return right
        if (right == ZERO_SIGN) return left
        if ((left and NEGATIVE_SIGN != 0 && right and POSITIVE_SIGN != 0) ||
            (left and POSITIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0)
        ) return ALL_SIGNS
        return left or right
    }

    const val NEGATIVE_SIGN = 1
    const val ZERO_SIGN = 2
    const val POSITIVE_SIGN = 4
    const val NONZERO_SIGNS = 5
    const val ALL_SIGNS = 7
}
