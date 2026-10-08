package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boolPinnedAt
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.argsortBy

/**
 * CP propagator for the no-overlap case of [com.eignex.klause.factor.scheduling.Cumulative]
 * (`unary = true`, built via [com.eignex.klause.factor.scheduling.Cumulative.unary]) and provides
 * time-tabling, detectable precedences, and Θ-tree edge-finding for the unary case.
 *
 * Each deduction cites only the tasks it rests on: a detectable precedence its pair, a time-table shave the
 * tasks whose compulsory parts block the shaved starts, an edge-finding bound the tasks of the overloaded set
 * and of the set the bound comes from. The latter two cost O(tasks) and are built only when conflict analysis
 * reads them, from the bounds as they stood ([explain]). Bounds are cited only as far as the deduction needs them.
 */
internal class DisjunctivePropagator(
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: LongArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val n: Int,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    // The reason of the failure the last [propagate] hit, read by [conflictReason] before the engine backtracks.
    private var failure: IntArray? = null

    /** Snapshot effective per-task durations. Returns null if any duration var is not
     *  fixed at this fixpoint pass — propagation defers in that case (sound). */
    private fun effDurOrNull(state: PropagationState): LongArray? {
        if (durationVars.isEmpty()) return LongArray(n) { durations[it] }
        val out = LongArray(n)
        for (i in 0 until n) {
            val d = state.intDomains[durationVars[i]]
            if (d.min != d.max) return null
            out[i] = d.min
        }
        return out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        failure ?: OptPresence.withPresencePremises(
            presents,
            state,
            collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0),
        )

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int) =
        View(state, atTrail, atLevel).run {
            explainPayload(payload)
            literals()
        }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        failure = null
        if (n == 0) return true
        val effDur = effDurOrNull(state) ?: return true
        if (!timeTable(state, effDur)) return false
        if (!detectablePrecedences(state, effDur)) return false
        if (!edgeFinding(state, effDur)) return false
        return true
    }

    private fun now(state: PropagationState) = View(state, state.undo.size, state.currentLevel)

    // A deduction's reason, recorded for [explain] to build from [payload] if analysis reads it.
    private fun reasonFor(state: PropagationState, payload: IntArray): IntArray? = when {
        state.currentLevel == 0 -> null

        state.undoLogging -> state.lazyReason(payload)

        else -> now(state).run {
            explainPayload(payload)
            literals()
        }
    }

    private fun View.explainPayload(payload: IntArray) {
        when (payload[0]) {
            TIME_TABLE -> timeTableReason(payload[1], lower = payload[2] == 1, unpack(payload, 3))
            else -> edgeReason(reversed = payload[1] == 1, cand = payload[2], tau = unpack(payload, 3))
        }
    }

    // Tightening [v] failed against its other bound or a hole: the deduction's reason plus [v]'s own domain.
    private fun failWith(state: PropagationState, task: Int, build: View.() -> Unit): Boolean {
        failure = now(state).run {
            build()
            ownDomain(task)
            literals()
        }
        return false
    }

    /** Build the mandatory profile from each task's `[lst, ect)` compulsory part; fail on
     *  level > 1; shave any non-fixed task's start endpoints if placement would create
     *  an additional unit-overlap with the mandatory profile. */
    private fun timeTable(state: PropagationState, effDur: LongArray): Boolean {
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = 1L)
        }
        if (!profile.build(cap = 1L)) {
            failure = now(state).run {
                overloadAt(profile.overloadTime)
                literals()
            }
            return false
        }
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val lstI = dom.max
            val ectI = dom.min + d
            val ownsMandatory = lstI < ectI
            var newMin = dom.min
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r = 1L, cap = 1L, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin > state.intDomains[v].max) return failWith(state, i) { timeTableReason(i, true, newMin) }
            if (newMin != state.intDomains[v].min) {
                val ant = reasonFor(state, packed(TIME_TABLE, i, 1, newMin))
                if (!state.tightenIntMin(v, newMin, ant)) return failWith(state, i) { timeTableReason(i, true, newMin) }
            }
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r = 1L, cap = 1L, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax < state.intDomains[v].min) return failWith(state, i) { timeTableReason(i, false, newMax) }
            if (newMax != state.intDomains[v].max) {
                val ant = reasonFor(state, packed(TIME_TABLE, i, 0, newMax))
                if (!state.tightenIntMax(v, newMax, ant)) {
                    return failWith(state, i) { timeTableReason(i, false, newMax) }
                }
            }
        }
        return true
    }

    /** Pairwise rule: if `est_i + dur_i > lst_j`, task i can't end before j must start;
     *  i must come strictly after j. Tighten `start_i.min ≥ est_j + dur_j`. */
    private fun detectablePrecedences(state: PropagationState, effDur: LongArray): Boolean {
        for (i in 0 until n) {
            if (effDur[i] == 0L) continue
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var newMinI = di.min
            var by = -1
            for (j in 0 until n) {
                if (j == i) continue
                if (effDur[j] == 0L) continue
                if (!OptPresence.isDefinitelyPresent(presents, j, state)) continue
                val dj = state.intDomains[starts[j]]
                if (di.min + effDur[i] > dj.max) {
                    if (dj.min + effDur[j] > di.max) {
                        failure = now(state).run {
                            precedes(j, i)
                            precedes(i, j)
                            literals()
                        }
                        return false
                    }
                    if (dj.min + effDur[j] > newMinI) {
                        newMinI = dj.min + effDur[j]
                        by = j
                    }
                }
            }
            if (by < 0) continue
            val reason = if (state.currentLevel == 0) {
                null
            } else {
                now(state).run {
                    precedes(by, i)
                    startGe(by, newMinI - dur(by))
                    literals()
                }
            }
            if (newMinI > di.max || !state.tightenIntMin(vi, newMinI, reason)) {
                return failWith(state, i) {
                    precedes(by, i)
                    startGe(by, newMinI - dur(by))
                }
            }
        }
        return true
    }

    private fun edgeFinding(state: PropagationState, effDur: LongArray): Boolean {
        if (n < 2) return true
        return forwardPass(state, effDur, reversed = false) && forwardPass(state, effDur, reversed = true)
    }

    @Suppress("ReturnCount")
    private fun forwardPass(state: PropagationState, effDur: LongArray, reversed: Boolean): Boolean {
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val durs = LongArray(m) { effDur[taskIds[it]] }
        val ests = LongArray(m)
        val lcts = LongArray(m)
        for (t in 0 until m) {
            val dom = state.intDomains[starts[taskIds[t]]]
            if (!reversed) {
                ests[t] = dom.min
                lcts[t] = dom.max + durs[t]
            } else {
                ests[t] = -(dom.max + durs[t])
                lcts[t] = -dom.min
            }
        }
        val energies = LongArray(m) { durs[it] }

        val estOrder = argsortBy(m) { a, b -> ests[a].compareTo(ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> lcts[a].compareTo(lcts[b]) }

        val tree = CumulativeThetaTree(n = m, capacity = 1L)
        tree.setLeafOrder(leafPos)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta()
            if (envTheta > tau) {
                failure = now(state).run {
                    overloadedBy(reversed, tau)
                    literals()
                }
                return false
            }
            for (ki in k until m) {
                val cand = lctOrder[ki]
                tree.activate(cand, ests[cand], energies[cand])
                val envWith = tree.envOfTheta()
                tree.deactivate(cand)
                if (envWith <= tau) continue
                val bound = envTheta
                val task = taskIds[cand]
                val v = starts[task]
                val payload = packed(EDGE, if (reversed) 1 else 0, task, tau)
                if (!reversed) {
                    if (bound > state.intDomains[v].min &&
                        !state.tightenIntMin(v, bound, reasonFor(state, payload))
                    ) {
                        return failWith(state, task) { edgeReason(false, task, tau) }
                    }
                } else {
                    val newMax = -bound - durs[cand]
                    if (newMax < state.intDomains[v].max &&
                        !state.tightenIntMax(v, newMax, reasonFor(state, payload))
                    ) {
                        return failWith(state, task) { edgeReason(true, task, tau) }
                    }
                }
            }
        }
        return true
    }

    /** The tasks' bounds, durations and presence as of [atTrail], with the literals a reason collects. */
    private inner class View(val state: PropagationState, val atTrail: Int, val atLevel: Int) {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun est(i: Int): Long = state.domainAt(starts[i], atTrail).min
        fun lst(i: Int): Long = state.domainAt(starts[i], atTrail).max
        fun dur(i: Int): Long =
            if (durationVars.isEmpty()) durations[i] else state.domainAt(durationVars[i], atTrail).min

        fun present(i: Int): Boolean {
            if (presents.isEmpty()) return true
            val lit = presents[i]
            val v = Lit.variable(lit)
            return state.boolPinnedAt(v, atTrail) && state.boolValues[v] == Lit.isPositive(lit)
        }

        private fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun startGe(i: Int, need: Long) = add(state.boundLiteral(starts[i], true, need, atTrail, atLevel))
        fun startLe(i: Int, need: Long) = add(state.boundLiteral(starts[i], false, need, atTrail, atLevel))

        // Task [i] runs, for at least its duration.
        fun task(i: Int) {
            if (durationVars.isNotEmpty()) add(state.boundLiteral(durationVars[i], true, dur(i), atTrail, atLevel))
            if (presents.isNotEmpty()) add(Lit.negate(presents[i]))
        }

        /** [before] cannot follow [after]: `after` starts too late to end before `before` must start. */
        fun precedes(before: Int, after: Int) {
            task(before)
            task(after)
            startLe(before, lst(before))
            startGe(after, lst(before) - dur(after) + 1)
        }

        fun ownDomain(i: Int) {
            startGe(i, est(i))
            startLe(i, lst(i))
        }

        // Task [k]'s compulsory part covers [t], as far as it must.
        private fun covers(k: Int, t: Long) {
            task(k)
            startLe(k, t)
            startGe(k, t - dur(k) + 1)
        }

        fun overloadAt(t: Long) {
            for (k in 0 until n) {
                if (!present(k) || dur(k) == 0L) continue
                if (lst(k) <= t && t < est(k) + dur(k)) covers(k, t)
            }
        }

        /**
         * Task [i]'s start moved to [bound] ([lower]) because every start it skipped overlaps another task's
         * compulsory part: cite those parts that meet the skipped placements, and [i]'s own bound on that side.
         */
        fun timeTableReason(i: Int, lower: Boolean, bound: Long) {
            task(i)
            val d = dur(i)
            val lo: Long
            val hi: Long
            if (lower) {
                startGe(i, est(i))
                lo = est(i)
                hi = bound + d - 2
            } else {
                startLe(i, lst(i))
                lo = bound + 1
                hi = lst(i) + d - 1
            }
            for (k in 0 until n) {
                if (k == i || !present(k) || dur(k) == 0L) continue
                val partLo = lst(k)
                val partHi = est(k) + dur(k) - 1
                if (partLo > partHi || partHi < lo || partLo > hi) continue
                task(k)
                startLe(k, partLo)
                startGe(k, partHi - dur(k) + 1)
            }
        }

        // Each task's start-to-end window in edge-finding coordinates: as is, or mirrored for the latest-start pass.
        private fun eft(i: Int, reversed: Boolean): Long = if (!reversed) est(i) else -(lst(i) + dur(i))
        private fun lct(i: Int, reversed: Boolean): Long = if (!reversed) lst(i) + dur(i) else -est(i)

        // Task [i] starts at or after [e] in edge-finding coordinates.
        private fun estAtLeast(i: Int, e: Long, reversed: Boolean) {
            task(i)
            if (!reversed) startGe(i, e) else startLe(i, -e - dur(i))
        }

        // Task [i] completes by [tau] in edge-finding coordinates.
        private fun lctAtMost(i: Int, tau: Long, reversed: Boolean) {
            task(i)
            if (!reversed) startLe(i, tau - dur(i)) else startGe(i, -tau)
        }

        // The tasks of [set] from the earliest-completion threshold that maximizes their earliest completion.
        private fun ectSubset(set: IntArrayList, reversed: Boolean): Pair<Long, IntArrayList> {
            val sorted = (0 until set.size).map { set[it] }.sortedByDescending { eft(it, reversed) }
            var energy = 0L
            var best = Long.MIN_VALUE
            var bestEst = 0L
            for (x in sorted) {
                energy += dur(x)
                val ect = eft(x, reversed) + energy
                if (ect > best) {
                    best = ect
                    bestEst = eft(x, reversed)
                }
            }
            val subset = IntArrayList()
            for (x in sorted) if (eft(x, reversed) >= bestEst) subset.add(x)
            return bestEst to subset
        }

        private fun theta(reversed: Boolean, tau: Long, except: Int): IntArrayList {
            val out = IntArrayList()
            for (k in 0 until n) {
                if (k == except || !present(k) || dur(k) == 0L) continue
                if (lct(k, reversed) <= tau) out.add(k)
            }
            return out
        }

        /** The tasks completing by [tau] cannot all fit after their own earliest start: an overload. */
        fun overloadedBy(reversed: Boolean, tau: Long) {
            val (e, subset) = ectSubset(theta(reversed, tau, -1), reversed)
            for (k in 0 until subset.size) {
                estAtLeast(subset[k], e, reversed)
                lctAtMost(subset[k], tau, reversed)
            }
        }

        /**
         * [cand] runs after every task the set completing by [tau] must hold: with it the set overflows [tau], so
         * it follows each of its members; its bound is the earliest completion of the members that set it.
         */
        fun edgeReason(reversed: Boolean, cand: Int, tau: Long) {
            val theta = theta(reversed, tau, cand)
            val withCand = IntArrayList()
            for (k in 0 until theta.size) withCand.add(theta[k])
            withCand.add(cand)
            val (e1, overflow) = ectSubset(withCand, reversed)
            for (k in 0 until overflow.size) {
                val x = overflow[k]
                estAtLeast(x, e1, reversed)
                if (x != cand) lctAtMost(x, tau, reversed)
            }
            val (e2, before) = ectSubset(theta, reversed)
            for (k in 0 until before.size) {
                estAtLeast(before[k], e2, reversed)
                lctAtMost(before[k], tau, reversed)
            }
        }

        fun literals(): IntArray = out.toIntArray()
    }

    private companion object {
        const val TIME_TABLE = 0
        const val EDGE = 1

        fun packed(kind: Int, a: Int, b: Int, value: Long) =
            intArrayOf(kind, a, b, (value ushr 32).toInt(), value.toInt())

        fun unpack(payload: IntArray, at: Int): Long =
            (payload[at].toLong() shl 32) or (payload[at + 1].toLong() and 0xFFFFFFFFL)
    }
}
