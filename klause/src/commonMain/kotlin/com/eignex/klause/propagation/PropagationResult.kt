package com.eignex.klause.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.DeducedRestrictions
import com.eignex.klause.util.EmptyBooleanArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.binarySearchInt
import com.eignex.klause.util.sortedKeys

/**
 * Result of [com.eignex.klause.solver.Problem.propagate]. Either a (possibly empty) set of
 * literals/values forced beyond the input assumptions, or a sound (but incomplete) proof of
 * infeasibility.
 */
sealed interface PropagationResult {

    /**
     * Newly-forced facts beyond the input assumptions. Stored as parallel primitive
     * arrays sorted ascending by key — no autoboxing on iteration or lookup, no
     * `HashMap` allocations when combining with [Assumptions].
     *
     * Primitive APIs ([forEachBool], [forEachInt], [boolValueOrNull], [intValueOrNull])
     * are the hot path; the [bools] / [ints] map views serve cold call-sites (the
     * bake-time failed-literal probing path in `Problem`, tests, debug printing).
     */
    class Implied internal constructor(
        /** Bool var ids in ascending order. */
        val boolKeys: IntArray,
        /** Forced values aligned with [boolKeys]. */
        val boolValues: BooleanArray,
        /** Int var ids forced to a singleton value, ascending. */
        val intKeys: IntArray,
        /** Forced values aligned with [intKeys]. */
        val intValues: LongArray,
        /** Int var ids whose lower bound was tightened (but not yet singleton). Disjoint
         *  from [intKeys]; ascending. Populated by bound-SAC and any future propagation
         *  that wants to expose non-singleton deductions. */
        val intMinKeys: IntArray = EmptyIntArray,
        /** Lower-bound values aligned with [intMinKeys]. */
        val intMinValues: LongArray = EmptyLongArray,
        /** Int var ids whose upper bound was tightened, ascending. */
        val intMaxKeys: IntArray = EmptyIntArray,
        /** Upper-bound values aligned with [intMaxKeys]. */
        val intMaxValues: LongArray = EmptyLongArray,
        /** Interior holes: parallel `(varId, value)` rows in lex order. Each row
         *  encodes `v ≠ value`, with `value` strictly between v's current min and max. */
        val intHoleVarIds: IntArray = EmptyIntArray,
        /** Forbidden values aligned with [intHoleVarIds]. */
        val intHoleValues: LongArray = EmptyLongArray,
        /** Set-restrictions `v ∈ {survivors}` for variables reduced to a sparse survivor set — recorded
         *  instead of one interior hole per excluded value, which is O(span) for a wide-but-sparse domain.
         *  CSR: var `intSetKeys[i]`'s survivors are `intSetValues[intSetOffsets[i] until intSetOffsets[i+1]]`
         *  ([intSetOffsets] size `intSetKeys.size + 1`, or empty when there are none). Disjoint from [intKeys]. */
        val intSetKeys: IntArray = EmptyIntArray,
        /** CSR row offsets into [intSetValues]; size `intSetKeys.size + 1`, or empty when there are none. */
        val intSetOffsets: IntArray = EmptyIntArray,
        /** Concatenated ascending survivor values, sliced per variable by [intSetOffsets]. */
        val intSetValues: LongArray = EmptyLongArray,
    ) : PropagationResult {

        /** True iff nothing was forced. */
        val isEmpty: Boolean get() = boolKeys.isEmpty() && intKeys.isEmpty()

        /** Number of forced Boolean variables. */
        val numBools: Int get() = boolKeys.size

        /** Number of forced (singleton) integer variables. */
        val numInts: Int get() = intKeys.size

        /** Forced value for bool [id], or null if not implied. */
        fun boolValueOrNull(id: Int): Boolean? {
            val idx = boolKeys.binarySearchInt(id)
            return if (idx >= 0) boolValues[idx] else null
        }

        /** Forced value for int [id], or null if not implied. */
        fun intValueOrNull(id: Int): Long? {
            val idx = intKeys.binarySearchInt(id)
            return if (idx >= 0) intValues[idx] else null
        }

        /** Tightened lower bound for int [id], or null if none. */
        fun intMinOrNullCompat(id: Int): Long? {
            val idx = intMinKeys.binarySearchInt(id)
            return if (idx >= 0) intMinValues[idx] else null
        }

        /** Tightened upper bound for int [id], or null if none. */
        fun intMaxOrNullCompat(id: Int): Long? {
            val idx = intMaxKeys.binarySearchInt(id)
            return if (idx >= 0) intMaxValues[idx] else null
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

        /** Invoke [action] for each forced Boolean `(id, value)`. */
        inline fun forEachBool(action: (id: Int, value: Boolean) -> Unit) {
            for (i in boolKeys.indices) action(boolKeys[i], boolValues[i])
        }

        /** Invoke [action] for each forced integer `(id, value)`. */
        inline fun forEachInt(action: (id: Int, value: Long) -> Unit) {
            for (i in intKeys.indices) action(intKeys[i], intValues[i])
        }

        /** Invoke [action] for each survivor-set restriction `(id, sortedSurvivors)` — the compact
         *  form of a wide-but-sparse reduction. The survivors are the exact surviving values in
         *  ascending order; callers restrict the variable's domain to them. */
        inline fun forEachIntSet(action: (id: Int, survivors: LongArray) -> Unit) {
            for (i in intSetKeys.indices) {
                action(intSetKeys[i], intSetValues.copyOfRange(intSetOffsets[i], intSetOffsets[i + 1]))
            }
        }

        /** Reinterpret this implied set as an [Assumptions].
         *  Both share the same key-sorted parallel-array layout, so the conversion is
         *  three [copyOf] calls (one per primitive array) — no rebuild, no boxing. */
        fun toAssumptions(): Assumptions = Assumptions(
            boolKeys = boolKeys.copyOf(),
            boolValues = boolValues.copyOf(),
            intKeys = intKeys.copyOf(),
            intValues = intValues.copyOf(),
            deductions = DeducedRestrictions(
                intMinKeys = intMinKeys.copyOf(),
                intMinValues = intMinValues.copyOf(),
                intMaxKeys = intMaxKeys.copyOf(),
                intMaxValues = intMaxValues.copyOf(),
                intHoleVarIds = intHoleVarIds.copyOf(),
                intHoleValues = intHoleValues.copyOf(),
                intSetKeys = intSetKeys.copyOf(),
                intSetOffsets = intSetOffsets.copyOf(),
                intSetValues = intSetValues.copyOf(),
            ),
        )

        /** Map view. Allocates a `LinkedHashMap` per access — used by cold paths like
         *  failed-literal probing in `Problem` and by tests; hot paths should use
         *  [forEachBool] / [boolValueOrNull] instead. */
        val bools: Map<Int, Boolean>
            get() = if (boolKeys.isEmpty()) {
                emptyMap()
            } else {
                LinkedHashMap<Int, Boolean>(boolKeys.size).also { m ->
                    for (i in boolKeys.indices) m[boolKeys[i]] = boolValues[i]
                }
            }

        /** Map view. See [bools]. */
        val ints: Map<Int, Long>
            get() = if (intKeys.isEmpty()) {
                emptyMap()
            } else {
                LinkedHashMap<Int, Long>(intKeys.size).also { m ->
                    for (i in intKeys.indices) m[intKeys[i]] = intValues[i]
                }
            }

        override fun equals(other: Any?): Boolean {
            if (other !is Implied) return false
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
            append("Implied(bools={")
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

        /** Union this implied set with [other] by replaying everything from [other] over this base.
         *  Bound tightenings take the tighter value; holes union; a variable pinned in the union is
         *  dropped from the bound and hole sets. Used by root-bake probing to accumulate deductions. */
        fun merge(other: Implied): Implied {
            val bools = MutableIntObjectMap<Boolean>()
            forEachBool { k, v -> bools.put(k, v) }
            other.forEachBool { k, v -> bools.put(k, v) }
            val ints = MutableIntLongMap()
            forEachInt { k, v -> ints.put(k, v) }
            other.forEachInt { k, v -> ints.put(k, v) }
            val mins = MutableIntLongMap()
            forEachIntMin { k, v -> mins.put(k, v) }
            other.forEachIntMin { k, v -> mins.put(k, maxOf(mins.getOrDefault(k, Long.MIN_VALUE), v)) }
            val maxes = MutableIntLongMap()
            forEachIntMax { k, v -> maxes.put(k, v) }
            other.forEachIntMax { k, v -> maxes.put(k, minOf(maxes.getOrDefault(k, Long.MAX_VALUE), v)) }
            val holes = HashMap<Int, HashSet<Long>>()
            forEachIntHole { id, v -> holes.getOrPut(id) { HashSet() }.add(v) }
            other.forEachIntHole { id, v -> holes.getOrPut(id) { HashSet() }.add(v) }
            // Set-restrictions accumulate by INTERSECTION: v ∈ S and v ∈ T both hold, so v ∈ S ∩ T.
            // A var restricted on only one side keeps that side's survivors; an empty intersection is
            // left as-is (an empty survivor set signals infeasibility on apply, exactly as a crossed
            // min/max bound already does). Preserving these is why the PR #958 wide-sparse fold isn't
            // silently discarded when root-probing seeds a merge.
            val sets = HashMap<Int, LongArray>()
            forEachIntSet { id, s -> sets[id] = s }
            other.forEachIntSet { id, t ->
                val existing = sets[id]
                sets[id] = if (existing == null) t else intersectSorted(existing, t)
            }
            ints.forEach { k, _ ->
                mins.remove(k)
                maxes.remove(k)
                holes.remove(k)
                sets.remove(k)
            }
            return build(bools, ints, mins, maxes, holes, sets)
        }

        /** This implied set with int [v]'s lower bound raised to at least [newMin]. */
        fun withMin(v: Int, newMin: Long): Implied {
            val mins = MutableIntLongMap()
            forEachIntMin { k, vv -> mins.put(k, vv) }
            mins.put(v, maxOf(mins.getOrDefault(v, Long.MIN_VALUE), newMin))
            val maxes = MutableIntLongMap()
            forEachIntMax { k, vv -> maxes.put(k, vv) }
            return build(boolMap(), intMap(), mins, maxes, holeSet(), setMap())
        }

        /** This implied set with int [v]'s upper bound lowered to at most [newMax]. */
        fun withMax(v: Int, newMax: Long): Implied {
            val mins = MutableIntLongMap()
            forEachIntMin { k, vv -> mins.put(k, vv) }
            val maxes = MutableIntLongMap()
            forEachIntMax { k, vv -> maxes.put(k, vv) }
            maxes.put(v, minOf(maxes.getOrDefault(v, Long.MAX_VALUE), newMax))
            return build(boolMap(), intMap(), mins, maxes, holeSet(), setMap())
        }

        /** This implied set with interior [value] excluded from int [v]'s domain (`v ≠ value`). */
        fun withHole(v: Int, value: Long): Implied {
            val mins = MutableIntLongMap()
            forEachIntMin { k, vv -> mins.put(k, vv) }
            val maxes = MutableIntLongMap()
            forEachIntMax { k, vv -> maxes.put(k, vv) }
            val holes = holeSet()
            holes.getOrPut(v) { HashSet() }.add(value)
            return build(boolMap(), intMap(), mins, maxes, holes, setMap())
        }

        private fun boolMap(): MutableIntObjectMap<Boolean> {
            val m = MutableIntObjectMap<Boolean>()
            forEachBool { k, v -> m.put(k, v) }
            return m
        }

        private fun intMap(): MutableIntLongMap {
            val m = MutableIntLongMap()
            forEachInt { k, v -> m.put(k, v) }
            return m
        }

        private fun holeSet(): HashMap<Int, HashSet<Long>> {
            val holes = HashMap<Int, HashSet<Long>>()
            forEachIntHole { id, v -> holes.getOrPut(id) { HashSet() }.add(v) }
            return holes
        }

        /** This set's per-variable survivor sets (var → ascending survivors), for threading through
         *  [build] unchanged in the single-set [withMin] / [withMax] / [withHole] paths. */
        private fun setMap(): HashMap<Int, LongArray> {
            val m = HashMap<Int, LongArray>()
            forEachIntSet { id, survivors -> m[id] = survivors }
            return m
        }

        /** Intersection of two ascending survivor arrays (two-pointer); result stays ascending. */
        private fun intersectSorted(a: LongArray, b: LongArray): LongArray {
            val out = LongArrayList(minOf(a.size, b.size))
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] < b[j] -> i++

                    a[i] > b[j] -> j++

                    else -> {
                        out.add(a[i])
                        i++
                        j++
                    }
                }
            }
            return out.toLongArray()
        }

