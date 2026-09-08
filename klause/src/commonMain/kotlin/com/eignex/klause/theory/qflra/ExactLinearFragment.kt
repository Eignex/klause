package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows

/** Whether the exact pure-real lane decides every factor in this source model. */
fun Problem.supportsExactLra(): Boolean = numIntVars == 0 && numRealVars != 0 && factors.all { it.exactTheoryOwnable }

/** Whether the exact integer-containing linear lane decides every factor in this source model. */
internal fun Problem.supportsExactLira(): Boolean = numIntVars != 0 && factors.all { it.exactTheoryOwnable }

internal val Factor.exactTheoryOwnable: Boolean
    get() = (linearForm is LinearForm.Conjunction || linearForm is LinearForm.Disjunction) && linearRows.all { row ->
        when (val constants = row.constants) {
            is IntegralConstants -> true

            is RealConstants -> constants.bound.isFinite() &&
                constants.intCoefficients.indices.all {
                    val coefficient = constants.intCoefficients.at(it)
                    coefficient.isFinite()
                } && constants.realCoefficients.indices.all { constants.realCoefficients.at(it).isFinite() }
        }
    }
