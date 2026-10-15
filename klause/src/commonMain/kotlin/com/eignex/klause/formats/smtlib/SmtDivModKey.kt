package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.WideLinComb
import com.eignex.klause.util.BigInt

internal data class SmtDivModKey(val dividend: WideLinComb, val divisor: BigInt)
