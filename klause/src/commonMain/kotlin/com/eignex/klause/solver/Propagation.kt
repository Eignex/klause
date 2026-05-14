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

    data object Unsat : PropagationResult
}

/**
 * Mutable working state passed to [Factor.propagate]. Tracks the currently-known pinned bool
 * values and the (tightened) int domains. Factors mutate it through [pinBool] / [tightenIntMin]
 * / [tightenIntMax] / [setInt]; each mutator returns `false` if the change makes the problem
 * infeasible (conflicting bool pin or empty int domain). The driver also tracks which variables
 * have changed so it can re-enqueue affected factors.
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

    init {
        // Seed with input assumptions.
        seedLoop@ for ((v, b) in assumptions.bools) {
            if (!pinBool(v, b)) { seeded = false; break@seedLoop }
        }
        if (seeded) {
            for ((v, i) in assumptions.ints) {
                if (!setInt(v, i)) { seeded = false; break }
            }
        }
    }

    fun pinBool(v: Int, value: Boolean): Boolean {
        val cur = boolValues[v]
        if (cur != null) return cur == value
        boolValues[v] = value
        dirtyBools.addLast(v)
        return true
    }

    fun tightenIntMin(v: Int, lo: Int): Boolean {
        val d = intDomains[v]
        if (lo <= d.min) return true
        if (lo > d.max) return false
        intDomains[v] = IntDomain(lo, d.max)
        dirtyInts.addLast(v)
        return true
    }

    fun tightenIntMax(v: Int, hi: Int): Boolean {
        val d = intDomains[v]
        if (hi >= d.max) return true
        if (hi < d.min) return false
        intDomains[v] = IntDomain(d.min, hi)
        dirtyInts.addLast(v)
        return true
    }

    fun setInt(v: Int, value: Int): Boolean =
        tightenIntMin(v, value) && tightenIntMax(v, value)

    /** Pop one bool var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyBool(): Int = if (dirtyBools.isEmpty()) -1 else dirtyBools.removeFirst()

    /** Pop one int var that's been dirtied since the last call, or `-1` if none. */
    fun pollDirtyInt(): Int = if (dirtyInts.isEmpty()) -1 else dirtyInts.removeFirst()
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
