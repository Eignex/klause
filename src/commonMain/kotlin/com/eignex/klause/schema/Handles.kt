package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolTerm
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.not

/** Runtime handle for a Boolean variable. Coerces to a [BoolRef] when used in expressions. */
class BoolHandle(val name: String) : BoolTerm {
    override fun toExpr(): BoolExpr = BoolRef(name, negated = false)
}

/** Runtime handle for a nominal (enum-of-labels) variable. Use `eq`/`ne` to make literals. */
class NominalHandle(val name: String, val labels: List<String>) {
    infix fun eq(label: String): BoolExpr {
        require(label in labels) { "Label '$label' not in nominal '$name' (have $labels)" }
        return NominalEq(name, label)
    }

    infix fun ne(label: String): BoolExpr = !eq(label)
}
