package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.WideLinComb
import com.eignex.util.numbers.BigInt

internal data class SmtDivModKey(val dividend: WideLinComb, val divisor: BigInt)
