package com.eignex.klause.cli

import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.formats.smtlib.smtSymbolicTerm

internal fun smtSymbolicValue(value: ExactRealValue): String = smtSymbolicTerm(value)
