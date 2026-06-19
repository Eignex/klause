package com.eignex.klause.solver.lp.bound

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.subExact
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Energetic-reasoning infeasibility check for Cumulative globals. For a time window
 * `[t1, t2)`, every task must spend at least its *mandatory* energy inside the window no matter where
 * it is placed; if the summed mandatory energy of the tasks exceeds `capacity·(t2 − t1)`, no schedule
 * fits and the node is infeasible (Baptiste–Le Pape–Nuijten / Lopez energetic reasoning).
 *
 * This is a pure feasibility test (objective-independent), used as a node prune. It is deliberately
 * conservative so it stays sound: required energy uses each task's **minimum** demand, **minimum**
 * duration and only **definitely-present** tasks (a lower bound on what must fit), while capacity uses
 * its **maximum** (an upper bound on what is available) — so a reported overflow is always real.
 *
 * Out of scope (deferred): energetic *cuts* added to the LP (they need a
 * time-indexed reformulation klause's start-variable LP does not have) and a Cumulative Lagrangian
 * subproblem via min-cost flow. Those overlap heavily with klause's existing theta-tree / edge-finding
 * propagation; this check is the sound, self-contained slice.
 */
internal class CumulativeEnergeticBound(problem: Problem) {
    private val factors: List<Cumulative> = problem.factors.filterIsInstance<Cumulative>()

    val applicable: Boolean get() = factors.isNotEmpty()

    /** True if some Cumulative is energetically infeasible at the current node (⇒ prune the subtree). */
    fun isInfeasible(session: PropagationSession): Boolean = factors.any { overSubscribed(it, session) }

    /**
     * A nogood explaining the first energetically over-subscribed window, or null when no
     * Cumulative is over-subscribed (or the arithmetic overflows). The window's over-subscription
     * `Σ dem_i·overlap_i > capacity·width` follows from each contributing task's start bounds
     * (`start ≥ est`, `start ≤ lst` pin the mandatory overlap), its min duration/demand and the max
     * capacity — so the clause negates exactly those bound atoms. It is implied by the constraint
     * alone (relaxing any one bound is what could let the schedule fit), hence globally valid, and is
     * registered lazily at a restart like the LP nogoods (it is all-false at the dead node).
     */
    fun explain(session: PropagationSession): IntArray? {
        for (c in factors) {
            val clause = try {
                explainChecked(c, session)
            } catch (_: LpOverflowException) {
                null
            }
            if (clause != null) return clause
        }
        return null
    }

    // All energy/capacity products use checked arithmetic: an overflow must NOT wrap into a spurious
    // "infeasible" (that would drop valid schedules), so it is caught and reported as "cannot prove
    // infeasible" (false) — the same sound skip the exact LP path takes on overflow.
    private fun overSubscribed(c: Cumulative, session: PropagationSession): Boolean = try {
        overSubscribedChecked(c, session)
    } catch (_: LpOverflowException) {
        false
    }

    private fun overSubscribedChecked(c: Cumulative, session: PropagationSession): Boolean {
        val n = c.starts.size
        if (n == 0 || n > MAX_TASKS) return false
        val capacity = if (c.capacityVar >= 0) session.intDomain(c.capacityVar).max.toLong() else c.capacity.toLong()

        // Per definitely-present task: earliest start, latest start, min duration, min demand.
        val est = LongArray(n)
        val lst = LongArray(n)
        val dur = LongArray(n)
        val dem = LongArray(n)
        val present = BooleanArray(n)
        val starts = LongArrayList() // candidate window left edges
        val ends = LongArrayList() // candidate window right edges
        for (i in 0 until n) {
            if (c.presents.isNotEmpty() && session.litTruth(c.presents[i]) != true) continue // maybe absent
            val sd = session.intDomain(c.starts[i])
            est[i] = sd.min.toLong()
            lst[i] = sd.max.toLong()
            dur[i] = minOrConst(session, c.durationVars, i, c.durations[i])
            dem[i] = minOrConst(session, c.resourceVars, i, c.resources[i])
            if (dur[i] <= 0L || dem[i] <= 0L) continue
            present[i] = true
            starts.add(est[i])
            ends.add(addExact(lst[i], dur[i])) // latest completion
        }

        // Sound subset of the energetic window set: left edges from earliest starts, right edges from
        // latest completions. A reported overflow over any window is a genuine infeasibility.
        for (a in 0 until starts.size) {
            val t1 = starts[a]
            for (b in 0 until ends.size) {
                val t2 = ends[b]
                if (t2 <= t1) continue
                val width = subExact(t2, t1)
                var required = 0L
                for (i in 0 until n) {
                    if (!present[i]) continue
                    val overlap = mandatoryOverlap(est[i], lst[i], dur[i], t1, t2)
                    if (overlap > 0L) required = addExact(required, mulExact(dem[i], overlap))
                }
                if (required > mulExact(capacity, width)) return true
            }
        }
        return false
    }

    /**
     * The bound-atom nogood for the first over-subscribed window of [c], or null if none. Recomputes
     * the same window scan as [overSubscribedChecked]; on the first violating `[t1, t2)` it gathers
     * the reason: per contributing task its presence (if optional), `start ≥ est`, `start ≤ lst`, and
     * any variable min duration / min demand, plus the max capacity — each negated.
     */
    private fun explainChecked(c: Cumulative, session: PropagationSession): IntArray? {
        val n = c.starts.size
        if (n == 0 || n > MAX_TASKS) return null
        val capacity = if (c.capacityVar >= 0) session.intDomain(c.capacityVar).max.toLong() else c.capacity.toLong()
        val est = LongArray(n)
        val lst = LongArray(n)
        val dur = LongArray(n)
        val dem = LongArray(n)
        val present = BooleanArray(n)
        val starts = LongArrayList()
        val ends = LongArrayList()
        for (i in 0 until n) {
            if (c.presents.isNotEmpty() && session.litTruth(c.presents[i]) != true) continue
            val sd = session.intDomain(c.starts[i])
            est[i] = sd.min.toLong()
            lst[i] = sd.max.toLong()
            dur[i] = minOrConst(session, c.durationVars, i, c.durations[i])
            dem[i] = minOrConst(session, c.resourceVars, i, c.resources[i])
            if (dur[i] <= 0L || dem[i] <= 0L) continue
            present[i] = true
            starts.add(est[i])
            ends.add(addExact(lst[i], dur[i]))
        }
        for (a in 0 until starts.size) {
            val t1 = starts[a]
            for (b in 0 until ends.size) {
                val t2 = ends[b]
                if (t2 <= t1) continue
                val width = subExact(t2, t1)
                var required = 0L
                for (i in 0 until n) {
                    if (!present[i]) continue
                    val overlap = mandatoryOverlap(est[i], lst[i], dur[i], t1, t2)
                    if (overlap > 0L) required = addExact(required, mulExact(dem[i], overlap))
                }
                if (required > mulExact(capacity, width)) {
                    return windowClause(c, session, est, lst, dur, dem, present, t1, t2, capacity)
                }
            }
        }
        return null
    }

    /**
     * The negated reason atoms for the over-subscribed window `[t1, t2)`; see [explainChecked]. Each
     * contributing task pins its mandatory overlap via `start ≥ est` and `start ≤ lst` (plus presence
     * and any variable min duration / min demand); the max capacity is the last reason. Relaxing any
     * one is what could let the schedule fit, so the disjunction of their negations is valid.
     */
    @Suppress("LongParameterList")
    private fun windowClause(
        c: Cumulative,
        session: PropagationSession,
        est: LongArray,
        lst: LongArray,
        dur: LongArray,
        dem: LongArray,
        present: BooleanArray,
        t1: Long,
        t2: Long,
        capacity: Long,
    ): IntArray {
        val lits = IntArrayList()
        fun geNot(v: Int, k: Long) = lits.add(session.boundGeLit(v, k.toInt(), positive = false))
        for (i in c.starts.indices) {
            if (!present[i] || mandatoryOverlap(est[i], lst[i], dur[i], t1, t2) <= 0L) continue
            if (c.presents.isNotEmpty()) lits.add(Lit.negate(c.presents[i]))
            geNot(c.starts[i], est[i])
            lits.add(session.boundLeLit(c.starts[i], lst[i].toInt(), positive = false))
            if (c.durationVars.isNotEmpty()) geNot(c.durationVars[i], dur[i])
            if (c.resourceVars.isNotEmpty()) geNot(c.resourceVars[i], dem[i])
        }
        if (c.capacityVar >= 0) lits.add(session.boundLeLit(c.capacityVar, capacity.toInt(), positive = false))
        return lits.toIntArray()
    }

    /** Live minimum of the per-task variable (when [vars] is set) else the [const] fallback. */
    private fun minOrConst(session: PropagationSession, vars: IntArray, i: Int, const: Int): Long =
        if (vars.isNotEmpty()) session.intDomain(vars[i]).min.toLong() else const.toLong()

    /** Minimum time task `[est, lst]+dur` must spend inside `[t1, t2)` over all its placements. */
    private fun mandatoryOverlap(est: Long, lst: Long, dur: Long, t1: Long, t2: Long): Long {
        val left = subExact(addExact(est, dur), t1) // must lie after t1 if pushed fully left
        val right = subExact(t2, lst) // must lie before t2 if pushed fully right
        val m = minOf(dur, subExact(t2, t1), left, right)
        return if (m > 0L) m else 0L
    }

    internal companion object {
        /** Per-factor task cap: factors above it are skipped entirely (and cost nothing — the
         *  auto-config cadence estimate relies on this). */
        internal const val MAX_TASKS: Int = 256
    }
}
