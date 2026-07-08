package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.IntDomain

/**
 * The sink a factor's [com.eignex.klause.solver.Factor.linearize] emits its LP relaxation into. A factor
 * states linear constraints over the problem's integer variables by raw id; the driver behind this
 * interface maps each variable to its LP column, caps the model, and tracks row provenance — a factor
 * never touches the underlying tableau.
 */
interface RelaxationBuilder {
    /**
     * Emit `Σ coeffs(k) · intVars(k) ⟨op⟩ bound` over integer variables (raw ids), tagged
     * [Contribution.CORE] or [Contribution.HULL] by [contribution] (default [Contribution.CORE]).
     * [LinearOp.NE] is not linear-relaxable and is ignored.
     */
    fun linearRow(
        op: LinearOp,
        intVars: IntArray,
        coeffs: LongArray,
        bound: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /**
     * Emit `Σ weights(k) · literal(k) ⟨op⟩ bound` over Boolean literals (a literal is a variable id
     * or its negation). A positive literal counts `+w · x`, a negative literal `+w · (1 − x)`; the
     * constants are folded into the right-hand side. [weights] of `null` means unit weights
     * (cardinality). Tagged by [contribution] (default [Contribution.CORE]). [LinearOp.NE] is ignored.
     */
    fun boolRow(
        literals: IntArray,
        weights: IntArray?,
        op: LinearOp,
        bound: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /** The LP column handle for integer variable [intVar] (raw id), created on first reference. */
    fun intColumn(intVar: Int): Int

    /** The LP column handle for Boolean variable [boolVar] (raw id), created on first reference. */
    fun boolColumn(boolVar: Int): Int

    /**
     * An auxiliary LP column in `[lo, hi]` with no backing CP variable (e.g. a one-hot selector or a
     * product column). [presence], when given, is a flat list of `(intVar, value)` pairs that must all
     * hold for the column to be present; it lets the persistent relaxation re-bind the column across
     * nodes (its upper bound drops to 0 when any required value leaves the live domain). `null` keeps
     * the column off the persistent path. Values are `Long` so a column keyed on a domain value beyond
     * Int range (a float-scaled bucket) re-binds against the true value rather than a truncated one.
     */
    fun auxColumn(lo: Long, hi: Long, presence: LongArray? = null): Int

    /**
     * Whether this factor's HULL contribution is enabled this build — its convex-hull family flag is
     * on and we are not building the minimal objective-cone relaxation. A factor guards its hull
     * section (the selector/arc columns it allocates and the rows over them) with this, so a disabled
     * family adds neither columns nor rows; only its CORE rows remain.
     */
    fun hullEnabled(): Boolean

    /** The live (node-current) domain of integer variable [intVar]. */
    fun liveDomain(intVar: Int): IntDomain

    /** The declared (root) domain of integer variable [intVar]. */
    fun declaredDomain(intVar: Int): IntDomain

    /**
     * Emit `Σ coeffs(k) · columns(k) ⟨op⟩ rhs` over column handles obtained from [intColumn],
     * [boolColumn], or [auxColumn]. Repeated columns are summed and absent columns count as zero.
     * Tagged by [contribution] (default [Contribution.CORE]). [LinearOp.NE] is ignored.
     */
    fun row(
        columns: IntArray,
        coeffs: LongArray,
        op: LinearOp,
        rhs: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /**
     * Emit a big-M row `Σ coeffs(k) · columns(k) ⟨op⟩ rhs` whose constants bake in the live
     * (node-current) bounds. [global] must be true exactly when the same big-M follows from the
     * declared bounds — then the row holds at every solution and is cacheable. Otherwise it holds only
     * inside the node's box, and the engine records the live bounds the big-M leaned on as the row's
     * conflict-certificate premises: for each linearized integer column, its live upper bound when
     * [maxSide] matches the coefficient's sign (positive on the `lMax` side), else its live lower bound
     * — auxiliary indicator columns are ignored. A feasibility-defining CORE row. [LinearOp.NE] is
     * ignored.
     */
    fun bigMRow(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean)
}
