package com.eignex.klause.presolve

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain

/**
 * How wide each integer column is, for a reduction whose argument charges a column its whole range.
 *
 * An activity argument — a knapsack lift, a maximal-activity domination, a disjointness test — needs both
 * of a column's sides. A column the model leaves open has no width to charge, so [isClosed] answers false
 * for it and [min] / [max] say nothing. A caller reads every column its row mentions and declines the row
 * when any of them is open, which is what lets a row over closed columns reduce on a model that is open
 * somewhere else. Reading the model as a whole instead — open anywhere, so charge nothing — is what kept
 * these reductions off the source lane entirely.
 *
 * Deliberately primitive: a column's range is read once per term per row, and [IntDomain] is a real object
 * with several representations, so handing one back per access would allocate down the hot path.
 */
internal interface ColumnRanges {

    /** Whether column [v] is bounded on both sides, so [min] and [max] give it a width. */
    fun isClosed(v: Int): Boolean

    /** Lower end of column [v]. Defined only where [isClosed]. */
    fun min(v: Int): Long

    /** Upper end of column [v]. Defined only where [isClosed]. */
    fun max(v: Int): Long

    /** Whether every column in [vars] has a width, so a row over them may be charged. */
    fun allClosed(vars: IntArray): Boolean {
        for (v in vars) if (!isClosed(v)) return false
        return true
    }

    /** The two readings a lane has of its columns. */
    companion object {
        /**
         * The root-propagated domains a finite projection folded.
         *
         * Every column answers: a finite projection has no open side. These are narrower than the model
         * states — root propagation proved them — so the finite lane's reductions are the stronger ones.
         */
        fun of(domains: Array<IntDomain>): ColumnRanges = object : ColumnRanges {
            override fun isClosed(v: Int): Boolean = true
            override fun min(v: Int): Long = domains[v].min
            override fun max(v: Int): Long = domains[v].max
            override fun allClosed(vars: IntArray): Boolean = true
        }

        /**
         * The ranges a source model states, before any finite projection exists.
         *
         * A column with an open side has no width here, whatever fallback box a lane may later materialize
         * for it: charging an invented endpoint would justify a reduction the model does not.
         */
        fun of(bounds: IntBounds): ColumnRanges = object : ColumnRanges {
            override fun isClosed(v: Int): Boolean = bounds.hasLower(v) && bounds.hasUpper(v)
            override fun min(v: Int): Long = bounds.lower(v)
            override fun max(v: Int): Long = bounds.upper(v)
        }
    }
}
