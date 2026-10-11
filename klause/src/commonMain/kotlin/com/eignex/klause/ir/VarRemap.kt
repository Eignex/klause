package com.eignex.klause.ir

import com.eignex.klause.util.EmptyIntArray

/** Owned renumbering of source variables: `newId = map(oldId)`, one captured table per namespace. */
class VarRemap private constructor(bools: IntArray, ints: IntArray, reals: IntArray, borrowed: Boolean) {
    /** Capture the caller's source renumbering tables. */
    constructor(bools: IntArray, ints: IntArray, reals: IntArray = EmptyIntArray) :
        this(bools, ints, reals, borrowed = false)

    private val bools = if (borrowed) bools else bools.copyOf()
    private val ints = if (borrowed) ints else ints.copyOf()
    private val reals = if (borrowed) reals else reals.copyOf()

    internal companion object {
        // Refinement reads a mutable owner's tables only within one synchronous workspace operation.
        internal fun borrowed(bools: IntArray, ints: IntArray): VarRemap =
            VarRemap(bools, ints, EmptyIntArray, borrowed = true)
    }

    /** Renumber a source Boolean, or null when the variable is absent from the target. */
    fun bool(id: SourceBoolId): SourceBoolId? = bool(id.index).takeIf { it >= 0 }?.let(::SourceBoolId)

    /** Renumber a source integer, or null when the variable is absent from the target. */
    fun int(id: SourceIntId): SourceIntId? = int(id.index).takeIf { it >= 0 }?.let(::SourceIntId)

    /** Renumber a source real, retaining its identity when no real table is supplied. */
    fun real(id: SourceRealId): SourceRealId? = real(id.index).takeIf { it >= 0 }?.let(::SourceRealId)

    /** Number of Boolean variables this remap covers. */
    val boolCount: Int get() = bools.size

    /** Number of integer columns this remap covers. */
    val intCount: Int get() = ints.size

    /** The new id of Boolean variable [id]. */
    fun bool(id: Int): Int = bools[id]

    /** The new id of integer column [id]. */
    fun int(id: Int): Int = ints[id]

    /** The new id of real column [id], or [id] when no real map is supplied. */
    fun real(id: Int): Int = if (reals.isEmpty()) id else reals[id]

    /** [ids] with every Boolean variable renumbered. */
    fun bools(ids: IntArray): IntArray = IntArray(ids.size) { bools[ids[it]] }

    /** [ids] with every integer column renumbered. */
    fun ints(ids: IntArray): IntArray = IntArray(ids.size) { ints[ids[it]] }

    /** [ids] with every real column renumbered. */
    fun reals(ids: IntArray): IntArray = if (reals.isEmpty()) ids.copyOf() else IntArray(ids.size) { reals[ids[it]] }

    /** [lits] with variables renumbered and signs preserved. */
    fun lits(lits: IntArray): IntArray =
        IntArray(lits.size) { Lit.make(bools[Lit.variable(lits[it])], Lit.isPositive(lits[it])) }

    /** The same literal over the renumbered variable. */
    fun lit(literal: Int): Int = Lit.make(bools[Lit.variable(literal)], Lit.isPositive(literal))
}
