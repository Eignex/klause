package com.eignex.klause.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lp.engine.LpColumnId
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.propagation.CpIntId
import com.eignex.util.arrays.emptyIntArray

/** An auxiliary source extension whose semantic role survives live-domain filtering. */
class LpAuxiliaryColumn(
    role: List<Long>,
    /** Upper bound while every required membership remains possible. */
    val presentUpper: Long,
    /** Every source solution admits an integral extension satisfying this producer's rows. */
    val integralExtension: Boolean,
) {
    private val roleSnapshot = role.toList()

    /** Producer-local semantic key, independent of filtered emission order. */
    val role: List<Long> get() = roleSnapshot.toList()
}

/**
 * The sink an LP factor projection emits its relaxation into. The driver maps variables to LP columns, caps
 * the model and tracks row provenance.
 */
interface RelaxationBuilder {
    /** Emit `Σ coeffs(k) · intVars(k) ⟨op⟩ bound` over raw integer ids. [LinearOp.NE] is ignored. */
    fun linearRow(
        op: LinearOp,
        intVars: IntArray,
        coeffs: LongArray,
        bound: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /**
     * Emit `Σ weights(k) · literal(k) ⟨op⟩ bound`; a negative literal counts `w · (1 − x)`. Null [weights]
     * means unit weights. [LinearOp.NE] is ignored.
     */
    fun boolRow(
        literals: IntArray,
        weights: LongArray?,
        op: LinearOp,
        bound: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /** The LP column handle for integer variable [intVar] (raw id), created on first reference. */
    fun intColumn(intVar: Int): Int

    /** The LP column handle for Boolean variable [boolVar] (raw id), created on first reference. */
    fun boolColumn(boolVar: Int): Int

    /**
     * The LP column handle for LP-only continuous variable [realVar], created on first reference with its
     * declared bounds and objective coefficient, or `-1` for a builder with no real-variable backing.
     */
    fun realColumn(realVar: Int): Int = -1

    /** Largest magnitude admitted by a real coordinate's declaration and feasibility band, or infinity. */
    fun realMagnitude(realVar: Int): Double = Double.POSITIVE_INFINITY

    /** Emit a derived real row whose numerical deductions carry the combined premise displacement [slack]. */
    fun derivedRealRow(columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double, slack: LpRowSlack) {
        realRow(columns, coeffs, op, rhs)
    }

    /** Emit a derived row carrying both its combined displacement and Boolean validity premises. */
    fun derivedRealRow(
        columns: IntArray, coeffs: DoubleArray, op: LinearOp, rhs: Double, slack: LpRowSlack, premiseLits: IntArray,
    ) {
        realRow(columns, coeffs, op, rhs, premiseLits = premiseLits)
    }

    /**
     * Emit a real-coefficient row `Σ coeffs(k) · columns(k) ⟨op⟩ rhs` over column handles. Repeated columns
     * are summed. [LinearOp.NE] is ignored. The row forces the model onto the double-precision view.
     */
    fun realRow(
        columns: IntArray,
        coeffs: DoubleArray,
        op: LinearOp,
        rhs: Double,
        strict: Boolean = false,
        premiseLits: IntArray = emptyIntArray(),
    ) {}

    /**
     * Emit a real row valid when [intVar] equals [value], recording both integer bound premises.
     * A builder without premise-aware real emission contributes no row.
     */
    fun realRowForIntValue(
        intVar: Int,
        value: Long,
        columns: IntArray,
        coeffs: DoubleArray,
        op: LinearOp,
        rhs: Double,
    ) {}

    /** Emit a derived row valid at [intVar] = [value], retaining its displacement and both bound premises. */
    fun derivedRealRowForIntValue(
        intVar: Int,
        value: Long,
        columns: IntArray,
        coeffs: DoubleArray,
        op: LinearOp,
        rhs: Double,
        slack: LpRowSlack,
    ) {
        realRowForIntValue(intVar, value, columns, coeffs, op, rhs)
    }

    /**
     * The live pin of Boolean variable [boolVar], or null when unpinned or the builder has no Boolean
     * backing.
     */
    fun liveBool(boolVar: Int): Boolean? = null

    /**
     * An auxiliary LP column in `[lo, hi]` with no backing CP variable. [presence] is a flat list of
     * `(intVar, value)` pairs that must all hold for the column to be present, letting the persistent
     * relaxation re-bind it across nodes (its upper bound drops to 0 otherwise); `null` keeps the column off
     * the persistent path.
     */
    fun auxColumn(lo: Long, hi: Long, presence: LongArray? = null, definition: LpAuxiliaryColumn? = null): Int

    /**
     * Whether this factor's HULL contribution is enabled this build. A disabled family must add neither
     * columns nor rows.
     */
    fun hullEnabled(): Boolean

    /** The live (node-current) domain of integer variable [intVar]. */
    fun liveDomain(intVar: Int): IntDomain

    /**
     * The root box of integer variable [intVar]. A side the source left open was closed by an invented
     * endpoint, so a projection that enumerates the box or bakes an endpoint must first ask
     * [statesLowerBound] / [statesUpperBound].
     */
    fun rootDomain(intVar: Int): IntDomain

    /**
     * Whether the model itself bounds integer variable [intVar] below, by declaration or proof. A row
     * leaning on an invented endpoint would cut off feasible points, since the column enters the root LP
     * genuinely open.
     */
    fun statesLowerBound(intVar: Int): Boolean

    /** Whether the model itself bounds integer variable [intVar] above; see [statesLowerBound]. */
    fun statesUpperBound(intVar: Int): Boolean

    /** Whether both of [intVar]'s root endpoints are the model's own; see [statesLowerBound]. */
    fun statesBothBounds(intVar: Int): Boolean = statesLowerBound(intVar) && statesUpperBound(intVar)

    /** Whether every member of [intVars] states both of its endpoints; see [statesBothBounds]. */
    fun statesBothBounds(intVars: IntArray): Boolean = intVars.all { statesBothBounds(it) }

    /**
     * Emit `Σ coeffs(k) · columns(k) ⟨op⟩ rhs` over column handles. Repeated columns are summed.
     * [LinearOp.NE] is ignored.
     */
    fun row(
        columns: IntArray,
        coeffs: LongArray,
        op: LinearOp,
        rhs: Long,
        contribution: Contribution = Contribution.CORE,
    )

    /**
     * Emit an exact integer-coefficient row under the current equality pin of [integer] to [value].
     * The receiver retains both sides of that source condition as certificate premises. A receiver
     * without conditional-row support omits the row, weakening its relaxation.
     */
    fun conditionedRow(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, integer: Int, value: Long) {}

    /**
     * Emit a CORE big-M row whose constants bake in the live bounds. [global] must be true exactly when the
     * same big-M follows from the declared bounds, which makes the row cacheable. Otherwise the live bounds
     * the big-M leaned on become certificate premises: the upper bound of each integer column whose
     * coefficient sign matches [maxSide], else the lower bound. [LinearOp.NE] is ignored.
     */
    fun bigMRow(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean)
}

/** LP handle for a projection-local finite integer, or null when this builder declines it. */
fun RelaxationBuilder.intColumn(variable: CpIntId): LpColumnId? =
    intColumn(variable.index).takeIf { it >= 0 }?.let(::LpColumnId)

/** LP handle for a Boolean in the shared source namespace, or null when absent. */
fun RelaxationBuilder.boolColumn(variable: SourceBoolId): LpColumnId? =
    boolColumn(variable.index).takeIf { it >= 0 }?.let(::LpColumnId)

/** LP handle for a source continuous variable, or null when this builder declines it. */
fun RelaxationBuilder.realColumn(variable: SourceRealId): LpColumnId? =
    realColumn(variable.index).takeIf { it >= 0 }?.let(::LpColumnId)
