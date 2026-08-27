package com.eignex.klause.propagation

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Solver

import com.eignex.klause.util.EmptyBooleanArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.binarySearchInt

/**
 * Per-call constraint on the solver: pin specific variables to specific values for the
 * duration of the call. Compatible with all the entry points on [Solver] and
 * [com.eignex.klause.solver.Optimizer]; backends that can't enforce assumptions (e.g. pure model-counting paths)
 * will document the limitation.
 *
 * Implementations are expected to:
 *  - initialise (or re-initialise on restart) the assignment with the assumed values,
 *  - skip any move proposal that would change an assumed variable,
 *  - leave the underlying [Problem] untouched — assumptions are call-scoped, not
 *    permanent constraints.
 *
 * If the assumed values are jointly infeasible against the problem's constraints the
 * solver may return `null` / `Unknown` rather than reporting `Unsat` (local-search
 * cannot prove UNSAT).
 *
 * The exact pins are the user/search-facing contract ([withBool] / [withInt] / [mergedWith]). The
 * presolve-derived bound tightenings, holes, and set-restrictions produced by SAC-at-root and
 * bake-time probing ride alongside in [deductions] — they are applied together at seed time but never
 * originate from the pin API.
 *
 * Storage is parallel primitive arrays sorted ascending by key — no autoboxing on any
 * read, no `HashMap` allocations on merge, and binary-search lookup for [boolValueOrNull]
 * / [intValueOrNull]. The [bools] / [ints] map views are kept for the bake-time
 * failed-literal probing path in [Problem] and for tests; hot paths should use the
 * primitive accessors ([forEachBool], [forEachInt], [boolValueOrNull], [intValueOrNull],
 * [mergedWith]) which never allocate.
 */
