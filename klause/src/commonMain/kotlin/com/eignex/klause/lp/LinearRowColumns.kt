package com.eignex.klause.lp

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.util.BigInt

internal val LinearRow.intVars: IntArray
    get() = (0 until size).filter { Term.isInt(ref(it)) }.map { Term.intVar(ref(it)) }.toIntArray()

internal val LinearRow.integerCoeffs: LongArray? get() = (constants as? IntegerConstants)?.coeffs
internal val LinearRow.integerBound: Long? get() = (constants as? IntegerConstants)?.bound
internal val LinearRow.wideCoefficients: Array<BigInt>
    get() = (constants as WideConstants).coefficients.toTypedArray()
internal val LinearRow.wideBound: BigInt get() = (constants as WideConstants).bound
