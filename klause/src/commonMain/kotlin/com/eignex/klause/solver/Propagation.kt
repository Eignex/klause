package com.eignex.klause.solver

/**
 * Result of [Problem.propagate]. Either a (possibly empty) set of literals/values forced beyond
 * the input assumptions, or a sound (but incomplete) proof of infeasibility.
 */
sealed interface PropagationResult {
    /** [bools] and [ints] are disjoint from the input assumptions: only newly-forced facts. */
    data class Implied(val bools: Map<Int, Boolean>, val ints: Map<Int, Int>) : PropagationResult {
        val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    }

    /**
     * Sound, incomplete proof of infeasibility. [conflictBools] / [conflictInts] are a
     * subset of the *input* [Assumptions] (or empty when the contradiction was implied
     * solely by the problem's constraints with no caller pins involved). The subset is
     * jointly unsatisfiable but not guaranteed minimal — implementations are encouraged
     * to shrink it when cheap, callers must not assume minimality.
     */
    data class Unsat(
        val conflictBools: Set<Int> = emptySet(),
        val conflictInts: Set<Int> = emptySet(),
    ) : PropagationResult
}

/**
 * Mutable working state passed to [Factor.propagate]. Tracks the currently-known pinned bool
 * values and the (tightened) int domains. Factors mutate it through [pinBool] / [tightenIntMin]
 * / [tightenIntMax] / [setInt]; each mutator returns `false` if the change makes the problem
 * infeasible (conflicting bool pin or empty int domain). The driver also tracks which variables
 * have changed so it can re-enqueue affected factors.
 *
 * Each input-assumption variable is allocated a unique bit in a reason bitmap. Every derived
 * pin / domain tightening carries a bitmap-OR of the input reasons whose joint state implied
 * it — so on infeasibility the driver can read back the subset of inputs responsible.
 *
 * The driver sets [currentReason] to the union of input reasons of every variable the
 * current factor reads before invoking [Factor.propagate]; pin / tighten calls without an
 * explicit reason inherit it (over-conservative — they may include vars the factor didn't
 * actually use, but never under-include).
 */
