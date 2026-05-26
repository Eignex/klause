package com.eignex.klause.solver

import com.eignex.klause.util.binarySearchInt

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
    val intValues: IntArray,
    /** Int var ids with an additional `≥ minValue` lower-bound tightening (no exact pin),
     *  ascending. Disjoint from [intKeys]. Used by SAC-at-root to record bound deductions
     *  that aren't yet singletons. */
    val intMinKeys: IntArray = IntArray(0),
    val intMinValues: IntArray = IntArray(0),
    /** Int var ids with an additional `≤ maxValue` upper-bound tightening (no exact pin),
     *  ascending. Disjoint from [intKeys]. */
    val intMaxKeys: IntArray = IntArray(0),
    val intMaxValues: IntArray = IntArray(0),
    /** Interior holes: parallel `(varId, value)` rows, lexicographically sorted by
     *  `(varId, value)`. Each row encodes `v ≠ value` for that var. Disjoint from
     *  [intKeys]; values strictly inside the var's [min, max] bounds (or its assumed
     *  bounds). Used by SAC-at-root to record value-level deductions that fall between
     *  bound shifts. */
    val intHoleVarIds: IntArray = IntArray(0),
    val intHoleValues: IntArray = IntArray(0),
) {

    val isEmpty: Boolean get() = boolKeys.isEmpty() && intKeys.isEmpty()
    val numBools: Int get() = boolKeys.size
    val numInts: Int get() = intKeys.size

    fun isFrozenBool(id: Int): Boolean = boolKeys.binarySearchInt(id) >= 0
    fun isFrozenInt(id: Int): Boolean = intKeys.binarySearchInt(id) >= 0

    /** Pinned value for bool [id], or `null` if it isn't an assumption. */
    fun boolValueOrNull(id: Int): Boolean? {
        val idx = boolKeys.binarySearchInt(id)
        return if (idx >= 0) boolValues[idx] else null
    }

    /** Pinned value for int [id], or `null` if it isn't an assumption. */
    fun intValueOrNull(id: Int): Int? {
        val idx = intKeys.binarySearchInt(id)
        return if (idx >= 0) intValues[idx] else null
    }

    /** Lower-bound tightening for int [id], or `null` if none. */
    fun intMinOrNull(id: Int): Int? {
        val idx = intMinKeys.binarySearchInt(id)
        return if (idx >= 0) intMinValues[idx] else null
    }

    /** Upper-bound tightening for int [id], or `null` if none. */
    fun intMaxOrNull(id: Int): Int? {
        val idx = intMaxKeys.binarySearchInt(id)
        return if (idx >= 0) intMaxValues[idx] else null
    }

    inline fun forEachIntMin(action: (id: Int, value: Int) -> Unit) {
        for (i in intMinKeys.indices) action(intMinKeys[i], intMinValues[i])
    }

    inline fun forEachIntMax(action: (id: Int, value: Int) -> Unit) {
        for (i in intMaxKeys.indices) action(intMaxKeys[i], intMaxValues[i])
    }

    inline fun forEachIntHole(action: (id: Int, value: Int) -> Unit) {
        for (i in intHoleVarIds.indices) action(intHoleVarIds[i], intHoleValues[i])
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
     * Primitive sorted-merge in O(n + m); no `HashMap`, no autoboxing.
     */
    fun mergedWith(other: Assumptions): Assumptions {
        if (other.isEmpty &&
            other.intMinKeys.isEmpty() && other.intMaxKeys.isEmpty() && other.intHoleVarIds.isEmpty()) return this
        if (this.isEmpty &&
            intMinKeys.isEmpty() && intMaxKeys.isEmpty() && intHoleVarIds.isEmpty()) return other
        val mergedBoolKeys = ArrayList<Int>(boolKeys.size + other.boolKeys.size)
        val mergedBoolValues = ArrayList<Boolean>(boolKeys.size + other.boolKeys.size)
        sortedMergeBools(boolKeys, boolValues, other.boolKeys, other.boolValues,
            mergedBoolKeys, mergedBoolValues)
        val mergedIntKeys = com.eignex.klause.util.IntArrayList(intKeys.size + other.intKeys.size)
        val mergedIntValues = com.eignex.klause.util.IntArrayList(intKeys.size + other.intKeys.size)
        sortedMergeInts(intKeys, intValues, other.intKeys, other.intValues,
            mergedIntKeys, mergedIntValues)
        // Bound tightenings: take max for mins, min for maxes; on overlap with an
        // exact int pin from either side, drop the bound (the pin subsumes).
        val pinned = HashSet<Int>()
        for (i in 0 until mergedIntKeys.size) pinned.add(mergedIntKeys[i])
        val minMap = HashMap<Int, Int>()
        forEachIntMin { k, v -> if (k !in pinned) minMap[k] = v }
        other.forEachIntMin { k, v ->
            if (k !in pinned) minMap[k] = maxOf(minMap[k] ?: Int.MIN_VALUE, v)
        }
        val maxMap = HashMap<Int, Int>()
        forEachIntMax { k, v -> if (k !in pinned) maxMap[k] = v }
        other.forEachIntMax { k, v ->
            if (k !in pinned) maxMap[k] = minOf(maxMap[k] ?: Int.MAX_VALUE, v)
        }
        val minK = minMap.keys.toIntArray().also { it.sort() }
        val maxK = maxMap.keys.toIntArray().also { it.sort() }
        // Holes: union of (varId, value) pairs, dropping pinned vars.
        val holeSet = HashSet<Long>()
        forEachIntHole { id, v -> if (id !in pinned) holeSet.add((id.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)) }
        other.forEachIntHole { id, v -> if (id !in pinned) holeSet.add((id.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)) }
        val holes = holeSet.toLongArray().also { it.sort() }
        val holeIds = IntArray(holes.size) { (holes[it] ushr 32).toInt() }
        val holeVals = IntArray(holes.size) { holes[it].toInt() }
        return Assumptions(
            boolKeys = mergedBoolKeys.toIntArray(),
            boolValues = BooleanArray(mergedBoolValues.size) { mergedBoolValues[it] },
            intKeys = mergedIntKeys.toIntArray(),
            intValues = mergedIntValues.toIntArray(),
            intMinKeys = minK,
            intMinValues = IntArray(minK.size) { minMap.getValue(minK[it]) },
            intMaxKeys = maxK,
            intMaxValues = IntArray(maxK.size) { maxMap.getValue(maxK[it]) },
            intHoleVarIds = holeIds,
            intHoleValues = holeVals,
        )
    }

    /** Return a fresh [Assumptions] that also pins bool [id] to [value]. Existing
     *  bool pin on [id] is overwritten. */
    fun withBool(id: Int, value: Boolean): Assumptions {
        val idx = boolKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = boolValues.copyOf(); nv[idx] = value
            Assumptions(boolKeys, nv, intKeys, intValues, intMinKeys, intMinValues, intMaxKeys, intMaxValues, intHoleVarIds, intHoleValues)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(boolKeys.size + 1)
            val nv = BooleanArray(boolKeys.size + 1)
            boolKeys.copyInto(nk, 0, 0, insert)
            boolValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            boolKeys.copyInto(nk, insert + 1, insert)
            boolValues.copyInto(nv, insert + 1, insert)
            Assumptions(nk, nv, intKeys, intValues, intMinKeys, intMinValues, intMaxKeys, intMaxValues, intHoleVarIds, intHoleValues)
        }
    }

    /** Return a fresh [Assumptions] that also pins int [id] to [value]. Existing
     *  int pin on [id] is overwritten; any prior bound tightening on [id] is dropped
     *  since the exact pin subsumes it. */
    fun withInt(id: Int, value: Int): Assumptions {
        val minIdx = intMinKeys.binarySearchInt(id)
        val maxIdx = intMaxKeys.binarySearchInt(id)
        val newMinK: IntArray
        val newMinV: IntArray
        if (minIdx >= 0) {
            newMinK = IntArray(intMinKeys.size - 1)
            newMinV = IntArray(intMinKeys.size - 1)
            intMinKeys.copyInto(newMinK, 0, 0, minIdx)
            intMinValues.copyInto(newMinV, 0, 0, minIdx)
            intMinKeys.copyInto(newMinK, minIdx, minIdx + 1)
            intMinValues.copyInto(newMinV, minIdx, minIdx + 1)
        } else { newMinK = intMinKeys; newMinV = intMinValues }
        val newMaxK: IntArray
        val newMaxV: IntArray
        if (maxIdx >= 0) {
            newMaxK = IntArray(intMaxKeys.size - 1)
            newMaxV = IntArray(intMaxKeys.size - 1)
            intMaxKeys.copyInto(newMaxK, 0, 0, maxIdx)
            intMaxValues.copyInto(newMaxV, 0, 0, maxIdx)
            intMaxKeys.copyInto(newMaxK, maxIdx, maxIdx + 1)
            intMaxValues.copyInto(newMaxV, maxIdx, maxIdx + 1)
        } else { newMaxK = intMaxKeys; newMaxV = intMaxValues }
        val idx = intKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intValues.copyOf(); nv[idx] = value
            Assumptions(boolKeys, boolValues, intKeys, nv, newMinK, newMinV, newMaxK, newMaxV, intHoleVarIds, intHoleValues)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intKeys.size + 1)
            val nv = IntArray(intKeys.size + 1)
            intKeys.copyInto(nk, 0, 0, insert)
            intValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            intKeys.copyInto(nk, insert + 1, insert)
            intValues.copyInto(nv, insert + 1, insert)
            Assumptions(boolKeys, boolValues, nk, nv, newMinK, newMinV, newMaxK, newMaxV, intHoleVarIds, intHoleValues)
        }
    }

    /** Return a fresh [Assumptions] with [id]'s lower bound tightened to at least [value].
     *  Used by SAC-at-root to accumulate non-singleton deductions. */
    fun withTightenedMin(id: Int, value: Int): Assumptions {
        val idx = intMinKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intMinValues.copyOf(); nv[idx] = maxOf(nv[idx], value)
            Assumptions(boolKeys, boolValues, intKeys, intValues, intMinKeys, nv, intMaxKeys, intMaxValues, intHoleVarIds, intHoleValues)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intMinKeys.size + 1)
            val nv = IntArray(intMinKeys.size + 1)
            intMinKeys.copyInto(nk, 0, 0, insert)
            intMinValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            intMinKeys.copyInto(nk, insert + 1, insert)
            intMinValues.copyInto(nv, insert + 1, insert)
            Assumptions(boolKeys, boolValues, intKeys, intValues, nk, nv, intMaxKeys, intMaxValues, intHoleVarIds, intHoleValues)
        }
    }

    /** Return a fresh [Assumptions] with [id ≠ value] punched in as an interior-hole
     *  assumption. Idempotent if the hole already exists. Caller is responsible for
     *  ensuring [value] is in the var's current effective domain — the engine will
     *  raise on attempts to exclude a singleton's sole value, mirroring tighten-on-pin. */
    fun withIntHole(id: Int, value: Int): Assumptions {
        var lo = 0
        var hi = intHoleVarIds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val midId = intHoleVarIds[mid]
            val midVal = intHoleValues[mid]
            val cmp = if (midId != id) midId - id else midVal - value
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid
                else -> return this  // already present
            }
        }
        val insert = lo
        val nk = IntArray(intHoleVarIds.size + 1)
        val nv = IntArray(intHoleValues.size + 1)
        intHoleVarIds.copyInto(nk, 0, 0, insert)
        intHoleValues.copyInto(nv, 0, 0, insert)
        nk[insert] = id; nv[insert] = value
        intHoleVarIds.copyInto(nk, insert + 1, insert)
        intHoleValues.copyInto(nv, insert + 1, insert)
        return Assumptions(boolKeys, boolValues, intKeys, intValues,
            intMinKeys, intMinValues, intMaxKeys, intMaxValues, nk, nv)
    }

    /** Return a fresh [Assumptions] with [id]'s upper bound tightened to at most [value]. */
    fun withTightenedMax(id: Int, value: Int): Assumptions {
        val idx = intMaxKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intMaxValues.copyOf(); nv[idx] = minOf(nv[idx], value)
            Assumptions(boolKeys, boolValues, intKeys, intValues, intMinKeys, intMinValues, intMaxKeys, nv, intHoleVarIds, intHoleValues)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intMaxKeys.size + 1)
            val nv = IntArray(intMaxKeys.size + 1)
            intMaxKeys.copyInto(nk, 0, 0, insert)
            intMaxValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id; nv[insert] = value
            intMaxKeys.copyInto(nk, insert + 1, insert)
            intMaxValues.copyInto(nv, insert + 1, insert)
            Assumptions(boolKeys, boolValues, intKeys, intValues, intMinKeys, intMinValues, nk, nv, intHoleVarIds, intHoleValues)
        }
    }

    /** Map view. Allocates a `LinkedHashMap` per access — used by cold paths like
     *  [Problem]'s failed-literal probing and by tests; hot paths should call
     *  [forEachBool] / [boolValueOrNull] instead. */
    val bools: Map<Int, Boolean>
        get() = if (boolKeys.isEmpty()) emptyMap() else
            LinkedHashMap<Int, Boolean>(boolKeys.size).also { m ->
                for (i in boolKeys.indices) m[boolKeys[i]] = boolValues[i]
            }

    /** Map view. Allocates per access — see [bools]. */
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
            intValues.contentEquals(other.intValues) &&
            intMinKeys.contentEquals(other.intMinKeys) &&
            intMinValues.contentEquals(other.intMinValues) &&
            intMaxKeys.contentEquals(other.intMaxKeys) &&
            intMaxValues.contentEquals(other.intMaxValues) &&
            intHoleVarIds.contentEquals(other.intHoleVarIds) &&
            intHoleValues.contentEquals(other.intHoleValues)
    }

    override fun hashCode(): Int {
        var h = boolKeys.contentHashCode()
        h = 31 * h + boolValues.contentHashCode()
        h = 31 * h + intKeys.contentHashCode()
        h = 31 * h + intValues.contentHashCode()
        h = 31 * h + intMinKeys.contentHashCode()
        h = 31 * h + intMinValues.contentHashCode()
        h = 31 * h + intMaxKeys.contentHashCode()
        h = 31 * h + intMaxValues.contentHashCode()
        h = 31 * h + intHoleVarIds.contentHashCode()
        h = 31 * h + intHoleValues.contentHashCode()
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

        /** Map-based factory. Call sites use `Assumptions(bools = mapOf(0 to true))`;
         *  internally normalises to the primitive sorted-array form. */
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
            outK: com.eignex.klause.util.IntArrayList, outV: com.eignex.klause.util.IntArrayList,
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
