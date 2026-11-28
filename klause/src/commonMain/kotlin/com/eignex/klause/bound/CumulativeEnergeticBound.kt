package com.eignex.klause.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

// Energetic reasoning (Baptiste, Le Pape and Nuijten, "Satisfiability tests and time-bound adjustments for
// cumulative scheduling problems", Annals of Operations Research 1999, https://doi.org/10.1023/A:1018995000688,
// after Lopez, Erschler and Esquirol 1992): a window `[t1, t2)` whose summed mandatory energy exceeds
// `capacity·(t2 − t1)` prunes the node. Required energy uses minimum demands and durations of definitely-present
// tasks and capacity its maximum, so a reported overflow is always real.
internal class CumulativeEnergeticBound(
    problem: Problem,
    private val factors: List<Cumulative> = problem.factors.filterIsInstance<Cumulative>(),
) : SchedulingFeasibilityBound {

    val applicable: Boolean get() = factors.isNotEmpty()

    override fun isInfeasible(session: PropagationSession): Boolean = factors.any { overSubscribed(it, session) }

    // The nogood negates the bound atoms that pin each contributing task's mandatory overlap and the max
    // capacity. It follows from the constraint alone, so it is global and can be registered lazily at a restart.
    override fun explain(session: PropagationSession): IntArray? {
        for (c in factors) {
            val clause = try {
                explainChecked(c, session)
            } catch (_: CheckedLongOverflowException) {
                null
            }
            if (clause != null) return clause
        }
        return null
    }

    // An overflow must not wrap into a spurious "infeasible", so it reports "cannot prove infeasible".
    private fun overSubscribed(c: Cumulative, session: PropagationSession): Boolean = try {
        overSubscribedChecked(c, session)
    } catch (_: CheckedLongOverflowException) {
        false
    }

    private fun overSubscribedChecked(c: Cumulative, session: PropagationSession): Boolean {
        val n = c.starts.size
        if (n == 0 || n > MAX_TASKS) return false
        val capacity = if (c.capacityVar >= 0) session.intDomain(c.capacityVar).max else c.capacity

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
            est[i] = sd.min
            lst[i] = sd.max
            dur[i] = minOrConst(session, c.durationVars, i, c.durations[i])
            dem[i] = minOrConst(session, c.resourceVars, i, c.resources[i])
            if (dur[i] <= 0L || dem[i] <= 0L) continue
            present[i] = true
            starts.add(est[i])
            ends.add(addExact(lst[i], dur[i]))
        }

        // A subset of the energetic windows: left edges from earliest starts, right edges from latest completions.
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

    private fun explainChecked(c: Cumulative, session: PropagationSession): IntArray? {
        val n = c.starts.size
        if (n == 0 || n > MAX_TASKS) return null
        val capacity = if (c.capacityVar >= 0) session.intDomain(c.capacityVar).max else c.capacity
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
            est[i] = sd.min
            lst[i] = sd.max
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
        fun geNot(v: Int, k: Long) = lits.add(session.boundGeLit(v, k, positive = false))
        for (i in c.starts.indices) {
            if (!present[i] || mandatoryOverlap(est[i], lst[i], dur[i], t1, t2) <= 0L) continue
            if (c.presents.isNotEmpty()) lits.add(Lit.negate(c.presents[i]))
            geNot(c.starts[i], est[i])
            lits.add(session.boundLeLit(c.starts[i], lst[i], positive = false))
            if (c.durationVars.isNotEmpty()) geNot(c.durationVars[i], dur[i])
            if (c.resourceVars.isNotEmpty()) geNot(c.resourceVars[i], dem[i])
        }
        if (c.capacityVar >= 0) lits.add(session.boundLeLit(c.capacityVar, capacity, positive = false))
        return lits.toIntArray()
    }

    private fun minOrConst(session: PropagationSession, vars: IntArray, i: Int, const: Long): Long =
        if (vars.isNotEmpty()) session.intDomain(vars[i]).min else const

    private fun mandatoryOverlap(est: Long, lst: Long, dur: Long, t1: Long, t2: Long): Long {
        val left = subExact(addExact(est, dur), t1)
        val right = subExact(t2, lst)
        val m = minOf(dur, subExact(t2, t1), left, right)
        return if (m > 0L) m else 0L
    }

    internal companion object {
        // Larger factors are skipped entirely and cost nothing, which the auto-config cadence estimate relies on.
        internal const val MAX_TASKS: Int = 256
    }
}
