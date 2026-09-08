package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.linearRows

internal val Factor.integerTheoryOwnable: Boolean
    get() = (linearForm is LinearForm.Conjunction || linearForm is LinearForm.Disjunction) && intVars.isNotEmpty() &&
        linearRows.all { it.constants is IntegralConstants }
