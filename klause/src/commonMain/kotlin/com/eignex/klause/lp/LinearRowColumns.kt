package com.eignex.klause.lp

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.ionspin.kotlin.bignum.integer.BigInteger

internal val LinearRow.intVars: IntArray
    get() = (0 until size).filter { Term.isInt(ref(it)) }.map { Term.intVar(ref(it)) }.toIntArray()

internal val LinearRow.integerCoeffs: LongArray? get() = (constants as? IntegerConstants)?.coeffs
internal val LinearRow.integerBound: Long? get() = (constants as? IntegerConstants)?.bound
internal val LinearRow.wideCoefficients: Array<BigInteger>
    get() = (constants as WideConstants).coefficients.toTypedArray()
internal val LinearRow.wideBound: BigInteger get() = (constants as WideConstants).bound
