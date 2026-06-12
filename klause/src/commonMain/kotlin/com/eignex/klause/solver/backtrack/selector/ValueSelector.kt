package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntHashSet
import kotlin.math.ln
import kotlin.random.Random

/**
 * Picks the order of values to try for a chosen variable. Returns a `Sequence` so iteration
 * is lazy — for bool vars the sequence is at most 2 elements; for int vars at most
 * `domain.size`. The engine pops each yielded value into the session in order; on conflict
 * it advances to the next.
 *
 * For bool vars, the int values are `0` (false) and `1` (true).
 *
 * Notification hooks parallel [VariableSelector]'s, scoped to the (var, value) pair
 * that the engine actually attempted. Impact-based value selection and solution-guided
 * heuristics consume these.
 */
interface ValueSelector {
    /** Candidate values for `varRef`, yielded in trial order. */
    fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Int>

    /** Hook: a conflict involved `varRef` taking [value]. */
    fun onConflict(varRef: VarRef, value: Int) {}

    /** Hook: `varRef` was committed to [value]. */
    fun onCommit(varRef: VarRef, value: Int) {}

    /** Hook: the search restarted. */
    fun onRestart() {}

    /** Called once per SAT leaf reached by the search. Solution-guided heuristics snapshot
     *  the assignment here so they can bias future picks toward it. Default no-op. */
    fun onSolution(snapshot: Sample) {}
}

/** Present domain values ordered by distance from [center] (ties prefer the upper value), skipping
 *  holes via the `in d` membership check. The first value drives [IndomainMiddle]/[IndomainMedian]'s
 *  int bound split; the tail keeps the sequence complete for any consumer that enumerates past it. */
internal fun centeredDomainValues(d: IntDomain, center: Int): Sequence<Int> = sequence {
    if (center in d) yield(center)
    var off = 1
    while (center - off >= d.min || center + off <= d.max) {
        if (center + off <= d.max && (center + off) in d) yield(center + off)
        if (center - off >= d.min && (center - off) in d) yield(center - off)
        off++
    }
}

/**
 * Shared probing core for [Impact] and [MaxSd]. For each candidate value of `varRef`:
 *   - push a real propagation pin via [PropagationSession.pinBool] / `pinInt`;
 *   - score by the log of the remaining-domain product;
 *   - revert with [PropagationSession.popLast] (Unsat probes self-revert, are dropped).
 *
 * [ascending] = `true` orders smallest-residual first (Impact); `false` orders largest-
 * residual first (MaxSd). For int domains larger than [maxProbes], a random subset is
 * probed; the un-probed remainder is appended at the end so DFS coverage is preserved.
 */
internal fun probeAndOrder(
    session: PropagationSession,
    varRef: VarRef,
    rng: Random,
    maxProbes: Int,
    ascending: Boolean,
): Sequence<Int> {
    val candidates: IntArray = when (varRef) {
        is VarRef.Bool -> intArrayOf(0, 1)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            if (d.size <= maxProbes) {
                IntArray(d.size) { d.valueAt(it) }
            } else {
                val seen = IntHashSet(maxProbes * 2)
                val sample = IntArray(maxProbes)
                var i = 0
                var guard = 0
                while (i < maxProbes && guard < maxProbes * 8) {
                    val candidate = d.valueAt(rng.nextInt(d.size))
                    if (seen.add(candidate)) {
                        sample[i] = candidate
                        i++
                    }
                    guard++
                }
                if (i < maxProbes) sample.copyOf(i) else sample
            }
        }
    }
    val scored = ArrayList<Pair<Int, Double>>(candidates.size)
    for (v in candidates) {
        val r = when (varRef) {
            is VarRef.Bool -> session.pinBool(varRef.varId, v != 0)
            is VarRef.IntVar -> session.pinInt(varRef.varId, v)
        }
        if (r is Unsat) continue
        val post = logRemainingDomainProduct(session)
        session.popLast()
        scored.add(v to post)
    }
    if (ascending) scored.sortBy { it.second } else scored.sortByDescending { it.second }
    if (varRef is VarRef.IntVar) {
        val d = session.intDomain(varRef.varId)
        if (candidates.size < d.size) {
            val probed = IntHashSet(candidates.size * 2).apply {
                for ((p, _) in scored) add(p)
                for (c in candidates) add(c)
            }
            val ordered = scored.asSequence().map { it.first }
            return ordered + sequence { d.forEach { if (it !in probed) yield(it) } }
        }
    }
    return scored.asSequence().map { it.first }
}

/** Sum of `ln(size)` over every unpinned variable (bools count as `ln 2` when free). Log-
 *  space to dodge `Double` overflow on problems with hundreds of free vars. */
internal fun logRemainingDomainProduct(session: PropagationSession): Double {
    var s = 0.0
    val p = session.problem
    val ln2 = ln(2.0)
    for (v in 0 until p.numBoolVars) if (session.boolValue(v) == null) s += ln2
    for (v in 0 until p.numIntVars) {
        val sz = session.intDomain(v).size
        if (sz > 1) s += ln(sz.toDouble())
    }
    return s
}

/** Ascending sequence of all values in [d], skipping any holes. Materialises lazily so
 *  the engine can early-exit before enumerating the full domain on a backtrack. */
internal fun domainValuesAscending(d: IntDomain): Sequence<Int> = sequence { d.forEach { yield(it) } }

/** Descending sequence; same skip-holes semantics. */
internal fun domainValuesDescending(d: IntDomain): Sequence<Int> = sequence {
    // Backwards walk: iterate from max down to min, skip holes via membership check.
    // For sparse domains this is O((max - min) + holes); for contiguous it's O(span).
    for (v in d.max downTo d.min) if (v in d) yield(v)
}
