package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.OptPresence
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.scheduling.internals.CumulativeEff
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.factor.scheduling.internals.EdgeFindingOmegas
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
 * CP propagator for [Cumulative]. Constructed by the propagation projection and holds the
 * time-tabling and Θ-tree edge-finding logic so those data structures are only allocated
 * when a CP engine is initialised.
 *
 * Each deduction cites only the tasks it rests on, recorded lazily and built when conflict analysis reads it
 * from the bounds as they stood ([explain]): a time-table shave the compulsory parts that block the skipped
 * starts, an edge-finding bound the set that must precede the task and the subset that sets the bound, an
 * energetic bound the tasks inside its window, a height bound the compulsory parts stacked on its peak.
 * Bounds are cited only as far as the deduction needs them, and each failure records its own reason.
 */
internal class CumulativePropagator(
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: LongArray,
    private val resources: LongArray,
    private val capacity: Long,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val resourceVars: IntArray,
    private val capacityVar: Int,
    private val n: Int,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    // The reason of the failure the last [propagate] hit, read by [conflictReason] before the engine backtracks.
    private var failure: IntArray? = null

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

    private fun View.explainPayload(p: IntArray) {
        when (p[0]) {
            TIME_TABLE -> shaveReason(p[1], lower = p[2] == 1, unpack(p, 3))
            EDGE -> edgeReason(p[1], tau = unpack(p, 2))
            ENERGY -> energyReason(p[1], kind = p[2], xMin = unpack(p, 3), xMax = unpack(p, 5))
            else -> heightReason(p[1])
        }
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

    private fun fail(state: PropagationState, build: View.() -> Unit): Boolean {
        failure = now(state).run {
            build()
            literals()
        }
        return false
    }

    // Tightening variable [v] failed against its other bound or a hole: the deduction's reason plus [v]'s domain.
    private fun failTighten(state: PropagationState, v: Int, payload: IntArray): Boolean = fail(state) {
        explainPayload(payload)
        domainOf(v)
    }

    private fun tightenMin(state: PropagationState, v: Int, bound: Long, payload: IntArray): Boolean =
        bound <= state.intDomains[v].min || state.tightenIntMin(v, bound, reasonFor(state, payload)) ||
            failTighten(state, v, payload)

    private fun tightenMax(state: PropagationState, v: Int, bound: Long, payload: IntArray): Boolean =
        bound >= state.intDomains[v].max || state.tightenIntMax(v, bound, reasonFor(state, payload)) ||
            failTighten(state, v, payload)

    private fun effectiveSnapshot(state: PropagationState): CumulativeEff? {
        val dur = LongArray(n)
        val res = LongArray(n)
        for (i in 0 until n) {
            if (durationVars.isEmpty()) {
                dur[i] = durations[i]
            } else {
                val d = state.intDomains[durationVars[i]]
                if (d.min != d.max) return null
                dur[i] = d.min
            }
            if (resourceVars.isEmpty()) {
                res[i] = resources[i]
            } else {
                val d = state.intDomains[resourceVars[i]]
                if (d.min != d.max) return null
                res[i] = d.min
            }
        }
        val cap = if (capacityVar < 0) {
            capacity
        } else {
            val d = state.intDomains[capacityVar]
            if (d.min != d.max) return null
            d.min
        }
        return CumulativeEff(dur, res, cap)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        failure = null
        if (n == 0) return true
        // Energetic-reasoning pass runs first and on variable durations/heights/capacity — unlike the
        // time-tabling / edge-finding below it does not need a fully fixed snapshot, so it is the only
        // filtering that fires while resource demands or durations are still ranges.
        if (!energeticNaivePass(state)) return false
        if (!profileHeightPass(state)) return false
        val eff = effectiveSnapshot(state) ?: return true
        val effDur = eff.dur
        val effRes = eff.res
        val effCap = eff.cap
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > effCap) {
                return fail(state) {
                    task(i)
                    durGe(i, 1)
                    resGe(i, effCap + 1)
                    capLe(effRes[i] - 1)
                }
            }
        }
        if (!edgeFindingPass(state, effDur, effRes, effCap)) return false
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0L || r == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = r)
        }
        if (!profile.build(effCap)) return fail(state) { overloadAt(profile.overloadTime, -1) }
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            val r = effRes[i]
            if (d == 0L || r == 0L) continue
            val v = starts[i]
            val dom = state.intDomains[v]
            if (dom.min == dom.max) continue
            val oldMin = dom.min
            val oldMax = dom.max
            val lstI = oldMax
            val ectI = oldMin + d
            val ownsMandatory = lstI < ectI
            var newMin = oldMin
            while (newMin <= state.intDomains[v].max) {
                if (profile.overloadsAt(newMin, newMin + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMin++
                } else {
                    break
                }
            }
            if (newMin != oldMin && !tightenMin(state, v, newMin, packed(TIME_TABLE, i, 1, newMin))) return false
            var newMax = state.intDomains[v].max
            while (newMax >= state.intDomains[v].min) {
                if (profile.overloadsAt(newMax, newMax + d, r, effCap, ownsMandatory, lstI, ectI)) {
                    newMax--
                } else {
                    break
                }
            }
            if (newMax != oldMax && !tightenMax(state, v, newMax, packed(TIME_TABLE, i, 0, newMax))) return false
        }
        return true
    }

    private fun minDur(state: PropagationState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.intDomains[durationVars[i]].min

    private fun maxDur(state: PropagationState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.intDomains[durationVars[i]].max

    private fun minHeight(state: PropagationState, i: Int): Long =
        if (resourceVars.isEmpty()) resources[i] else state.intDomains[resourceVars[i]].min

    private fun capMax(state: PropagationState): Long =
        if (capacityVar < 0) capacity else state.intDomains[capacityVar].max

    /**
     * Naive energetic reasoning (after Baptiste–Le Pape–Nuijten). For
     * the running window `[xMin, xMax]` spanned by the tasks seen so far in non-decreasing latest
     * completion order, every such task's whole execution lies inside the window, so their minimum
     * energies sum to at most `capacity · |window|`. That bounds the current task's resource height
     * and duration and lower-bounds the capacity, and an exceeded budget is a contradiction. Sound
     * for any task order; unlike the time-tabling pass it reasons over variable demands/durations.
     */
    @Suppress("ReturnCount")
    private fun energeticNaivePass(state: PropagationState): Boolean {
        val act = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (minDur(state, i) > 0 && minHeight(state, i) >= 0) act.add(i)
        }
        val m = act.size
        if (m == 0) return true
        val ids = IntArray(m) { act[it] }
        val est = LongArray(m) { state.intDomains[starts[ids[it]]].min }
        val lct = LongArray(m) { state.intDomains[starts[ids[it]]].max + maxDur(state, ids[it]) }
        val order = argsortBy(m) { a, b -> lct[a].compareTo(lct[b]) }
        val camax = capMax(state)
        var xMin = Long.MAX_VALUE
        var xMax = Long.MIN_VALUE
        var surface = 0L
        for (p in 0 until m) {
            val k = order[p]
            val i = ids[k]
            if (est[k] < xMin) xMin = est[k]
            if (lct[k] > xMax) xMax = lct[k]
            val len = xMax - xMin
            if (len > 0L) {
                val availSurf = len * camax - surface
                if (availSurf < 0L) return fail(state) { energyReason(-1, OVERLOAD, xMin, xMax) }
                val md = minDur(state, i)
                if (resourceVars.isNotEmpty() && md > 0) {
                    val payload = packed(ENERGY, i, RESOURCE, xMin, xMax)
                    if (!tightenMax(state, resourceVars[i], availSurf / md, payload)) return false
                }
                val mh = minHeight(state, i)
                if (durationVars.isNotEmpty() && mh > 0) {
                    val payload = packed(ENERGY, i, DURATION, xMin, xMax)
                    if (!tightenMax(state, durationVars[i], availSurf / mh, payload)) return false
                }
                if (capacityVar >= 0) {
                    val payload = packed(ENERGY, -1, CAPACITY, xMin, xMax)
                    if (!tightenMin(state, capacityVar, (surface + len - 1L) / len, payload)) return false
                }
            }
            surface += minDur(state, i) * minHeight(state, i)
            if (surface > (xMax - xMin) * camax) return fail(state) { energyReason(-1, OVERLOAD, xMin, xMax) }
        }
        return true
    }

    /**
     * Profile-based resource-height pruning. A mandatory profile built
     * from each present task's compulsory part `[start.max, start.min + minDur)` at its *minimum*
     * demand bounds how tall a task spanning a peak may be: `height ≤ capacity − (peak − ownMin)`.
     * Sharper than the energetic area bound at a tall, narrow compulsory peak inside a long window.
     * Bounds-only on min demands, so it is sound while heights are still ranges.
     */
    @Suppress("ReturnCount")
    private fun profileHeightPass(state: PropagationState): Boolean {
        if (resourceVars.isEmpty()) return true
        val cap = capMax(state)
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val lst = state.intDomains[starts[i]].max
            val ect = state.intDomains[starts[i]].min + minDur(state, i)
            val h = minHeight(state, i)
            if (lst < ect && h > 0) profile.addTask(lst, ect, h)
        }
        if (!profile.build(cap)) return fail(state) { overloadAt(profile.overloadTime, -1) }
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            val lst = state.intDomains[starts[i]].max
            val ect = state.intDomains[starts[i]].min + minDur(state, i)
            if (lst >= ect) continue
            val newUb = cap - (profile.maxLevelOver(lst, ect) - minHeight(state, i))
            if (!tightenMax(state, resourceVars[i], newUb, intArrayOf(HEIGHT, i))) return false
        }
        return true
    }

    private fun edgeFindingPass(state: PropagationState, effDur: LongArray, effRes: LongArray, effCap: Long): Boolean {
        if (n < 2 || effCap == 0L) return true
        val active = IntArrayList()
        for (i in 0 until n) {
            if (!OptPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0 && effRes[i] > 0) active.add(i)
        }
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val ests = LongArray(m) { state.intDomains[starts[taskIds[it]]].min }
        val lcts = LongArray(m) { state.intDomains[starts[taskIds[it]]].max + effDur[taskIds[it]] }
        val energies = LongArray(m) { effDur[taskIds[it]] * effRes[taskIds[it]] }
        val cs = LongArray(m) { effRes[taskIds[it]] }

        val estOrder = argsortBy(m) { a, b -> ests[a].compareTo(ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> lcts[a].compareTo(lcts[b]) }

        val tree = CumulativeThetaTree(n = m, capacity = effCap)
        tree.setLeafOrder(leafPos)
        val omegas = EdgeFindingOmegas(m)

        var k = 0
        while (k < m) {
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta()
            if (envTheta > effCap * tau) return fail(state) { thetaOverload(tau) }
            for (ki in k until m) {
                val i = lctOrder[ki]
                val envWith = tree.envIfActivated(i, ests[i], energies[i])
                if (envWith <= effCap * tau) continue
                if (omegas.thetaSize != k) omegas.build(k, lctOrder, ests, lcts, energies)
                val newEst = omegas.earliestStartAfter(cs[i], effCap) ?: continue
                val task = taskIds[i]
                if (!tightenMin(state, starts[task], newEst, packed(EDGE, task, tau))) return false
            }
        }
        return true
    }

    /** The tasks' bounds, demands and presence as of [atTrail], with the literals a reason collects. */
    private inner class View(val state: PropagationState, val atTrail: Int, val atLevel: Int) {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun est(i: Int): Long = state.domainAt(starts[i], atTrail).min
        fun lst(i: Int): Long = state.domainAt(starts[i], atTrail).max
        fun durMin(i: Int): Long =
            if (durationVars.isEmpty()) durations[i] else state.domainAt(durationVars[i], atTrail).min

        fun durMax(i: Int): Long =
            if (durationVars.isEmpty()) durations[i] else state.domainAt(durationVars[i], atTrail).max

        fun resMin(i: Int): Long =
            if (resourceVars.isEmpty()) resources[i] else state.domainAt(resourceVars[i], atTrail).min

        fun resMax(i: Int): Long =
            if (resourceVars.isEmpty()) resources[i] else state.domainAt(resourceVars[i], atTrail).max

        fun capMax(): Long = if (capacityVar < 0) capacity else state.domainAt(capacityVar, atTrail).max

        fun present(i: Int): Boolean {
            if (presents.isEmpty()) return true
            val lit = presents[i]
            val v = Lit.variable(lit)
            return state.boolPinnedAt(v, atTrail) && state.boolValues[v] == Lit.isPositive(lit)
        }

        private fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        private fun bound(v: Int, lower: Boolean, need: Long) =
            add(state.boundLiteral(v, lower, need, atTrail, atLevel))

        fun startGe(i: Int, need: Long) = bound(starts[i], true, need)
        fun startLe(i: Int, need: Long) = bound(starts[i], false, need)
        fun durGe(i: Int, need: Long) {
            if (durationVars.isNotEmpty()) bound(durationVars[i], true, need)
        }

        fun durLe(i: Int, need: Long) {
            if (durationVars.isNotEmpty()) bound(durationVars[i], false, need)
        }

        fun resGe(i: Int, need: Long) {
            if (resourceVars.isNotEmpty()) bound(resourceVars[i], true, need)
        }

        fun resLe(i: Int, need: Long) {
            if (resourceVars.isNotEmpty()) bound(resourceVars[i], false, need)
        }

        fun capLe(need: Long) {
            if (capacityVar >= 0) bound(capacityVar, false, need)
        }

        fun task(i: Int) {
            if (presents.isNotEmpty()) add(Lit.negate(presents[i]))
        }

        fun domainOf(v: Int) {
            val d = state.domainAt(v, atTrail)
            bound(v, true, d.min)
            bound(v, false, d.max)
        }

        // Task [k] runs over time [t] with at least its least height.
        private fun covers(k: Int, t: Long) {
            task(k)
            durGe(k, durMin(k))
            resGe(k, resMin(k))
            startLe(k, t)
            startGe(k, t - durMin(k) + 1)
        }

        private fun coversPoint(k: Int, t: Long): Boolean =
            present(k) && durMin(k) > 0 && resMin(k) > 0 && lst(k) <= t && t < est(k) + durMin(k)

        /**
         * The compulsory parts at [t], apart from [except]'s, load the resource past [room]: cite the tallest of
         * them until they do, and the capacity they exceed.
         */
        fun overloadAt(t: Long, except: Int, room: Long = capMax()) {
            val covering = (0 until n).filter { it != except && coversPoint(it, t) }.sortedByDescending { resMin(it) }
            var load = 0L
            for (k in covering) {
                if (load > room) break
                covers(k, t)
                load += resMin(k)
            }
            capLe(capMax())
        }

        /**
         * Task [i]'s start moved to [bound] ([lower]) because each start it skipped overloads the profile. When it
         * moved by at most its duration every skipped placement spans the one point just short of [bound], and
         * that point's overload suffices; otherwise every compulsory part meeting a skipped placement is cited.
         */
        fun shaveReason(i: Int, lower: Boolean, bound: Long) {
            task(i)
            val d = durMin(i)
            val r = resMin(i)
            durGe(i, d)
            resGe(i, r)
            val old = if (lower) est(i) else lst(i)
            val shift = if (lower) bound - old else old - bound
            if (shift <= d) {
                val t = if (lower) bound - 1 else bound + d
                if (lower) startGe(i, bound - d) else startLe(i, bound + d)
                overloadAt(t, i, capMax() - r)
                return
            }
            val lo: Long
            val hi: Long
            if (lower) {
                startGe(i, old)
                lo = old
                hi = bound + d - 2
            } else {
                startLe(i, old)
                lo = bound + 1
                hi = old + d - 1
            }
            for (k in 0 until n) {
                if (k == i || !present(k) || durMin(k) == 0L || resMin(k) == 0L) continue
                val partLo = lst(k)
                val partHi = est(k) + durMin(k) - 1
                if (partLo > partHi || partHi < lo || partLo > hi) continue
                task(k)
                durGe(k, durMin(k))
                resGe(k, resMin(k))
                startLe(k, partLo)
                startGe(k, partHi - durMin(k) + 1)
            }
            capLe(capMax())
        }

        private fun lct(k: Int): Long = lst(k) + durMin(k)
        private fun energy(k: Int): Long = durMin(k) * resMin(k)

        private fun theta(tau: Long, except: Int): List<Int> = (0 until n).filter {
            it != except && present(it) && durMin(it) > 0 && resMin(it) > 0 && lct(it) <= tau
        }

        // Task [k] lies in the window from [from] to [tau] with at least its least energy.
        private fun inWindow(k: Int, from: Long, tau: Long) {
            task(k)
            durGe(k, durMin(k))
            durLe(k, durMin(k))
            resGe(k, resMin(k))
            startGe(k, from)
            startLe(k, tau - durMin(k))
        }

        // The est-suffix of [set] that maximizes `C·est + energy`, as its threshold and members.
        private fun envelopeSubset(set: List<Int>, cap: Long): Pair<Long, List<Int>> {
            val sorted = set.sortedByDescending { est(it) }
            var energy = 0L
            var best = Long.MIN_VALUE
            var bestEst = 0L
            for (x in sorted) {
                energy += energy(x)
                val env = cap * est(x) + energy
                if (env > best) {
                    best = env
                    bestEst = est(x)
                }
            }
            return bestEst to sorted.filter { est(it) >= bestEst }
        }

        /** The tasks completing by [tau] carry more energy than the capacity can place after their threshold. */
        fun thetaOverload(tau: Long) {
            val (e, subset) = envelopeSubset(theta(tau, -1), capMax())
            for (k in subset) inWindow(k, e, tau)
            capLe(capMax())
        }

        /**
         * [i] ends after every task of a set completing by [tau]: with it, an est-suffix of the set overflows the
         * capacity up to [tau]. Its new earliest start is the one an est-suffix of that set's energy beyond what
         * fits beside [i] forces.
         */
        fun edgeReason(i: Int, tau: Long) {
            val cap = capMax()
            val c = resMin(i)
            task(i)
            durGe(i, durMin(i))
            resGe(i, c)
            resLe(i, c)
            capLe(cap)
            val theta = theta(tau, i)
            val (e1, overflow) = envelopeSubset(theta + i, cap)
            for (k in overflow) if (k == i) startGe(i, e1) else inWindow(k, e1, tau)
            val byEstDesc = theta.sortedByDescending { est(it) }
            var energy = 0L
            var windowEnd = Long.MIN_VALUE
            var best = Long.MIN_VALUE
            var bestAt = -1
            var bestEnd = 0L
            for ((p, x) in byEstDesc.withIndex()) {
                energy += energy(x)
                windowEnd = maxOf(windowEnd, lct(x))
                val rest = energy - (cap - c) * (windowEnd - est(x))
                if (rest <= 0L) continue
                val b = est(x) + (rest + c - 1L) / c
                if (b > best) {
                    best = b
                    bestAt = p
                    bestEnd = windowEnd
                }
            }
            if (bestAt < 0) return
            // The bound rests on this set's own window, so its members are cited as ending by its own latest
            // completion, not merely by tau.
            val from = est(byEstDesc[bestAt])
            for (p in 0..bestAt) inWindow(byEstDesc[p], from, bestEnd)
        }

        /**
         * The tasks inside `[xMin, xMax]` carry their least energy there: it bounds [i]'s height or duration by
         * what is left ([kind]), the capacity from below, or overloads the window.
         */
        fun energyReason(i: Int, kind: Int, xMin: Long, xMax: Long) {
            for (k in 0 until n) {
                if (k == i || !present(k) || durMin(k) <= 0 || resMin(k) < 0) continue
                if (est(k) < xMin || lst(k) + durMax(k) > xMax) continue
                task(k)
                startGe(k, xMin)
                startLe(k, xMax - durMax(k))
                durLe(k, durMax(k))
                durGe(k, durMin(k))
                resGe(k, resMin(k))
            }
            if (kind != CAPACITY) capLe(capMax())
            if (i < 0) return
            task(i)
            startGe(i, xMin)
            startLe(i, xMax - durMax(i))
            durLe(i, durMax(i))
            if (kind == RESOURCE) durGe(i, durMin(i)) else resGe(i, resMin(i))
        }

        /** [i]'s height is bounded by the tallest stack of compulsory parts over its own compulsory part. */
        fun heightReason(i: Int) {
            val lo = lst(i)
            val hi = est(i) + durMin(i) - 1
            var peak = lo
            var peakLoad = Long.MIN_VALUE
            val points = (0 until n).filter { it != i && present(it) }
                .flatMap { listOf(lst(it), est(it) + durMin(it) - 1) }
            for (t in (points + lo).filter { it in lo..hi }) {
                val load = (0 until n).filter { it != i && coversPoint(it, t) }.sumOf { resMin(it) }
                if (load > peakLoad) {
                    peakLoad = load
                    peak = t
                }
            }
            task(i)
            durGe(i, durMin(i))
            startLe(i, peak)
            startGe(i, peak - durMin(i) + 1)
            for (k in 0 until n) if (k != i && coversPoint(k, peak)) covers(k, peak)
            capLe(capMax())
        }

        fun literals(): IntArray = out.toIntArray()
    }

    private companion object {
        const val TIME_TABLE = 0
        const val EDGE = 1
        const val ENERGY = 2
        const val HEIGHT = 3

        const val RESOURCE = 0
        const val DURATION = 1
        const val CAPACITY = 2
        const val OVERLOAD = 3

        fun packed(kind: Int, a: Int, b: Int, value: Long) =
            intArrayOf(kind, a, b, (value ushr 32).toInt(), value.toInt())

        fun packed(kind: Int, a: Int, value: Long) = intArrayOf(kind, a, (value ushr 32).toInt(), value.toInt())

        fun packed(kind: Int, a: Int, b: Int, x: Long, y: Long) =
            intArrayOf(kind, a, b, (x ushr 32).toInt(), x.toInt(), (y ushr 32).toInt(), y.toInt())

        fun unpack(payload: IntArray, at: Int): Long =
            (payload[at].toLong() shl 32) or (payload[at + 1].toLong() and 0xFFFFFFFFL)
    }
}
