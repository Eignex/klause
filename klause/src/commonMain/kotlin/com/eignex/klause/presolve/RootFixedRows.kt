package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.complemented
import com.eignex.klause.ir.impliedLinearRows

/** Rows whose activators are fixed by root unit clauses. */
internal fun rootFixedReifiedRows(factors: List<Factor>): List<Linear> =
    presolveLinearRows(factors, activatedOnly = true)

/**
 * Specialize implied rows for presolve's linear arithmetic kernels, independently of finite domains.
 * Boolean terms and activators are substituted only when root facts determine them. Disjunctive rows
 * are not individually implied; relaxation rows may prove bounds but do not authorize factor removal.
 */
internal fun presolveLinearRows(factors: List<Factor>, activatedOnly: Boolean = false): List<Linear> {
    val fixed = HashMap<Int, Boolean>()
    val conflicting = HashSet<Int>()
    for (factor in factors) {
        if (factor !is Clause || factor.literals.size != 1) continue
        val literal = factor.literals[0]
        val variable = Lit.variable(literal)
        val truth = Lit.isPositive(literal)
        if (fixed.put(variable, truth)?.let { it != truth } == true) conflicting.add(variable)
    }
    for (variable in conflicting) fixed.remove(variable)
    return buildList {
        for (factor in factors) {
            for (row in factor.impliedLinearRows) {
                if (activatedOnly && row.activator == LinearRow.ALWAYS) continue
                val truth = if (row.activator == LinearRow.ALWAYS) true else fixed[row.activator] ?: continue
                if ((if (truth) row.relation else row.relation.complemented()) == LinearOp.NE) continue
                row.specializeLinear(truth, fixed)?.let(::add)
            }
        }
    }
}
