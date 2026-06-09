package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.LongArrayList

/**
 * Energetic-reasoning infeasibility check for Cumulative globals (#22/#23). For a time window
 * `[t1, t2)`, every task must spend at least its *mandatory* energy inside the window no matter where
 * it is placed; if the summed mandatory energy of the tasks exceeds `capacity·(t2 − t1)`, no schedule
 * fits and the node is infeasible (Baptiste–Le Pape–Nuijten / Lopez energetic reasoning).
 *
 * This is a pure feasibility test (objective-independent), used as a node prune. It is deliberately
 * conservative so it stays sound: required energy uses each task's **minimum** demand, **minimum**
 * duration and only **definitely-present** tasks (a lower bound on what must fit), while capacity uses
 * its **maximum** (an upper bound on what is available) — so a reported overflow is always real.
 *
 * Out of scope (deferred, documented on #22/#23): energetic *cuts* added to the LP (they need a
 * time-indexed reformulation klause's start-variable LP does not have) and a Cumulative Lagrangian
 * subproblem via min-cost flow. Those overlap heavily with klause's existing theta-tree / edge-finding
 * propagation; this check is the sound, self-contained slice.
 */
internal class CumulativeEnergeticBound(problem: Problem) {
    private val factors: List<Cumulative> = problem.factors.filterIsInstance<Cumulative>()

    val applicable: Boolean get() = factors.isNotEmpty()

    /** True if some Cumulative is energetically infeasible at the current node (⇒ prune the subtree). */
    fun isInfeasible(session: PropagationSession): Boolean = factors.any { overSubscribed(it, session) }

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

    private companion object {
        const val MAX_TASKS: Int = 256
    }
}
