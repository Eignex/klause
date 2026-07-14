package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray

/**
 * One linear constraint over the problem's variables, as exposed by [Factor.linearRows]:
 *
 * ```
 *   Σ coeffs(k) · vars(k)  +  Σ boolCoeffs(k) · value(boolLits(k))  ⟨op⟩  bound
 * ```
 *
 * where the first sum is over integer variables (raw ids) and the second is over Boolean literals — a
 * positive literal contributes `+boolCoeffs·x`, a negative one `+boolCoeffs·(1 − x)`, matching
 * [RelaxationBuilder.boolRow]. A pure integer-linear factor leaves [boolLits] empty; a pure Boolean one
 * (clause, cardinality, pseudo-Boolean) leaves [vars] empty.
 *
 * A read-only structural view for presolve: it lets a pass reason over the linear content of *any*
 * factor uniformly instead of pattern-matching the concrete factor type.
 *
 * Distinct from a [Factor.linearize] relaxation row: a [LinearRow] is **solution-set exact** for its
 * originating factor (see [Factor.linearRows]); a relaxation row may be a superset.
 */
class LinearRow(
    /** Coefficient of each integer term, parallel to [vars]. */
    val coeffs: LongArray,
    /** Integer variable id of each term, parallel to [coeffs]. */
    val vars: IntArray,
    /** Relational operator comparing the weighted sum against [bound]. */
    val op: LinearOp,
    /** Right-hand side the weighted sum is compared against. */
    val bound: Long,
    /** Coefficient of each Boolean term, parallel to [boolLits]; empty for a pure integer-linear row. */
    val boolCoeffs: LongArray = EmptyLongArray,
    /** Boolean literal (Lit-encoded) of each Boolean term, parallel to [boolCoeffs]. A positive literal
     *  counts `+coeff · x`, a negative one `+coeff · (1 − x)`. */
    val boolLits: IntArray = EmptyIntArray,
)
