package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.ValueOrderingStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.LongHashSet
import kotlin.math.ln
import kotlin.random.Random

/**
 * Picks the order of values to try for a chosen variable, lazily; on conflict the engine advances to the
 * next value. Bool values are `0` (false) and `1` (true).
 */
interface ValueSelector {
    /** Candidate values for [varRef], yielded in trial order. */
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

    /** Called once per solution the search reaches. */
    fun onSolution(snapshot: Sample) {}
}

// Shift-and-carry: `d.max - d.min` wraps on spans wider than `Long.MAX_VALUE`.
internal fun boundsMidpoint(d: IntDomain): Long = (d.min shr 1) + (d.max shr 1) + (d.min and d.max and 1L)

// Cursor-based rather than `center ± offset`: the offset arithmetic wraps near the ends of the `Long`
// range and spins forever.
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

// Scores each probed value by the log of the remaining-domain product; refuted values are dropped. The rest
// of a domain wider than `maxProbes` follows the ordered values so coverage is preserved.
internal fun probeAndOrder(
    session: PropagationSession,
    varRef: VarRef,
    rng: Random,
    maxProbes: Int,
    ascending: Boolean,
    observations: ValueOrderingStatsSink? = null,
    scoreVisit: (() -> Boolean)? = null,
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
                    // A saturated index space never reaches values past the first 2^31, so sample the bounds.
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
    // Candidates a probe reached, surviving or refuted; the unprobed ones are kept apart.
    val settled = LongHashSet(candidates.size * 2)
    var probed = 0
    for (v in candidates) {
        // One probe fixpoint can run for seconds and the engine polls only between nodes, so the deadline
        // is read here and inside every probe (null when cut, leaving the value unprobed).
        if (session.cancelRequested) {
            session.probeCancelled = true
            break
        }
        val r = when (varRef) {
            is VarRef.Bool -> session.probeBool(varRef.varId, v != 0L)
            is VarRef.IntVar -> session.probeInt(varRef.varId, v)
        } ?: break
        observations?.let {
            it.probes++
            if (r is Unsat) it.refutations++
        }
        if (r is Unsat) {
            probed++
            settled.add(v)
            continue
        }
        val post = try {
            logRemainingDomainProduct(session, scoreVisit)
        } finally {
            session.popLast()
        } ?: break
        probed++
        settled.add(v)
        scored.add(v to post)
    }
    // Unreached values must still be offered, or the search could report unsat over a region it never
    // entered.
    val unprobed = if (probed < candidates.size) candidates.copyOfRange(probed, candidates.size) else emptyLongArray()
    if (ascending) scored.sortBy { it.second } else scored.sortByDescending { it.second }
    if (varRef is VarRef.IntVar) {
        val d = session.intDomain(varRef.varId)
        // Only a walkable domain gets the full tail; on a wider one a bound split covers the rest.
        val walkable = d.spanOrNull()
        if (walkable != null && candidates.size < walkable.size) {
            val ordered = scored.asSequence().map { it.first }
            return ordered + sequence {
                for (i in 0 until walkable.size) {
                    val v = walkable.valueAt(i)
                    if (v !in settled) yield(v)
                }
            }
        }
        // An empty order means every value is refuted, which a sample of an unwalkable domain cannot show.
        if (walkable == null && scored.isEmpty() && unprobed.isEmpty()) return sequenceOf(boundsMidpoint(d))
    }
    return scored.asSequence().map { it.first } + unprobed.toList().asSequence()
}

// Log-space to avoid `Double` overflow on problems with hundreds of free vars.
internal fun logRemainingDomainProduct(session: PropagationSession, visit: (() -> Boolean)? = null): Double? {
    var s = 0.0
    val p = session.problem
    val ln2 = ln(2.0)
    for (v in 0 until p.numBoolVars) {
        if (visit != null && !visit()) return null
        if (session.boolValue(v) == null) s += ln2
    }
    for (v in 0 until p.numIntVars) {
        if (visit != null && !visit()) return null
        val sz = session.intDomain(v).valueCount
        if (sz > 1) s += ln(sz.toDouble())
    }
    return s
}

internal fun domainValuesAscending(d: IntDomain): Sequence<Long> =
    sequence { for (i in 0 until d.values.size) yield(d.values.valueAt(i)) }

internal fun domainValuesDescending(d: IntDomain): Sequence<Long> = sequence {
    // O(max - min) membership walk, holes included.
    for (v in d.max downTo d.min) if (v in d) yield(v)
}
