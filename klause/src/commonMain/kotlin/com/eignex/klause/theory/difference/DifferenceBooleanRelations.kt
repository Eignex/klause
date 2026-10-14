package com.eignex.klause.theory.difference

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal fun Problem.checkDifferenceBooleans(bools: BooleanArray, cancellation: Cancellation): Boolean? {
    val witness = object : ExactWitness {
        override fun truth(boolVar: Int): Boolean = bools[boolVar]
        override fun at(column: Int): BigFraction = error("a Boolean relation has no numeric columns")
    }
    for (factor in factors) {
        if (cancellation()) return null
        if (factor.intVars.isNotEmpty()) continue
        val form = factor.linearForm ?: return null
        fun holds(row: LinearRow): Boolean = row.exactComparison(0,
            row.activator == LinearRow.ALWAYS || bools[row.activator], witness::truth).holdsAt(witness)
        val satisfied = when (form) {
            is LinearForm.Conjunction -> form.rows.all(::holds)
            is LinearForm.Disjunction -> form.rows.any(::holds)
            is LinearForm.Relaxation -> return null
        }
        if (!satisfied) return false
    }
    return true.takeUnless { cancellation() }
}
