package com.eignex.klause.solver

/**
 * Per-call constraint on the solver: pin specific variables to specific values for the
 * duration of the call. Compatible with all the entry points on [Solver] and
 * [Optimizer]; backends that can't enforce assumptions (e.g. pure model-counting paths)
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
 * Storage is parallel primitive arrays sorted ascending by key — no autoboxing on any
 * read, no `HashMap` allocations on merge, and binary-search lookup for [boolValueOrNull]
 * / [intValueOrNull]. The legacy [bools] / [ints] map accessors are kept as lazy views
 * so existing call-sites keep working, but the hot paths should prefer the primitive
 * accessors ([forEachBool], [forEachInt], [boolValueOrNull], [intValueOrNull],
 * [mergedWith]) which never allocate.
 */
class Assumptions internal constructor(
    /** Bool var ids, ascending. Public for primitive iteration in [forEachBool]. */
    val boolKeys: IntArray,
    /** Pinned values aligned with [boolKeys]. */
    val boolValues: BooleanArray,
    /** Int var ids, ascending. */
    val intKeys: IntArray,
    /** Pinned values aligned with [intKeys]. */
    val intValues: IntArray,
) {

    val isEmpty: Boolean get() = boolKeys.isEmpty() && intKeys.isEmpty()
    val numBools: Int get() = boolKeys.size
    val numInts: Int get() = intKeys.size

    fun isFrozenBool(id: Int): Boolean = boolKeys.binarySearch(id) >= 0
    fun isFrozenInt(id: Int): Boolean = intKeys.binarySearch(id) >= 0

    /** Pinned value for bool [id], or `null` if it isn't an assumption. */
    fun boolValueOrNull(id: Int): Boolean? {
        val idx = boolKeys.binarySearch(id)
        return if (idx >= 0) boolValues[idx] else null
    }

    /** Pinned value for int [id], or `null` if it isn't an assumption. */
    fun intValueOrNull(id: Int): Int? {
        val idx = intKeys.binarySearch(id)
        return if (idx >= 0) intValues[idx] else null
    }

    /** Primitive iteration over bool pins in ascending-key order. No allocation. */
    inline fun forEachBool(action: (id: Int, value: Boolean) -> Unit) {
        for (i in boolKeys.indices) action(boolKeys[i], boolValues[i])
    }

    /** Primitive iteration over int pins in ascending-key order. No allocation. */
    inline fun forEachInt(action: (id: Int, value: Int) -> Unit) {
        for (i in intKeys.indices) action(intKeys[i], intValues[i])
    }

    /**
     * Merge `this` with [other]; on key overlap, [other]'s value wins (last-write
     * semantics — the Session abstraction relies on this). Returns a fresh
     * [Assumptions]; the inputs are untouched.
     *
     * Primitive sorted-merge in O(n + m); no `HashMap`, no autoboxing. Replaces the old
     * `HashMap<Int, Boolean>(a.bools).apply { putAll(b.bools) }` pattern that lit up
     * every session merge call.
     */
    fun mergedWith(other: Assumptions): Assumptions {
        if (other.isEmpty) return this
        if (this.isEmpty) return other
        val mergedBoolKeys = ArrayList<Int>(boolKeys.size + other.boolKeys.size)
        val mergedBoolValues = ArrayList<Boolean>(boolKeys.size + other.boolKeys.size)
        sortedMergeBools(boolKeys, boolValues, other.boolKeys, other.boolValues,
            mergedBoolKeys, mergedBoolValues)
        val mergedIntKeys = ArrayList<Int>(intKeys.size + other.intKeys.size)
        val mergedIntValues = ArrayList<Int>(intKeys.size + other.intKeys.size)
        sortedMergeInts(intKeys, intValues, other.intKeys, other.intValues,
            mergedIntKeys, mergedIntValues)
        return Assumptions(
            boolKeys = mergedBoolKeys.toIntArray(),
            boolValues = BooleanArray(mergedBoolValues.size) { mergedBoolValues[it] },
            intKeys = mergedIntKeys.toIntArray(),
            intValues = mergedIntValues.toIntArray(),
        )
    }

    /** Return a fresh [Assumptions] that also pins bool [id] to [value]. Existing
     *  bool pin on [id] is overwritten. */
    fun withBool(id: Int, value: Boolean): Assumptions {
        val idx = boolKeys.binarySearch(id)
        return if (idx >= 0) {
            val nv = boolValues.copyOf(); nv[idx] = value
            Assumptions(boolKeys, nv, intKeys, intValues)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(boolKeys.size + 1)
            val nv = BooleanArray(boolKeys.size + 1)
            boolKeys.copyInto(nk, 0, 0, insert)
            boolValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            boolKeys.copyInto(nk, insert + 1, insert)
            boolValues.copyInto(nv, insert + 1, insert)
            Assumptions(nk, nv, intKeys, intValues)
        }
    }

    /** Return a fresh [Assumptions] that also pins int [id] to [value]. Existing
     *  int pin on [id] is overwritten. */
    fun withInt(id: Int, value: Int): Assumptions {
        val idx = intKeys.binarySearch(id)
        return if (idx >= 0) {
            val nv = intValues.copyOf(); nv[idx] = value
            Assumptions(boolKeys, boolValues, intKeys, nv)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intKeys.size + 1)
            val nv = IntArray(intKeys.size + 1)
            intKeys.copyInto(nk, 0, 0, insert)
            intValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            intKeys.copyInto(nk, insert + 1, insert)
            intValues.copyInto(nv, insert + 1, insert)
            Assumptions(boolKeys, boolValues, nk, nv)
        }
    }

    /** Legacy backward-compat view. Allocates a `LinkedHashMap` per access — hot paths
     *  should call [forEachBool] / [boolValueOrNull] instead. */
    val bools: Map<Int, Boolean>
        get() = if (boolKeys.isEmpty()) emptyMap() else
            LinkedHashMap<Int, Boolean>(boolKeys.size).also { m ->
                for (i in boolKeys.indices) m[boolKeys[i]] = boolValues[i]
            }

    /** Legacy backward-compat view. Allocates per access — see [bools]. */
    val ints: Map<Int, Int>
        get() = if (intKeys.isEmpty()) emptyMap() else
            LinkedHashMap<Int, Int>(intKeys.size).also { m ->
                for (i in intKeys.indices) m[intKeys[i]] = intValues[i]
            }

    override fun equals(other: Any?): Boolean {
        if (other !is Assumptions) return false
        return boolKeys.contentEquals(other.boolKeys) &&
            boolValues.contentEquals(other.boolValues) &&
            intKeys.contentEquals(other.intKeys) &&
            intValues.contentEquals(other.intValues)
    }

    override fun hashCode(): Int {
        var h = boolKeys.contentHashCode()
        h = 31 * h + boolValues.contentHashCode()
        h = 31 * h + intKeys.contentHashCode()
        h = 31 * h + intValues.contentHashCode()
        return h
    }

    override fun toString(): String = buildString {
        append("Assumptions(bools={")
        for (i in boolKeys.indices) {
            if (i > 0) append(", ")
            append(boolKeys[i]); append("="); append(boolValues[i])
        }
        append("}, ints={")
        for (i in intKeys.indices) {
            if (i > 0) append(", ")
            append(intKeys[i]); append("="); append(intValues[i])
        }
        append("})")
    }

    companion object {
        val None: Assumptions =
            Assumptions(IntArray(0), BooleanArray(0), IntArray(0), IntArray(0))

        /** Map-based constructor preserved for backward compat — call sites can keep
         *  using `Assumptions(bools = mapOf(0 to true))`. Internally normalises to the
         *  primitive sorted-array form. */
        operator fun invoke(
            bools: Map<Int, Boolean> = emptyMap(),
            ints: Map<Int, Int> = emptyMap(),
        ): Assumptions {
            if (bools.isEmpty() && ints.isEmpty()) return None
            val bKeys = bools.keys.toIntArray().also { it.sort() }
            val bVals = BooleanArray(bKeys.size) { bools.getValue(bKeys[it]) }
            val iKeys = ints.keys.toIntArray().also { it.sort() }
            val iVals = IntArray(iKeys.size) { ints.getValue(iKeys[it]) }
            return Assumptions(bKeys, bVals, iKeys, iVals)
        }

        private fun sortedMergeBools(
            ak: IntArray, av: BooleanArray, bk: IntArray, bv: BooleanArray,
            outK: ArrayList<Int>, outV: ArrayList<Boolean>,
        ) {
            var i = 0; var j = 0
            while (i < ak.size && j < bk.size) {
                when {
                    ak[i] < bk[j] -> { outK.add(ak[i]); outV.add(av[i]); i++ }
                    ak[i] > bk[j] -> { outK.add(bk[j]); outV.add(bv[j]); j++ }
                    else -> { outK.add(bk[j]); outV.add(bv[j]); i++; j++ } // last-write wins → b
                }
            }
            while (i < ak.size) { outK.add(ak[i]); outV.add(av[i]); i++ }
            while (j < bk.size) { outK.add(bk[j]); outV.add(bv[j]); j++ }
        }

        private fun sortedMergeInts(
            ak: IntArray, av: IntArray, bk: IntArray, bv: IntArray,
            outK: ArrayList<Int>, outV: ArrayList<Int>,
        ) {
            var i = 0; var j = 0
            while (i < ak.size && j < bk.size) {
                when {
                    ak[i] < bk[j] -> { outK.add(ak[i]); outV.add(av[i]); i++ }
                    ak[i] > bk[j] -> { outK.add(bk[j]); outV.add(bv[j]); j++ }
                    else -> { outK.add(bk[j]); outV.add(bv[j]); i++; j++ }
                }
            }
            while (i < ak.size) { outK.add(ak[i]); outV.add(av[i]); i++ }
            while (j < bk.size) { outK.add(bk[j]); outV.add(bv[j]); j++ }
        }
    }
}
