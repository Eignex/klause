package com.eignex.klause.solver

/** Declared storage capability of one source integer column. */
sealed interface IntColumn {
    /**
     * A finite-domain component owns mutable search state for this column.
     *
     * @property domain Finite CP domain.
     */
    data class Finite(val domain: IntDomain) : IntColumn

    /**
     * No finite search state exists for this column: a theory owns it and reasons over the bounds the
     * source model stated, either of which may be absent.
     *
     * @property lower Inclusive lower bound, or null when the column is open below.
     * @property upper Inclusive upper bound, or null when the column is open above.
     */
    data class Bounded(val lower: Long?, val upper: Long?) : IntColumn
}

/**
 * Typed integer-column storage for a [Problem].
 *
 * [FiniteIntColumns] is the hot all-CP representation. [MixedIntColumns] exists only when a composed
 * solve has symbolic theory columns, so ordinary finite search retains its packed domain array.
 */
sealed class IntColumns {
    /** Number of source integer columns. */
    abstract val size: Int

    /** Typed capability of source column [v]. */
    abstract fun column(v: Int): IntColumn

    /** Finite domain of [v], or null when a theory owns it and only its bounds are known. */
    fun domainOrNull(v: Int): IntDomain? = (column(v) as? IntColumn.Finite)?.domain

    /** Bounds of [v] however it is owned: a finite domain reports its own, a theory column its stated ones. */
    fun boundsOf(v: Int): IntColumn.Bounded = when (val c = column(v)) {
        is IntColumn.Finite -> IntColumn.Bounded(c.domain.min, c.domain.max)
        is IntColumn.Bounded -> c
    }

    /** Packed finite domains when every column is CP-owned, or null otherwise. */
    abstract fun allFiniteOrNull(): Array<IntDomain>?
}

/** Packed finite domains for an all-CP problem. */
class FiniteIntColumns(domains: Array<IntDomain>, shared: Boolean = false) : IntColumns() {
    private val domains: Array<IntDomain> = if (shared) domains else domains.copyOf()

    override val size: Int get() = domains.size

    override fun column(v: Int): IntColumn = IntColumn.Finite(domains[v])

    override fun allFiniteOrNull(): Array<IntDomain> = domains
}

/** Source-indexed typed columns for a hybrid CP/theory problem. */
class MixedIntColumns(columns: Array<IntColumn>) : IntColumns() {
    private val columns = columns.copyOf()

    override val size: Int get() = columns.size

    override fun column(v: Int): IntColumn = columns[v]

    override fun allFiniteOrNull(): Array<IntDomain>? {
        if (columns.any { it !is IntColumn.Finite }) return null
        return Array(columns.size) { (columns[it] as IntColumn.Finite).domain }
    }
}
