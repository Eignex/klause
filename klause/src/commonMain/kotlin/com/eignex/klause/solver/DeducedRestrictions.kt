package com.eignex.klause.solver

import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.binarySearchInt
import com.eignex.klause.util.toSortedIntArray
import com.eignex.klause.util.toSortedLongArray

/**
 * The presolve-derived narrowings that ride alongside an [Assumptions]' exact pins: non-singleton
 * lower/upper bound tightenings, interior holes (`v ≠ value`), and compact set-restrictions
 * (`v ∈ {survivors}`). These are produced by SAC-at-root and bake-time probing (see the root baker)
 * and applied at seed time; they never originate from the Session / portfolio pin API
 * ([Assumptions.withBool] / [Assumptions.withInt]), which is why they live in a separate carrier that
 * [Assumptions] holds as a single field.
 *
 * Storage is parallel primitive arrays sorted ascending by key — no autoboxing, binary-search lookup.
 * Every builder returns a fresh instance; the receiver is untouched.
 */
class DeducedRestrictions internal constructor(
    /** Int var ids with an additional `≥ minValue` lower-bound tightening (no exact pin), ascending. */
    val intMinKeys: IntArray = EmptyIntArray,
    /** Lower-bound values aligned with [intMinKeys]. */
    val intMinValues: LongArray = EmptyLongArray,
    /** Int var ids with an additional `≤ maxValue` upper-bound tightening (no exact pin), ascending. */
    val intMaxKeys: IntArray = EmptyIntArray,
    /** Upper-bound values aligned with [intMaxKeys]. */
    val intMaxValues: LongArray = EmptyLongArray,
    /** Interior holes: parallel `(varId, value)` rows, lexicographically sorted by `(varId, value)`;
     *  each row encodes `v ≠ value`. */
    val intHoleVarIds: IntArray = EmptyIntArray,
    /** Forbidden values aligned with [intHoleVarIds]. */
    val intHoleValues: LongArray = EmptyLongArray,
    /** Set-restrictions `v ∈ {survivors}`, var ids ascending. CSR layout: variable `intSetKeys[i]`'s
     *  ascending survivors are `intSetValues[intSetOffsets[i] until intSetOffsets[i + 1]]`.
     *  [intSetOffsets] has size `intSetKeys.size + 1` (or is empty when there are none). */
    val intSetKeys: IntArray = EmptyIntArray,
    /** CSR row offsets into [intSetValues]; size `intSetKeys.size + 1`, or empty when there are none. */
    val intSetOffsets: IntArray = EmptyIntArray,
    /** Concatenated ascending survivor values, sliced per variable by [intSetOffsets]. */
    val intSetValues: LongArray = EmptyLongArray,
) {

    /** True iff no bound tightening, hole, or set-restriction is recorded. */
    val isEmpty: Boolean
        get() = intMinKeys.isEmpty() && intMaxKeys.isEmpty() && intHoleVarIds.isEmpty() && intSetKeys.isEmpty()

    /** Survivors of set-restricted var at index [i] into [intSetKeys], as an ascending array. */
    fun intSetSurvivorsAt(i: Int): LongArray = intSetValues.copyOfRange(intSetOffsets[i], intSetOffsets[i + 1])

    /** Invoke [action] once per set-restricted variable with its ascending survivor array. */
    inline fun forEachIntSet(action: (id: Int, survivors: LongArray) -> Unit) {
        for (i in intSetKeys.indices) action(intSetKeys[i], intSetSurvivorsAt(i))
    }

    /** Invoke [action] for each lower-bound tightening `(id, value)`. */
    inline fun forEachIntMin(action: (id: Int, value: Long) -> Unit) {
        for (i in intMinKeys.indices) action(intMinKeys[i], intMinValues[i])
    }

    /** Invoke [action] for each upper-bound tightening `(id, value)`. */
    inline fun forEachIntMax(action: (id: Int, value: Long) -> Unit) {
        for (i in intMaxKeys.indices) action(intMaxKeys[i], intMaxValues[i])
    }

    /** Invoke [action] for each interior hole `(id, forbiddenValue)`. */
    inline fun forEachIntHole(action: (id: Int, value: Long) -> Unit) {
        for (i in intHoleVarIds.indices) action(intHoleVarIds[i], intHoleValues[i])
    }

    /** Lower-bound tightening for int [id], or `null` if none. */
    fun intMinOrNull(id: Int): Long? {
        val idx = intMinKeys.binarySearchInt(id)
        return if (idx >= 0) intMinValues[idx] else null
    }

    /** Upper-bound tightening for int [id], or `null` if none. */
    fun intMaxOrNull(id: Int): Long? {
        val idx = intMaxKeys.binarySearchInt(id)
        return if (idx >= 0) intMaxValues[idx] else null
    }

    /** Rebuild with a subset of fields replaced; every unspecified field is carried over unchanged. */
    private fun copy(
        intMinKeys: IntArray = this.intMinKeys,
        intMinValues: LongArray = this.intMinValues,
        intMaxKeys: IntArray = this.intMaxKeys,
        intMaxValues: LongArray = this.intMaxValues,
        intHoleVarIds: IntArray = this.intHoleVarIds,
        intHoleValues: LongArray = this.intHoleValues,
        intSetKeys: IntArray = this.intSetKeys,
        intSetOffsets: IntArray = this.intSetOffsets,
        intSetValues: LongArray = this.intSetValues,
    ): DeducedRestrictions = DeducedRestrictions(
        intMinKeys, intMinValues, intMaxKeys, intMaxValues,
        intHoleVarIds, intHoleValues, intSetKeys, intSetOffsets, intSetValues,
    )

    /** Drop any lower/upper bound tightening on [id] (an exact pin on it subsumes the bound), keeping
     *  holes and set-restrictions. Backs [Assumptions.withInt]. */
    fun withoutBounds(id: Int): DeducedRestrictions {
        val minIdx = intMinKeys.binarySearchInt(id)
        val maxIdx = intMaxKeys.binarySearchInt(id)
        if (minIdx < 0 && maxIdx < 0) return this
        val newMinK: IntArray
        val newMinV: LongArray
        if (minIdx >= 0) {
            newMinK = IntArray(intMinKeys.size - 1)
            newMinV = LongArray(intMinKeys.size - 1)
            intMinKeys.copyInto(newMinK, 0, 0, minIdx)
            intMinValues.copyInto(newMinV, 0, 0, minIdx)
            intMinKeys.copyInto(newMinK, minIdx, minIdx + 1)
            intMinValues.copyInto(newMinV, minIdx, minIdx + 1)
        } else {
            newMinK = intMinKeys
            newMinV = intMinValues
        }
        val newMaxK: IntArray
        val newMaxV: LongArray
        if (maxIdx >= 0) {
            newMaxK = IntArray(intMaxKeys.size - 1)
            newMaxV = LongArray(intMaxKeys.size - 1)
            intMaxKeys.copyInto(newMaxK, 0, 0, maxIdx)
            intMaxValues.copyInto(newMaxV, 0, 0, maxIdx)
            intMaxKeys.copyInto(newMaxK, maxIdx, maxIdx + 1)
            intMaxValues.copyInto(newMaxV, maxIdx, maxIdx + 1)
        } else {
            newMaxK = intMaxKeys
            newMaxV = intMaxValues
        }
        return copy(intMinKeys = newMinK, intMinValues = newMinV, intMaxKeys = newMaxK, intMaxValues = newMaxV)
    }

    /** Drop all set-restrictions, keeping bound tightenings and holes. Dropping a restriction only
     *  loosens the seed, so it is sound; backs the pin-removal helpers in `SatisfyResult`. */
    fun withoutSetRestrictions(): DeducedRestrictions = if (intSetKeys.isEmpty()) {
        this
    } else {
        copy(intSetKeys = EmptyIntArray, intSetOffsets = EmptyIntArray, intSetValues = EmptyLongArray)
    }

    /** Lower bound on [id] raised to at least [value]. */
    fun withTightenedMin(id: Int, value: Long): DeducedRestrictions {
        val idx = intMinKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intMinValues.copyOf()
            nv[idx] = maxOf(nv[idx], value)
            copy(intMinValues = nv)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intMinKeys.size + 1)
            val nv = LongArray(intMinKeys.size + 1)
            intMinKeys.copyInto(nk, 0, 0, insert)
            intMinValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id
            nv[insert] = value
            intMinKeys.copyInto(nk, insert + 1, insert)
            intMinValues.copyInto(nv, insert + 1, insert)
            copy(intMinKeys = nk, intMinValues = nv)
        }
    }

    /** Upper bound on [id] tightened to at most [value]. */
    fun withTightenedMax(id: Int, value: Long): DeducedRestrictions {
        val idx = intMaxKeys.binarySearchInt(id)
        return if (idx >= 0) {
            val nv = intMaxValues.copyOf()
            nv[idx] = minOf(nv[idx], value)
            copy(intMaxValues = nv)
        } else {
            val insert = -(idx + 1)
            val nk = IntArray(intMaxKeys.size + 1)
            val nv = LongArray(intMaxKeys.size + 1)
            intMaxKeys.copyInto(nk, 0, 0, insert)
            intMaxValues.copyInto(nv, 0, 0, insert)
            nk[insert] = id
            nv[insert] = value
            intMaxKeys.copyInto(nk, insert + 1, insert)
            intMaxValues.copyInto(nv, insert + 1, insert)
            copy(intMaxKeys = nk, intMaxValues = nv)
        }
    }

    /** `id ≠ value` punched in as an interior hole. Idempotent if already present. */
    fun withIntHole(id: Int, value: Long): DeducedRestrictions {
        var lo = 0
        var hi = intHoleVarIds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val midId = intHoleVarIds[mid]
            val midVal = intHoleValues[mid]
            val cmp = if (midId != id) midId - id else midVal.compareTo(value)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid
                else -> return this // already present
            }
        }
        val insert = lo
        val nk = IntArray(intHoleVarIds.size + 1)
        val nv = LongArray(intHoleValues.size + 1)
        intHoleVarIds.copyInto(nk, 0, 0, insert)
        intHoleValues.copyInto(nv, 0, 0, insert)
        nk[insert] = id
        nv[insert] = value
        intHoleVarIds.copyInto(nk, insert + 1, insert)
        intHoleValues.copyInto(nv, insert + 1, insert)
        return copy(intHoleVarIds = nk, intHoleValues = nv)
    }

    /**
     * Merge `this` with [other] under the merged exact-pin set [pinned]: mins take the max, maxes take
     * the min, holes union, set-restrictions take [other] on overlap (last-write) — and every deduction
     * whose var is in [pinned] is dropped, since the exact pin subsumes it. Byte-identical to the
     * deduction half of the former `Assumptions.mergedWith`.
     */
    internal fun mergedWith(other: DeducedRestrictions, pinned: IntHashSet): DeducedRestrictions {
        val minMap = MutableIntLongMap()
        forEachIntMin { k, v -> if (k !in pinned) minMap.put(k, v) }
        other.forEachIntMin { k, v ->
            if (k !in pinned) minMap.put(k, maxOf(minMap.getOrDefault(k, Long.MIN_VALUE), v))
        }
        val maxMap = MutableIntLongMap()
        forEachIntMax { k, v -> if (k !in pinned) maxMap.put(k, v) }
        other.forEachIntMax { k, v ->
            if (k !in pinned) maxMap.put(k, minOf(maxMap.getOrDefault(k, Long.MAX_VALUE), v))
        }
        val minKList = IntArrayList(minMap.size)
        minMap.forEach { k, _ -> minKList.add(k) }
        val minK = minKList.toSortedIntArray()
        val maxKList = IntArrayList(maxMap.size)
        maxMap.forEach { k, _ -> maxKList.add(k) }
        val maxK = maxKList.toSortedIntArray()
        // Holes: union of (varId, value) pairs, dropping pinned vars; dedupe per var, flatten sorted.
        val holeMap = LinkedHashMap<Int, LongHashSet>()
        forEachIntHole { id, v -> if (id !in pinned) holeMap.getOrPut(id) { LongHashSet() }.add(v) }
        other.forEachIntHole { id, v -> if (id !in pinned) holeMap.getOrPut(id) { LongHashSet() }.add(v) }
        val holeCount = holeMap.values.sumOf { it.size }
        val holeIds = IntArray(holeCount)
        val holeVals = LongArray(holeCount)
        var holePos = 0
        for (id in holeMap.keys.toIntArray().also { it.sort() }) {
            for (v in holeMap.getValue(id).toSortedLongArray()) {
                holeIds[holePos] = id
                holeVals[holePos] = v
                holePos++
            }
        }
        // Set-restrictions: per-var survivor sets, [other] winning on overlap, dropping pinned vars.
        val setMap = LinkedHashMap<Int, LongArray>()
        forEachIntSet { id, sv -> if (id !in pinned) setMap[id] = sv }
        other.forEachIntSet { id, sv -> if (id !in pinned) setMap[id] = sv }
        val setK = setMap.keys.toIntArray().also { it.sort() }
        val setOffsets = IntArray(setK.size + 1)
        val setVals = LongArrayList(setK.size)
        for (i in setK.indices) {
            for (x in setMap.getValue(setK[i])) setVals.add(x)
            setOffsets[i + 1] = setVals.size
        }
        return DeducedRestrictions(
            intMinKeys = minK,
            intMinValues = LongArray(minK.size) { minMap.getOrDefault(minK[it], 0L) },
            intMaxKeys = maxK,
            intMaxValues = LongArray(maxK.size) { maxMap.getOrDefault(maxK[it], 0L) },
            intHoleVarIds = holeIds,
            intHoleValues = holeVals,
            intSetKeys = setK,
            intSetOffsets = if (setK.isEmpty()) EmptyIntArray else setOffsets,
            intSetValues = setVals.toLongArray(),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DeducedRestrictions) return false
        return intMinKeys.contentEquals(other.intMinKeys) &&
            intMinValues.contentEquals(other.intMinValues) &&
            intMaxKeys.contentEquals(other.intMaxKeys) &&
            intMaxValues.contentEquals(other.intMaxValues) &&
            intHoleVarIds.contentEquals(other.intHoleVarIds) &&
            intHoleValues.contentEquals(other.intHoleValues) &&
            intSetKeys.contentEquals(other.intSetKeys) &&
            intSetOffsets.contentEquals(other.intSetOffsets) &&
            intSetValues.contentEquals(other.intSetValues)
    }

    override fun hashCode(): Int {
        var h = intMinKeys.contentHashCode()
        h = 31 * h + intMinValues.contentHashCode()
        h = 31 * h + intMaxKeys.contentHashCode()
        h = 31 * h + intMaxValues.contentHashCode()
        h = 31 * h + intHoleVarIds.contentHashCode()
        h = 31 * h + intHoleValues.contentHashCode()
        h = 31 * h + intSetKeys.contentHashCode()
        h = 31 * h + intSetOffsets.contentHashCode()
        h = 31 * h + intSetValues.contentHashCode()
        return h
    }

    /** Shared instances. */
    companion object {
        /** No bound tightenings, holes, or set-restrictions. */
        val None: DeducedRestrictions = DeducedRestrictions()
    }
}
