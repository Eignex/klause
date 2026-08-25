package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.randomValue
import com.eignex.klause.solver.values
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.LongHashSet
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
    fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long>

    /** A fresh, unshared instance for one solve: stateless selectors return this, stateful ones
     *  rebuild from their config so no per-search state leaks across reuse. */
    fun fresh(): ValueSelector

    /** Hook: a conflict involved `varRef` taking [value]. */
    fun onConflict(varRef: VarRef, value: Long) {}

    /** Hook: `varRef` was committed to [value]. */
    fun onCommit(varRef: VarRef, value: Long) {}

    /** Hook: the search restarted. */
    fun onRestart() {}

    /** Called once per SAT leaf reached by the search. Solution-guided heuristics snapshot
     *  the assignment here so they can bias future picks toward it. Default no-op. */
    fun onSolution(snapshot: Sample) {}
}

/** Floor of `(min(d) + max(d)) / 2` without overflow: `d.max - d.min` wraps on spans wider than
 *  `Long.MAX_VALUE` (e.g. a full-`Long` domain), collapsing the midpoint onto `d.min` and turning
 *  dichotomic search linear. Shift-and-carry stays exact for every bound pair. */
internal fun boundsMidpoint(d: IntDomain): Long = (d.min shr 1) + (d.max shr 1) + (d.min and d.max and 1L)

/** Present domain values ordered by distance from [center] (ties prefer the upper value), skipping
 *  holes via the `in d` membership check. The first value drives [IndomainMiddle]/[IndomainMedian]'s
 *  int bound split; the tail keeps the sequence complete for any consumer that enumerates past it.
 *  Cursor-based rather than `center ± offset`: the offset arithmetic wraps once a cursor would pass
 *  a bound near the ends of the `Long` range, turning the walk into an endless spin. */
internal fun centeredDomainValues(d: IntDomain, center: Long): Sequence<Long> = sequence {
    if (center in d) yield(center)
    var up = center
    var down = center
    while (up < d.max || down > d.min) {
        if (up < d.max) {
            up++
            if (up in d) yield(up)
        }
        if (down > d.min) {
            down--
            if (down in d) yield(down)
        }
    }
}

/** `max(d) - min(d)`, saturated at `Long.MAX_VALUE` instead of wrapping on a full-`Long` span. */
internal fun saturatingSpan(d: IntDomain): Long = (d.max - d.min).let { if (it < 0L) Long.MAX_VALUE else it }

/** `a * b` for non-negative operands, saturated at `Long.MAX_VALUE` instead of wrapping. */
internal fun saturatingMul(a: Long, b: Long): Long = when {
    a == 0L || b == 0L -> 0L
    a > Long.MAX_VALUE / b -> Long.MAX_VALUE
    else -> a * b
}

/**
 * Shared probing core for [Impact] and [MaxSd]. For each candidate value of `varRef`:
 *   - push a real propagation pin via [PropagationSession.probeBool] / `probeInt`;
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
): Sequence<Long> {
    val candidates: LongArray = when (varRef) {
        is VarRef.Bool -> longArrayOf(0L, 1L)

        is VarRef.IntVar -> {
            val d = session.intDomain(varRef.varId)
            val few = d.spanOrNull(maxProbes.toLong())
            if (few != null) {
                LongArray(few.size) { few.valueAt(it) }
            } else {
                val seen = LongHashSet(maxProbes * 2)
                val sample = LongArray(maxProbes)
                var i = 0
                var guard = 0
                while (i < maxProbes && guard < maxProbes * 8) {
                    // Positional sampling is uniform only over an enumerable domain; a saturated
                    // index space never reaches values past the first 2^31, so sample the bounds.
                    val candidate = d.randomValue(rng)
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
    val scored = ArrayList<Pair<Long, Double>>(candidates.size)
    // Every candidate a probe actually reached: ordered when it survived, dropped when the probe refuted
    // it (no assignment needs to revisit those). Kept apart from the ones left unprobed below.
    val settled = LongHashSet(candidates.size * 2)
    var probed = 0
    for (v in candidates) {
        // Each probe is a whole propagation fixpoint, so one decision over a wide domain spends
        // [maxProbes] of them, and one such fixpoint alone can run seconds long. The engine polls between
        // nodes and cannot see inside this loop, so the deadline is read here and inside every probe (which
        // yields null when cut, leaving its value unprobed for the tail below). Either sighting stops the
        // engine at this node — the ranking is worth nothing once the time is gone.
        if (session.cancelRequested) {
            session.probeCancelled = true
            break
        }
        val r = when (varRef) {
            is VarRef.Bool -> session.probeBool(varRef.varId, v != 0L)
            is VarRef.IntVar -> session.probeInt(varRef.varId, v)
        } ?: break
        probed++
        settled.add(v)
        if (r is Unsat) continue
        val post = logRemainingDomainProduct(session)
        session.popLast()
        scored.add(v to post)
    }
    // Whatever the probes did not reach still has to be offered, or the search would silently skip
    // values the domain holds and could report unsat over a region it never entered.
    val unprobed = if (probed < candidates.size) candidates.copyOfRange(probed, candidates.size) else EmptyLongArray
    if (ascending) scored.sortBy { it.second } else scored.sortByDescending { it.second }
    if (varRef is VarRef.IntVar) {
        val d = session.intDomain(varRef.varId)
        // The tail is "every other value the domain holds", so it exists only on a domain that can be
        // walked. On a wider one the probed candidates are the offer; a bound split covers the rest.
        val walkable = d.spanOrNull()
        if (walkable != null && candidates.size < walkable.size) {
            // Already offered (ordered) or deliberately dropped; everything else in the domain follows.
            val ordered = scored.asSequence().map { it.first }
            return ordered + sequence {
                for (i in 0 until walkable.size) {
                    val v = walkable.valueAt(i)
                    if (v !in settled) yield(v)
                }
            }
        }
    }
    return scored.asSequence().map { it.first } + unprobed.toList().asSequence()
}

/** Sum of `ln(size)` over every unpinned variable (bools count as `ln 2` when free). Log-
 *  space to dodge `Double` overflow on problems with hundreds of free vars. */
internal fun logRemainingDomainProduct(session: PropagationSession): Double {
    var s = 0.0
    val p = session.problem
    val ln2 = ln(2.0)
    for (v in 0 until p.numBoolVars) if (session.boolValue(v) == null) s += ln2
    for (v in 0 until p.numIntVars) {
        val sz = session.intDomain(v).valueCount
        if (sz > 1) s += ln(sz.toDouble())
    }
    return s
}

/** Ascending sequence of all values in [d], skipping any holes. Materialises lazily so
 *  the engine can early-exit before enumerating the full domain on a backtrack. */
internal fun domainValuesAscending(d: IntDomain): Sequence<Long> =
    sequence { for (i in 0 until d.values.size) yield(d.values.valueAt(i)) }

/** Descending sequence; same skip-holes semantics. */
internal fun domainValuesDescending(d: IntDomain): Sequence<Long> = sequence {
    // Backwards walk: iterate from max down to min, skip holes via membership check.
    // For sparse domains this is O((max - min) + holes); for contiguous it's O(span).
    for (v in d.max downTo d.min) if (v in d) yield(v)
}