class Assumptions internal constructor(
    /** Bool var ids, ascending. Public for primitive iteration in [forEachBool]. */
    val boolKeys: IntArray,
    /** Pinned values aligned with [boolKeys]. */
    val boolValues: BooleanArray,
    /** Int var ids pinned to an exact value, ascending. */
    val intKeys: IntArray,
    /** Pinned values aligned with [intKeys]. */
    val intValues: LongArray,
    /** Presolve-derived bound/hole/set narrowings applied alongside the pins; disjoint from [intKeys]. */
    val deductions: DeducedRestrictions = DeducedRestrictions.None,
) {

    /** True iff no bool or int variable is pinned. */
    val isEmpty: Boolean get() = boolKeys.isEmpty() && intKeys.isEmpty()

    /** Number of pinned Boolean variables. */
    val numBools: Int get() = boolKeys.size

    /** Number of exactly-pinned integer variables. */
    val numInts: Int get() = intKeys.size

    /** True iff Boolean variable [id] is pinned. */
    fun isFrozenBool(id: Int): Boolean = boolKeys.binarySearchInt(id) >= 0

    /** True iff integer variable [id] is pinned to an exact value. */
    fun isFrozenInt(id: Int): Boolean = intKeys.binarySearchInt(id) >= 0

    /** Pinned value for bool [id], or `null` if it isn't an assumption. */
    fun boolValueOrNull(id: Int): Boolean? {
        val idx = boolKeys.binarySearchInt(id)
        return if (idx >= 0) boolValues[idx] else null
    }

    /** Pinned value for int [id], or `null` if it isn't an assumption. */
    fun intValueOrNull(id: Int): Long? {
        val idx = intKeys.binarySearchInt(id)
        return if (idx >= 0) intValues[idx] else null
    }

    /** Primitive iteration over bool pins in ascending-key order. No allocation. */
    inline fun forEachBool(action: (id: Int, value: Boolean) -> Unit) {
        for (i in boolKeys.indices) action(boolKeys[i], boolValues[i])
    }

    /** Primitive iteration over int pins in ascending-key order. No allocation. */
    inline fun forEachInt(action: (id: Int, value: Long) -> Unit) {
        for (i in intKeys.indices) action(intKeys[i], intValues[i])
    }

    /**
     * Merge `this` with [other]; on key overlap, [other]'s value wins (last-write
     * semantics — the Session abstraction relies on this). Returns a fresh
     * [Assumptions]; the inputs are untouched.
     *
     * Primitive sorted-merge in O(n + m); no `HashMap`, no autoboxing. The [deductions] merge is
     * delegated to [DeducedRestrictions.mergedWith] under the merged exact-pin set.
     */
    fun mergedWith(other: Assumptions): Assumptions {
        if (other.isEmpty && other.deductions.isEmpty) return this
        if (this.isEmpty && deductions.isEmpty) return other
        val mergedBoolKeys = IntArrayList(boolKeys.size + other.boolKeys.size)
        val mergedBoolValues = ArrayList<Boolean>(boolKeys.size + other.boolKeys.size)
        sortedMergeBools(
            boolKeys,
            boolValues,
            other.boolKeys,
            other.boolValues,
            mergedBoolKeys,
            mergedBoolValues,
        )
        val mergedIntKeys = IntArrayList(intKeys.size + other.intKeys.size)
        val mergedIntValues = LongArrayList(intKeys.size + other.intKeys.size)
        sortedMergeInts(
            intKeys,
            intValues,
            other.intKeys,
            other.intValues,
            mergedIntKeys,
            mergedIntValues,
        )
        // Bound/hole/set tightenings drop any var that is now exactly pinned (the pin subsumes).
        val pinned = IntHashSet()
        for (i in 0 until mergedIntKeys.size) pinned.add(mergedIntKeys[i])
        val mergedDeductions = deductions.mergedWith(other.deductions, pinned)
        return Assumptions(
            boolKeys = mergedBoolKeys.toIntArray(),
            boolValues = BooleanArray(mergedBoolValues.size) { mergedBoolValues[it] },
            intKeys = mergedIntKeys.toIntArray(),
            intValues = mergedIntValues.toLongArray(),
            deductions = mergedDeductions,
        )
    }

    /** Rebuild with a subset of fields replaced; every unspecified field (including [deductions]) is
     *  carried over unchanged. */
    private fun copy(
        boolKeys: IntArray = this.boolKeys,
        boolValues: BooleanArray = this.boolValues,
        intKeys: IntArray = this.intKeys,
        intValues: LongArray = this.intValues,
        deductions: DeducedRestrictions = this.deductions,
    ): Assumptions = Assumptions(boolKeys, boolValues, intKeys, intValues, deductions)

    /** Return a fresh [Assumptions] that also pins bool [id] to [value]. Existing
     *  bool pin on [id] is overwritten. */
    fun withBool(id: Int, value: Boolean): Assumptions {
        val idx = boolKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = boolValues.copyOf()
            nv[idx] = value
            copy(boolValues = nv)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(boolKeys.size + 1)
            val nv = BooleanArray(boolKeys.size + 1)
            boolKeys.copyInto(nk, 0, 0, insert)
            boolValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id
            nv[insert] = value
            boolKeys.copyInto(nk, insert + 1, insert)
            boolValues.copyInto(nv, insert + 1, insert)
            copy(boolKeys = nk, boolValues = nv)
        }
    }

    /** Return a fresh [Assumptions] that also pins int [id] to [value]. Existing
     *  int pin on [id] is overwritten; any prior bound tightening on [id] is dropped
     *  since the exact pin subsumes it. */
    fun withInt(id: Int, value: Long): Assumptions {
        val newDeductions = deductions.withoutBounds(id)
        val idx = intKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intValues.copyOf()
            nv[idx] = value
            copy(intValues = nv, deductions = newDeductions)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intKeys.size + 1)
            val nv = LongArray(intKeys.size + 1)
            intKeys.copyInto(nk, 0, 0, insert)
            intValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id
            nv[insert] = value
            intKeys.copyInto(nk, insert + 1, insert)
            intValues.copyInto(nv, insert + 1, insert)
            copy(intKeys = nk, intValues = nv, deductions = newDeductions)
        }
    }

    /** Return a fresh [Assumptions] with [id]'s lower bound tightened to at least [value].
     *  Used by SAC-at-root to accumulate non-singleton deductions. */
    fun withTightenedMin(id: Int, value: Long): Assumptions = copy(deductions = deductions.withTightenedMin(id, value))

    /** Return a fresh [Assumptions] with [id]'s upper bound tightened to at most [value]. */
    fun withTightenedMax(id: Int, value: Long): Assumptions = copy(deductions = deductions.withTightenedMax(id, value))

    /** Return a fresh [Assumptions] with [id ≠ value] punched in as an interior-hole
     *  assumption. Idempotent if the hole already exists. Caller is responsible for
     *  ensuring [value] is in the var's current effective domain — the engine will
     *  raise on attempts to exclude a singleton's sole value, mirroring tighten-on-pin. */
    fun withIntHole(id: Int, value: Long): Assumptions = copy(deductions = deductions.withIntHole(id, value))

    /** Map view. Allocates a `LinkedHashMap` per access — used by cold paths like
     *  [Problem]'s failed-literal probing and by tests; hot paths should call
     *  [forEachBool] / [boolValueOrNull] instead. */
    val bools: Map<Int, Boolean>
        get() = if (boolKeys.isEmpty()) {
            emptyMap()
        } else {
            LinkedHashMap<Int, Boolean>(boolKeys.size).also { m ->
                for (i in boolKeys.indices) m[boolKeys[i]] = boolValues[i]
            }
        }

    /** Map view. Allocates per access — see [bools]. */
    val ints: Map<Int, Long>
        get() = if (intKeys.isEmpty()) {
            emptyMap()
        } else {
            LinkedHashMap<Int, Long>(intKeys.size).also { m ->
                for (i in intKeys.indices) m[intKeys[i]] = intValues[i]
            }
        }

    override fun equals(other: Any?): Boolean {
        if (other !is Assumptions) return false
        return boolKeys.contentEquals(other.boolKeys) &&
            boolValues.contentEquals(other.boolValues) &&
            intKeys.contentEquals(other.intKeys) &&
            intValues.contentEquals(other.intValues) &&
            deductions == other.deductions
    }

    override fun hashCode(): Int {
        var h = boolKeys.contentHashCode()
        h = 31 * h + boolValues.contentHashCode()
        h = 31 * h + intKeys.contentHashCode()
        h = 31 * h + intValues.contentHashCode()
        h = 31 * h + deductions.hashCode()
        return h
    }

    override fun toString(): String = buildString {
        append("Assumptions(bools={")
        for (i in boolKeys.indices) {
            if (i > 0) append(", ")
            append(boolKeys[i])
            append("=")
            append(boolValues[i])
        }
        append("}, ints={")
        for (i in intKeys.indices) {
            if (i > 0) append(", ")
            append(intKeys[i])
            append("=")
            append(intValues[i])
        }
        append("})")
    }

    /** Shared [Assumptions] instances and builders. */
    companion object {
        /** The empty assumption set (nothing pinned). */
        val None: Assumptions =
            Assumptions(EmptyIntArray, EmptyBooleanArray, EmptyIntArray, EmptyLongArray)

        /** Map-based factory. Call sites use `Assumptions(bools = mapOf(0 to true))`;
         *  internally normalises to the primitive sorted-array form. */
        operator fun invoke(bools: Map<Int, Boolean> = emptyMap(), ints: Map<Int, Long> = emptyMap()): Assumptions {
            if (bools.isEmpty() && ints.isEmpty()) return None
            val bKeys = bools.keys.toIntArray().also { it.sort() }
            val bVals = BooleanArray(bKeys.size) { bools.getValue(bKeys[it]) }
            val iKeys = ints.keys.toIntArray().also { it.sort() }
            val iVals = LongArray(iKeys.size) { ints.getValue(iKeys[it]) }
            return Assumptions(bKeys, bVals, iKeys, iVals)
        }

        private fun sortedMergeBools(
            ak: IntArray,
            av: BooleanArray,
            bk: IntArray,
            bv: BooleanArray,
            outK: IntArrayList,
            outV: ArrayList<Boolean>,
        ) {
            var i = 0
            var j = 0
            while (i < ak.size && j < bk.size) {
                when {
                    ak[i] < bk[j] -> {
                        outK.add(ak[i])
                        outV.add(av[i])
                        i++
                    }

                    ak[i] > bk[j] -> {
                        outK.add(bk[j])
                        outV.add(bv[j])
                        j++
                    }

                    else -> {
                        outK.add(bk[j])
                        outV.add(bv[j])
                        i++
                        j++
                    } // last-write wins → b
                }
            }
            while (i < ak.size) {
                outK.add(ak[i])
                outV.add(av[i])
                i++
            }
            while (j < bk.size) {
                outK.add(bk[j])
                outV.add(bv[j])
                j++
            }
        }

        private fun sortedMergeInts(
            ak: IntArray,
            av: LongArray,
            bk: IntArray,
            bv: LongArray,
            outK: IntArrayList,
            outV: LongArrayList,
        ) {
            var i = 0
            var j = 0
            while (i < ak.size && j < bk.size) {
                when {
                    ak[i] < bk[j] -> {
                        outK.add(ak[i])
                        outV.add(av[i])
                        i++
                    }

                    ak[i] > bk[j] -> {
                        outK.add(bk[j])
                        outV.add(bv[j])
                        j++
                    }

                    else -> {
                        outK.add(bk[j])
                        outV.add(bv[j])
                        i++
                        j++
                    }
                }
            }
            while (i < ak.size) {
                outK.add(ak[i])
                outV.add(av[i])
                i++
            }
            while (j < bk.size) {
                outK.add(bk[j])
                outV.add(bv[j])
                j++
            }
        }
    }
}
