package com.eignex.klause.solver.propagation

import com.eignex.klause.util.binarySearchInt

/**
 * Result of [com.eignex.klause.solver.Problem.propagate]. Either a (possibly empty) set of
 * literals/values forced beyond the input assumptions, or a sound (but incomplete) proof of
 * infeasibility.
 */
sealed interface PropagationResult {

    /**
     * Newly-forced facts beyond the input assumptions. Stored as parallel primitive
     * arrays sorted ascending by key — no autoboxing on iteration or lookup, no
     * `HashMap` allocations when combining with [com.eignex.klause.solver.Assumptions].
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
        val intValues: IntArray,
        /** Int var ids whose lower bound was tightened (but not yet singleton). Disjoint
         *  from [intKeys]; ascending. Populated by bound-SAC and any future propagation
         *  that wants to expose non-singleton deductions. */
        val intMinKeys: IntArray = IntArray(0),
        val intMinValues: IntArray = IntArray(0),
        val intMaxKeys: IntArray = IntArray(0),
        val intMaxValues: IntArray = IntArray(0),
        /** Interior holes: parallel `(varId, value)` rows in lex order. Each row
         *  encodes `v ≠ value`, with `value` strictly between v's current min and max. */
        val intHoleVarIds: IntArray = IntArray(0),
        val intHoleValues: IntArray = IntArray(0),
    ) : PropagationResult {

        val isEmpty: Boolean get() = boolKeys.isEmpty() && intKeys.isEmpty()
        val numBools: Int get() = boolKeys.size
        val numInts: Int get() = intKeys.size

        fun boolValueOrNull(id: Int): Boolean? {
            val idx = boolKeys.binarySearchInt(id)
            return if (idx >= 0) boolValues[idx] else null
        }

        fun intValueOrNull(id: Int): Int? {
            val idx = intKeys.binarySearchInt(id)
            return if (idx >= 0) intValues[idx] else null
        }

        fun intMinOrNullCompat(id: Int): Int? {
            val idx = intMinKeys.binarySearchInt(id)
            return if (idx >= 0) intMinValues[idx] else null
        }

        fun intMaxOrNullCompat(id: Int): Int? {
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

        inline fun forEachBool(action: (id: Int, value: Boolean) -> Unit) {
            for (i in boolKeys.indices) action(boolKeys[i], boolValues[i])
        }

        inline fun forEachInt(action: (id: Int, value: Int) -> Unit) {
            for (i in intKeys.indices) action(intKeys[i], intValues[i])
        }

        /** Reinterpret this implied set as an [com.eignex.klause.solver.Assumptions].
         *  Both share the same key-sorted parallel-array layout, so the conversion is
         *  three [copyOf] calls (one per primitive array) — no rebuild, no boxing. */
        fun toAssumptions(): com.eignex.klause.solver.Assumptions =
            com.eignex.klause.solver.Assumptions(
                boolKeys = boolKeys.copyOf(),
                boolValues = boolValues.copyOf(),
                intKeys = intKeys.copyOf(),
                intValues = intValues.copyOf(),
                intMinKeys = intMinKeys.copyOf(),
                intMinValues = intMinValues.copyOf(),
                intMaxKeys = intMaxKeys.copyOf(),
                intMaxValues = intMaxValues.copyOf(),
                intHoleVarIds = intHoleVarIds.copyOf(),
                intHoleValues = intHoleValues.copyOf(),
            )

        /** Map view. Allocates a `LinkedHashMap` per access — used by cold paths like
         *  failed-literal probing in `Problem` and by tests; hot paths should use
         *  [forEachBool] / [boolValueOrNull] instead. */
        val bools: Map<Int, Boolean>
            get() = if (boolKeys.isEmpty()) emptyMap() else
                LinkedHashMap<Int, Boolean>(boolKeys.size).also { m ->
                    for (i in boolKeys.indices) m[boolKeys[i]] = boolValues[i]
                }

        /** Map view. See [bools]. */
        val ints: Map<Int, Int>
            get() = if (intKeys.isEmpty()) emptyMap() else
                LinkedHashMap<Int, Int>(intKeys.size).also { m ->
                    for (i in intKeys.indices) m[intKeys[i]] = intValues[i]
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
            val Empty: Implied = Implied(IntArray(0), BooleanArray(0), IntArray(0), IntArray(0))

            /** Map-based factory. Call sites use `Implied(bools, ints)`; the constructor
             *  normalises to the primitive sorted-array form. Optional bound-tightening
             *  args support SAC-at-root and any future producer of non-singleton deductions. */
            operator fun invoke(
                bools: Map<Int, Boolean> = emptyMap(),
                ints: Map<Int, Int> = emptyMap(),
                intMinKeys: IntArray = IntArray(0),
                intMinValues: IntArray = IntArray(0),
                intMaxKeys: IntArray = IntArray(0),
                intMaxValues: IntArray = IntArray(0),
                intHoleVarIds: IntArray = IntArray(0),
                intHoleValues: IntArray = IntArray(0),
            ): Implied {
                if (bools.isEmpty() && ints.isEmpty() &&
                    intMinKeys.isEmpty() && intMaxKeys.isEmpty() && intHoleVarIds.isEmpty()) return Empty
                val bKeys = bools.keys.toIntArray().also { it.sort() }
                val bVals = BooleanArray(bKeys.size) { bools.getValue(bKeys[it]) }
                val iKeys = ints.keys.toIntArray().also { it.sort() }
                val iVals = IntArray(iKeys.size) { ints.getValue(iKeys[it]) }
                return Implied(bKeys, bVals, iKeys, iVals,
                    intMinKeys, intMinValues, intMaxKeys, intMaxValues,
                    intHoleVarIds, intHoleValues)
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
    data class Unsat(
        val conflictBools: Set<Int> = emptySet(),
        val conflictInts: Set<Int> = emptySet(),
        val conflictLevels: Set<Int> = emptySet(),
        val conflictFactors: Set<Int> = emptySet(),
        /**
         * First-UIP analysis result captured before state revert, or `null` when the
         * conflict happened at level 0 (no learning possible), inside an assumption
         * seed (no failing factor to seed from), or in a backend that doesn't run the
         * analyzer. Engines that support non-chronological backjump (CDB) read
         * `learnedClause` to pick a [ConflictAnalyzer.AnalysisResult.Learned.backjumpLevel].
         */
        val learnedClause: ConflictAnalyzer.AnalysisResult? = null,
    ) : PropagationResult
}
