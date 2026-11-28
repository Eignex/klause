package com.eignex.klause.presolve

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain

// How wide each integer column is, for a reduction whose argument charges a column its whole range.
// An activity argument needs both sides; a one-sided reduction reads [hasLower] or [hasUpper]. An open
// column has no width, so a caller declines any row that mentions one, which lets rows over closed columns
// reduce on a model that is open elsewhere.
// Primitive on purpose: ranges are read once per term per row, and returning an [IntDomain] per access
// would allocate on the hot path.
internal interface ColumnRanges {

    fun isClosed(v: Int): Boolean

    fun hasLower(v: Int): Boolean = isClosed(v)

    fun hasUpper(v: Int): Boolean = isClosed(v)

    // Defined only where [hasLower].
    fun min(v: Int): Long

    // Defined only where [hasUpper].
    fun max(v: Int): Long

    // A source reduction that eliminates a binary column takes it from binary-column substitution, leaving
    // a would-be pseudo-Boolean model hybrid, which costs it the lane.
    fun isBinary(v: Int): Boolean = isClosed(v) && min(v) == 0L && max(v) == 1L

    fun allClosed(vars: IntArray): Boolean {
        for (v in vars) if (!isClosed(v)) return false
        return true
    }

    companion object {
        // Root-propagated domains of a finite projection: every column is closed, and narrower than the
        // model states.
        fun of(domains: Array<IntDomain>): ColumnRanges = object : ColumnRanges {
            override fun isClosed(v: Int): Boolean = true
            override fun min(v: Int): Long = domains[v].min
            override fun max(v: Int): Long = domains[v].max
            override fun allClosed(vars: IntArray): Boolean = true
        }

        // A column with an open side has no width here, whatever fallback box a lane later materializes for
        // it: charging an invented endpoint would justify a reduction the model does not.
        fun of(bounds: IntBounds): ColumnRanges = object : ColumnRanges {
            override fun isClosed(v: Int): Boolean = bounds.hasLower(v) && bounds.hasUpper(v)
            override fun hasLower(v: Int): Boolean = bounds.hasLower(v)
            override fun hasUpper(v: Int): Boolean = bounds.hasUpper(v)
            override fun min(v: Int): Long = bounds.lower(v)
            override fun max(v: Int): Long = bounds.upper(v)
        }
    }
}