class PropagationState(
    val problem: Problem,
    assumptions: Assumptions,
) {
    /** Per-bool current pin; `null` means unassigned. */
    val boolValues: Array<Boolean?> = arrayOfNulls(problem.numBoolVars)

    /** Per-int current domain (copy of [Problem.intDomains], narrowed as propagation proceeds). */
    val intDomains: Array<IntDomain> = Array(problem.numIntVars) { problem.intDomains[it] }

    /** Vars whose pin/domain changed since the driver last drained them. */
    private val dirtyBools: ArrayDeque<Int> = ArrayDeque()
    private val dirtyInts: ArrayDeque<Int> = ArrayDeque()

    /** False iff seeding the assumptions themselves already produced a contradiction. */
    var seeded: Boolean = true
        private set

    // Reason-bitmap plumbing. ----------------------------------------------------------------

    /** Number of input-assumption variables (bools + ints). One bit each in the reason bitmap. */
    internal val numInputs: Int = assumptions.bools.size + assumptions.ints.size
    internal val reasonWords: Int = (numInputs + 63) ushr 6

    /** Bit index for each input-assumption bool var; `-1` if the var isn't an input. */
    private val boolInputBit: IntArray = IntArray(problem.numBoolVars) { -1 }
    private val intInputBit: IntArray = IntArray(problem.numIntVars) { -1 }
    /** Inverse: bit index → originating var id; companion array distinguishes kind. */
    private val bitToBoolVar: IntArray = IntArray(numInputs) { -1 }
    private val bitToIntVar: IntArray = IntArray(numInputs) { -1 }

    /** Per-var reason bitmap. `null` = no input dependency (pin derived from constraints alone). */
    private val boolReasons: Array<LongArray?> = arrayOfNulls(problem.numBoolVars)
    private val intReasons: Array<LongArray?> = arrayOfNulls(problem.numIntVars)

    /**
     * Default reason used by [pinBool] / [tightenIntMin] / [tightenIntMax] / [setInt] when the
     * caller doesn't pass one explicitly. The driver sets this to the union of input reasons
     * of every var the current factor reads before invoking [Factor.propagate].
     */
    internal var currentReason: LongArray? = null

    /**
     * Captured on the first detected contradiction; the driver reads this when forming the
     * [PropagationResult.Unsat] return value. Cleared by the driver between successful factor
     * invocations.
     */
    internal var conflictReason: LongArray? = null

    init {
        var bit = 0
        for ((v, _) in assumptions.bools) {
            boolInputBit[v] = bit; bitToBoolVar[bit] = v; bit++
        }
        for ((v, _) in assumptions.ints) {
            intInputBit[v] = bit; bitToIntVar[bit] = v; bit++
        }
        seedLoop@ for ((v, b) in assumptions.bools) {
            if (!pinBoolWithReason(v, b, singletonReason(boolInputBit[v]))) {
                seeded = false; break@seedLoop
            }
        }
        if (seeded) {
            for ((v, i) in assumptions.ints) {
                if (!setIntWithReason(v, i, singletonReason(intInputBit[v]))) {
                    seeded = false; break
                }
            }
        }
    }

    fun pinBool(v: Int, value: Boolean): Boolean = pinBoolWithReason(v, value, currentReason)

    fun pinBoolWithReason(v: Int, value: Boolean, reason: LongArray?): Boolean {
        val cur = boolValues[v]
        if (cur != null) {
            if (cur == value) return true
            conflictReason = unionReasons(boolReasons[v], reason)
            return false
        }
        boolValues[v] = value
        boolReasons[v] = reason?.copyOf()
        dirtyBools.addLast(v)
        return true
    }

    fun tightenIntMin(v: Int, lo: Int): Boolean = tightenIntMinWithReason(v, lo, currentReason)

    fun tightenIntMinWithReason(v: Int, lo: Int, reason: LongArray?): Boolean {
        val d = intDomains[v]
        if (lo <= d.min) return true
        if (lo > d.max) {
            conflictReason = unionReasons(intReasons[v], reason)
            return false
        }
        intDomains[v] = IntDomain(lo, d.max)
        intReasons[v] = unionReasons(intReasons[v], reason)
        dirtyInts.addLast(v)
        return true
    }

    fun tightenIntMax(v: Int, hi: Int): Boolean = tightenIntMaxWithReason(v, hi, currentReason)

    fun tightenIntMaxWithReason(v: Int, hi: Int, reason: LongArray?): Boolean {
        val d = intDomains[v]
        if (hi >= d.max) return true
        if (hi < d.min) {
            conflictReason = unionReasons(intReasons[v], reason)
            return false
        }
        intDomains[v] = IntDomain(d.min, hi)
        intReasons[v] = unionReasons(intReasons[v], reason)
        dirtyInts.addLast(v)
        return true
    }

    fun setInt(v: Int, value: Int): Boolean = setIntWithReason(v, value, currentReason)

    fun setIntWithReason(v: Int, value: Int, reason: LongArray?): Boolean =
        tightenIntMinWithReason(v, value, reason) && tightenIntMaxWithReason(v, value, reason)

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()

    /** Reason bitmap for a pinned bool, or `null` if unpinned / no input deps. */
    fun reasonForBool(v: Int): LongArray? = boolReasons[v]

    /** Reason bitmap for the current int domain, or `null` if untouched / no input deps. */
    fun reasonForInt(v: Int): LongArray? = intReasons[v]

    /** Union of input reasons of every variable in [boolVars] / [intVars]. */
    fun reasonForVars(boolVars: IntArray, intVars: IntArray): LongArray? {
        var acc: LongArray? = null
        for (v in boolVars) acc = unionReasons(acc, boolReasons[v])
        for (v in intVars) acc = unionReasons(acc, intReasons[v])
        return acc
    }

    internal fun unionReasons(a: LongArray?, b: LongArray?): LongArray? {
        if (reasonWords == 0) return null
        if (a == null) return b?.copyOf()
        if (b == null) return a.copyOf()
        val r = LongArray(reasonWords)
        for (i in 0 until reasonWords) r[i] = a[i] or b[i]
        return r
    }

    private fun singletonReason(bit: Int): LongArray? {
        if (reasonWords == 0 || bit < 0) return null
        val r = LongArray(reasonWords)
        r[bit ushr 6] = 1L shl (bit and 63)
        return r
    }

    /** Decode the bool-input vars set in [reason]. */
    internal fun extractConflictBools(reason: LongArray?): Set<Int> {
        if (reason == null || numInputs == 0) return emptySet()
        val out = HashSet<Int>()
        for (bit in 0 until numInputs) {
            if ((reason[bit ushr 6] ushr (bit and 63)) and 1L == 1L) {
                val bv = bitToBoolVar[bit]
                if (bv >= 0) out.add(bv)
            }
        }
        return out
    }

    /** Decode the int-input vars set in [reason]. */
    internal fun extractConflictInts(reason: LongArray?): Set<Int> {
        if (reason == null || numInputs == 0) return emptySet()
        val out = HashSet<Int>()
        for (bit in 0 until numInputs) {
            if ((reason[bit ushr 6] ushr (bit and 63)) and 1L == 1L) {
                val iv = bitToIntVar[bit]
                if (iv >= 0) out.add(iv)
            }
        }
        return out
    }
}

/** floor(a / b) with correct handling of negative operands. */
internal fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

/** ceil(a / b) with correct handling of negative operands. */
internal fun ceilDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) >= 0L) q + 1 else q
}
