package com.eignex.klause.lp.engine

/** Where a variable sits. Nonbasic variables are pinned to a finite bound; basic ones float. */
internal enum class VarStatus {
    /** Basic: the variable floats; its value is read off the basis. */
    BASIC,

    /** Nonbasic, pinned to its lower bound. */
    AT_LOWER,

    /** Nonbasic, pinned to its upper bound. */
    AT_UPPER,
}

/**
 * A basis: the `m` basic variable columns plus the bound each nonbasic variable is pinned to. The
 * float [RevisedSimplex] returns one for exact certification ([integerCertify]); because
 * branch-and-bound only tightens bounds, a parent basis stays dual-feasible so a child re-optimizes
 * with a few pivots instead of a cold solve.
 */
internal class Basis(
    /** The ordered `m` variable columns that are basic; position identifies the basis coordinate. */
    val basicVars: IntArray,
    /** Per-variable status (length `numVars`): [VarStatus.BASIC], [VarStatus.AT_LOWER] or `AT_UPPER`. */
    val status: Array<VarStatus>,
)

internal enum class ExactLpStatus { BASIC, AT_LOWER, AT_UPPER, FIXED, FREE }

// The heading order is part of the factor contract; this declaration does not establish nonsingularity.
internal class ExactLpBasis(headings: List<Int>, statuses: List<ExactLpStatus>) {
    private val headings = headings.toList()
    private val statuses = statuses.toList()

    init {
        require(this.headings.distinct().size == this.headings.size) { "basis headings must be unique" }
        require(this.headings.all { it in this.statuses.indices }) { "basis heading outside columns" }
        require(this.statuses.indices.all { (it in this.headings) == (this.statuses[it] == ExactLpStatus.BASIC) }) {
            "basis headings and basic statuses disagree"
        }
    }

    fun heading(position: Int): Int = headings[position]
    fun status(column: Int): ExactLpStatus = statuses[column]

    fun validFor(model: ExactLpModel): Boolean {
        if (headings.size != model.m || statuses.size != model.numVars) return false
        return statuses.indices.all { j ->
            val bounds = model.column(j).bounds
            bounds.consistent && when (statuses[j]) {
                ExactLpStatus.BASIC -> true
                ExactLpStatus.AT_LOWER -> bounds.lower != null && !bounds.lower.strict
                ExactLpStatus.AT_UPPER -> bounds.upper != null && !bounds.upper.strict
                ExactLpStatus.FIXED -> bounds.fixed
                ExactLpStatus.FREE -> bounds.lower == null && bounds.upper == null
            }
        }
    }

    fun toLegacy(model: ExactLpModel): LegacyLpBasis? {
        if (!validFor(model)) return null
        val legacy = model.toLegacy() ?: return null
        val status = Array(statuses.size) { j ->
            when (statuses[j]) {
                ExactLpStatus.BASIC -> VarStatus.BASIC
                ExactLpStatus.AT_LOWER, ExactLpStatus.FIXED -> VarStatus.AT_LOWER
                ExactLpStatus.AT_UPPER -> VarStatus.AT_UPPER
                ExactLpStatus.FREE -> return null
            }
        }
        return LegacyLpBasis(model, this, legacy, Basis(headings.toIntArray(), status))
    }
}

// Retain fixedness and the exact immutable authority alongside the independently owned legacy data.
internal class LegacyLpBasis(
    val source: ExactLpModel,
    val exactBasis: ExactLpBasis,
    val model: LpModel,
    val basis: Basis,
)
