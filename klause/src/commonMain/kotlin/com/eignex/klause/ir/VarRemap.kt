package com.eignex.klause.ir

import com.eignex.klause.util.EmptyIntArray

/** A renumbering of a problem's variables: `newId = map[oldId]`, one map per namespace. */
class VarRemap(private val bools: IntArray, private val ints: IntArray, private val reals: IntArray = EmptyIntArray) {
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
