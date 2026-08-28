package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor

/** Whether this factor contributes CORE rows to the LP objective cone. */
internal fun Factor.extendsLpObjectiveCone(): Boolean = when (this) {
    is ArrayMinMax, is Cardinality, is Clause, is PseudoBoolean -> true
    is Linear -> integerConstants != null
    else -> false
}
