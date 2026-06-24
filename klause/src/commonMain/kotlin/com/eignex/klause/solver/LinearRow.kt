package com.eignex.klause.solver

import com.eignex.klause.solver.factor.arithmetic.LinearOp

/**
 * One linear constraint `Σ coeffs(k) · vars(k) ⟨op⟩ bound` over integer variables (raw ids), as
 * exposed by [Factor.linearRows]. A read-only structural view for presolve: it lets a pass reason
 * over the linear content of *any* factor uniformly instead of pattern-matching the concrete
 * [com.eignex.klause.solver.factor.arithmetic.Linear] type.
 *
 * Distinct from a [Linearizer] relaxation row: a [LinearRow] is **solution-set exact** for its
 * originating factor (see [Factor.linearRows]); a relaxation row may be a superset.
 */
class LinearRow(
    /** Coefficient of each term, parallel to [vars]. */
    val coeffs: IntArray,
    /** Integer variable id of each term, parallel to [coeffs]. */
    val vars: IntArray,
    /** Relational operator comparing `Σ coeffs·vars` against [bound]. */
    val op: LinearOp,
    /** Right-hand side the weighted sum is compared against. */
    val bound: Long,
)
