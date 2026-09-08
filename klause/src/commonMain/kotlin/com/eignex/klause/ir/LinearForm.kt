package com.eignex.klause.ir

/** A factor's linear declaration, independent of its finite or open-domain execution. */
sealed class LinearForm {
    /** Comparisons in declaration order. */
    abstract val rows: List<LinearRow>

    /** A conjunction equivalent to the whole factor. */
    class Conjunction(override val rows: List<LinearRow>) : LinearForm()

    /** A disjunction equivalent to the whole factor; its rows are not individually implied. */
    class Disjunction(override val rows: List<LinearRow>) : LinearForm()

    /** Rows implied by the factor whose conjunction may admit extra assignments. */
    class Relaxation(override val rows: List<LinearRow>) : LinearForm()
}

/** Rows in declaration order, including alternatives in a [LinearForm.Disjunction]. */
val Factor.linearRows: List<LinearRow> get() = linearForm?.rows.orEmpty()

/** Rows individually implied by the factor, safe for presolve deductions and LP relaxation. */
val Factor.impliedLinearRows: List<LinearRow>
    get() = when (val form = linearForm) {
        is LinearForm.Conjunction -> form.rows
        is LinearForm.Relaxation -> form.rows
        is LinearForm.Disjunction, null -> emptyList()
    }
