package com.eignex.klause.solver

/** Declared storage capability of one source integer column. */
sealed interface IntColumn {
    /**
     * A finite-domain component owns mutable search state for this column.
     *
     * @property domain Finite CP domain.
     */
    data class Finite(val domain: IntDomain) : IntColumn

    /** A theory owns this column symbolically; no finite search state exists. */
    data object Symbolic : IntColumn
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

    /** Finite domain of [v], or null when a theory owns it symbolically. */
    fun domainOrNull(v: Int): IntDomain? = (column(v) as? IntColumn.Finite)?.domain

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