        /** Shared [PropagationResult] instances. */
        companion object {
            /** Materialise an [Implied] from the accumulation maps used by [merge] / [withMin] / [withMax] /
             *  [withHole], emitting the key-sorted parallel arrays the constructor expects. [holes] maps a
             *  variable to its set of forbidden values (emitted as `(id, value)` rows sorted by
             *  `(id, value)`); [sets] maps a variable to its ascending survivor values, emitted as the CSR
             *  [intSetKeys]/[intSetOffsets]/[intSetValues] (empty when there are none). */
            private fun build(
                bools: MutableIntObjectMap<Boolean>,
                ints: MutableIntLongMap,
                mins: MutableIntLongMap,
                maxes: MutableIntLongMap,
                holes: Map<Int, HashSet<Long>>,
                sets: Map<Int, LongArray> = emptyMap(),
            ): Implied {
                val bKeys = bools.sortedKeys()
                val iKeys = ints.sortedKeys()
                val minK = mins.sortedKeys()
                val maxK = maxes.sortedKeys()
                val holeK = holes.keys.filter { holes.getValue(it).isNotEmpty() }.toIntArray().also { it.sort() }
                val setK = sets.keys.toIntArray().also { it.sort() }
                if (bKeys.isEmpty() && iKeys.isEmpty() && minK.isEmpty() &&
                    maxK.isEmpty() && holeK.isEmpty() && setK.isEmpty()
                ) {
                    return EMPTY
                }
                val holeCount = holeK.sumOf { holes.getValue(it).size }
                val holeIds = IntArray(holeCount)
                val holeVals = LongArray(holeCount)
                var hw = 0
                for (id in holeK) {
                    for (v in holes.getValue(id).toLongArray().also { it.sort() }) {
                        holeIds[hw] = id
                        holeVals[hw] = v
                        hw++
                    }
                }
                val setOffsets = IntArray(setK.size + 1)
                for (i in setK.indices) setOffsets[i + 1] = setOffsets[i] + sets.getValue(setK[i]).size
                val setVals = LongArray(setOffsets[setK.size])
                var w = 0
                for (k in setK) for (sv in sets.getValue(k)) setVals[w++] = sv
                return Implied(
                    boolKeys = bKeys,
                    boolValues = BooleanArray(bKeys.size) { bools.getValue(bKeys[it]) },
                    intKeys = iKeys,
                    intValues = LongArray(iKeys.size) { ints.getOrDefault(iKeys[it], 0L) },
                    intMinKeys = minK,
                    intMinValues = LongArray(minK.size) { mins.getOrDefault(minK[it], 0L) },
                    intMaxKeys = maxK,
                    intMaxValues = LongArray(maxK.size) { maxes.getOrDefault(maxK[it], 0L) },
                    intHoleVarIds = if (holeCount == 0) EmptyIntArray else holeIds,
                    intHoleValues = if (holeCount == 0) EmptyLongArray else holeVals,
                    intSetKeys = if (setK.isEmpty()) EmptyIntArray else setK,
                    intSetOffsets = if (setK.isEmpty()) EmptyIntArray else setOffsets,
                    intSetValues = if (setK.isEmpty()) EmptyLongArray else setVals,
                )
            }

            /** The empty implied set (nothing forced). */
            val EMPTY: Implied = Implied(EmptyIntArray, EmptyBooleanArray, EmptyIntArray, EmptyLongArray)

            /** Map-based factory. Call sites use `Implied(bools, ints)`; the constructor
             *  normalises to the primitive sorted-array form. Optional bound-tightening
             *  args support SAC-at-root and any future producer of non-singleton deductions. */
            operator fun invoke(
                bools: Map<Int, Boolean> = emptyMap(),
                ints: Map<Int, Long> = emptyMap(),
                intMinKeys: IntArray = EmptyIntArray,
                intMinValues: LongArray = EmptyLongArray,
                intMaxKeys: IntArray = EmptyIntArray,
                intMaxValues: LongArray = EmptyLongArray,
                intHoleVarIds: IntArray = EmptyIntArray,
                intHoleValues: LongArray = EmptyLongArray,
                intSetKeys: IntArray = EmptyIntArray,
                intSetOffsets: IntArray = EmptyIntArray,
                intSetValues: LongArray = EmptyLongArray,
            ): Implied {
                if (bools.isEmpty() && ints.isEmpty() &&
                    intMinKeys.isEmpty() && intMaxKeys.isEmpty() && intHoleVarIds.isEmpty() && intSetKeys.isEmpty()
                ) {
                    return EMPTY
                }
                val bKeys = bools.keys.toIntArray().also { it.sort() }
                val bVals = BooleanArray(bKeys.size) { bools.getValue(bKeys[it]) }
                val iKeys = ints.keys.toIntArray().also { it.sort() }
                val iVals = LongArray(iKeys.size) { ints.getValue(iKeys[it]) }
                return Implied(
                    bKeys, bVals, iKeys, iVals,
                    intMinKeys, intMinValues, intMaxKeys, intMaxValues,
                    intHoleVarIds, intHoleValues,
                    intSetKeys, intSetOffsets, intSetValues,
                )
            }
        }
    }

    /**
     * Sound, incomplete proof of infeasibility.
     *
     *  - [conflictLevels] is the set of *decision levels* involved in the conflict. For a
     *    [PropagationSession], `session.pinBool(v, value)` lives at the level it was pushed
     *    at; `seed` assigns levels `1..|assumptions|` in iteration order. Level 0 is never
     *    in the set — it represents the problem-constraint phase, not a decision.
     *  - [conflictBools] / [conflictInts] are the decision variables at those levels. They
     *    are derived from [conflictLevels] for convenience; CSP-style DFS samplers typically
     *    read [conflictLevels] directly to compute their backjump target.
     *  - [conflictFactors] is the set of [com.eignex.klause.solver.Problem.factors] ids that
     *    derived the contradiction. Currently populated only with the *single* factor that
     *    returned `false` from `propagate` — sound but minimal in the trivial sense. Full
     *    propagation-graph attribution (every factor whose firing contributed to the
     *    failing factor's premises) requires a reason trail and lands with LCG-style clause
     *    learning. Empty when the contradiction came from a seed assumption check.
     *
     *  The conflict subset is jointly unsatisfiable but not guaranteed minimal — callers must
     *  not assume minimality. An empty result means the contradiction was implied by problem
     *  constraints alone (no input was load-bearing).
     */
    // Conflict id/level sets are primitive IntArrays, not Set<Int>: they're produced once per
    // conflict (a hot path on conflict-heavy instances) and consumers only iterate / membership-
    // test / `isEmpty` them — all of which work allocation-free on IntArray. Producers dedup via
    // a primitive IntHashSet before materializing. Equality is by reference (no consumer compares
    // a whole Unsat by value), so the data-class default is fine.
    @ConsistentCopyVisibility
    data class Unsat internal constructor(
        val conflictBools: IntArray = EmptyIntArray,
        val conflictInts: IntArray = EmptyIntArray,
        val conflictLevels: IntArray = EmptyIntArray,
        val conflictFactors: IntArray = EmptyIntArray,
        /**
         * First-UIP analysis result captured before state revert, or `null` when the
         * conflict happened at level 0 (no learning possible), inside an assumption
         * seed (no failing factor to seed from), or in a backend that doesn't run the
         * analyzer. Engines that support non-chronological backjump (CDB) read
         * `learnedClause` to pick a [ConflictAnalyzer.AnalysisResult.Learned.backjumpLevel].
         */
        internal val learnedClause: ConflictAnalyzer.AnalysisResult? = null,
    ) : PropagationResult
}
