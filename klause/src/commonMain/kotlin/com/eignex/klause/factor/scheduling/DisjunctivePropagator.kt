package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.scheduling.internals.CumulativeThetaTree
import com.eignex.klause.factor.scheduling.internals.MandatoryProfile
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.OptionalPresence
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boolPinnedAt
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact
import com.eignex.klause.util.argsortBy
import com.eignex.klause.util.argsortByIntKey
import com.eignex.klause.util.subExact

/**
 * CP propagator for the no-overlap case of [Cumulative] (`unary = true`, built via [Cumulative.unary]):
 * time-tabling, pairwise detectable precedences, and Θ-tree edge-finding in both directions (P. Vilím,
 * "O(n log n) Filtering Algorithms for Unary Resource Constraint", CPAIOR 2004,
 * https://doi.org/10.1007/978-3-540-24664-0_23).
 *
 * Those passes wait for every duration to be fixed. Before them, a Θ-tree overload check over minimum durations
 * runs while a duration is unfixed or a presence unknown: every solution shortens to one where each task runs
 * exactly its minimum, so present tasks whose minima cannot fit between their earliest start and latest
 * completion conflict, and an unknown task whose minimum would overload them is absent. Tasks sharing one exact
 * signed presence literal are present together, so an unknown literal is ruled out when its tasks' minima would
 * together overload the present tasks.
 *
 * Each deduction cites only the tasks it rests on: a detectable precedence its pair, a time-table shave the
 * tasks whose compulsory parts block the shaved starts, an edge-finding bound the tasks of the overloaded set
 * and of the set the bound comes from, an exclusion the present tasks it would overload and the excluded tasks'
 * own windows and minimum durations, never their presence. All but the detectable precedence cost O(tasks) and
 * are built only when conflict analysis reads them, from the bounds as they stood ([explain]). Bounds are cited
 * only as far as the deduction needs them.
 */
internal class DisjunctivePropagator(
    val intVars: IntArray,
    private val starts: IntArray,
    private val durations: LongArray,
    private val presents: IntArray,
    private val durationVars: IntArray,
    private val n: Int,
    private val reasonPrefix: Int = -1,
) : Propagator {

    override val expensiveBake: Boolean get() = true

    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    // Tasks whose exact signed presence literal guards another task too, in runs of one literal: run g holds
    // guarded[guardRuns[g] until guardRuns[g + 1]]. Both are empty unless a literal repeats.
    private val guarded: IntArray
    private val guardRuns: IntArray

    init {
        val order = argsortByIntKey(presents.size) { presents[it] }
        val tasks = IntArrayList()
        val runs = IntArrayList()
        var from = 0
        while (from < order.size) {
            var to = from + 1
            while (to < order.size && presents[order[to]] == presents[order[from]]) to++
            if (to - from >= 2) {
                runs.add(tasks.size)
                for (k in from until to) tasks.add(order[k])
            }
            from = to
        }
        if (!runs.isEmpty()) runs.add(tasks.size)
        guarded = tasks.toIntArray()
        guardRuns = runs.toIntArray()
    }

    /** Effective per-task durations, or null while any duration var is unfixed (propagation then waits). */
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
        state.propagatorFailures[this] ?: OptionalPresence.withPresencePremises(
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
        state.propagatorFailures.remove(this)
        if (n == 0) return true
        if (!overloadCheck(state)) return false
        if (!guardOverloadCheck(state)) return false
        val effDur = effDurOrNull(state) ?: return true
        if (!timeTable(state, effDur)) return false
        if (!detectablePrecedences(state, effDur)) return false
        if (!edgeFinding(state, effDur)) return false
        return true
    }

    private fun now(state: PropagationState) = View(state, state.undo.size, state.currentLevel)

    private fun checkSubsetWork(state: PropagationState) {
        if (reasonPrefix >= 0) state.checkFiniteWork()
    }

    // A deduction's reason, recorded for [explain] to build from [payload] if analysis reads it.
    private fun reasonFor(state: PropagationState, payload: IntArray): IntArray? = when {
        state.currentLevel == 0 -> null

        state.undoLogging -> state.lazyReason(if (reasonPrefix < 0) payload else intArrayOf(reasonPrefix) + payload)

        else -> now(state).run {
            explainPayload(payload)
            literals()
        }
    }

    private fun View.explainPayload(payload: IntArray) {
        when (payload[0]) {
            TIME_TABLE -> timeTableReason(payload[1], lower = payload[2] == 1, unpack(payload, 3))
            EDGE -> edgeReason(reversed = payload[1] == 1, cand = payload[2], tau = unpack(payload, 3))
            EXCLUSION -> excludedBy(cand = payload[1], tau = unpack(payload, 3))
            else -> guardExcludedBy(member = payload[1], tau = unpack(payload, 3))
        }
    }

    private fun minDuration(state: PropagationState, i: Int): Long =
        if (durationVars.isEmpty()) durations[i] else state.intDomains[durationVars[i]].min

    private fun isUnknown(state: PropagationState, i: Int): Boolean =
        !OptionalPresence.isDefinitelyPresent(presents, i, state) &&
            !OptionalPresence.isDefinitelyAbsent(presents, i, state)

    /**
     * Fail on present tasks whose minimum durations overload a window, and exclude each unknown task whose minimum
     * would. With fixed durations and no unknown task, edge-finding already checks the overload.
     */
    private fun overloadCheck(state: PropagationState): Boolean {
        if (durationVars.isEmpty() && presents.isEmpty()) return true
        val mandatory = IntArrayList()
        val optional = IntArrayList()
        var durationsFixed = true
        for (i in 0 until n) {
            if (durationVars.isNotEmpty() && state.intDomains[durationVars[i]].let { it.min != it.max }) {
                durationsFixed = false
            }
            if (minDuration(state, i) <= 0L) continue
            if (OptionalPresence.isDefinitelyPresent(presents, i, state)) {
                mandatory.add(i)
            } else if (!OptionalPresence.isDefinitelyAbsent(presents, i, state)) {
                optional.add(i)
            }
        }
        if (mandatory.isEmpty() || (durationsFixed && optional.isEmpty())) return true
        val windows = overloadWindows(state, mandatory, optional) ?: return true
        return sweepOverload(state, windows)
    }

    // The tasks an overload sweep reads, mandatory first, indexed by position.
    private class OverloadWindows(
        val tasks: IntArray,
        val mandatoryCount: Int,
        val ests: LongArray,
        val lcts: LongArray,
        val durs: LongArray,
    )

    // Null when a completion time or envelope is not representable: the check then declines, which only weakens it.
    private fun overloadWindows(
        state: PropagationState,
        mandatory: IntArrayList,
        optional: IntArrayList,
    ): OverloadWindows? {
        val m = mandatory.size + optional.size
        val tasks = IntArray(m) { if (it < mandatory.size) mandatory[it] else optional[it - mandatory.size] }
        val ests = LongArray(m)
        val lcts = LongArray(m)
        val durs = LongArray(m)
        try {
            var energy = 0L
            for (t in 0 until m) {
                val dom = state.intDomains[starts[tasks[t]]]
                durs[t] = minDuration(state, tasks[t])
                ests[t] = dom.min
                lcts[t] = addExact(dom.max, durs[t])
                energy = addExact(energy, durs[t])
            }
            addExact(ests.max(), energy)
            // The tree's empty-subtree sentinel must stay below every envelope it is compared with.
            if (subExact(ests.min(), energy) <= CumulativeThetaTree.NO_ENV) return null
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        return OverloadWindows(tasks, mandatory.size, ests, lcts, durs)
    }

    // Sweep completion times upward: Θ holds the present tasks completing by τ, and each unknown task completing by
    // τ is tested against it there and at every later τ.
    private fun sweepOverload(state: PropagationState, w: OverloadWindows): Boolean {
        val m = w.tasks.size
        val estOrder = argsortBy(m) { a, b -> w.ests[a].compareTo(w.ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> w.lcts[a].compareTo(w.lcts[b]) }
        val tree = CumulativeThetaTree(n = m, capacity = 1L)
        tree.setLeafOrder(leafPos)
        val pending = IntArrayList()
        var k = 0
        while (k < m) {
            val tau = w.lcts[lctOrder[k]]
            while (k < m && w.lcts[lctOrder[k]] == tau) {
                val t = lctOrder[k]
                if (t < w.mandatoryCount) tree.activate(t, w.ests[t], w.durs[t]) else pending.add(t)
                k++
            }
            if (tree.envOfTheta() > tau) {
                state.propagatorFailures[this] = now(state).run {
                    overloadedBy(reversed = false, tau)
                    literals()
                }
                return false
            }
            if (!excludeOverloading(state, w, tree, pending, tau)) return false
        }
        return true
    }

    // Exclude each pending unknown task that would overload Θ by [tau]; keep the rest pending for later windows.
    private fun excludeOverloading(
        state: PropagationState,
        w: OverloadWindows,
        tree: CumulativeThetaTree,
        pending: IntArrayList,
        tau: Long,
    ): Boolean {
        var kept = 0
        for (r in 0 until pending.size) {
            val t = pending[r]
            val task = w.tasks[t]
            // An earlier exclusion in this sweep may have fixed this presence through a shared literal.
            if (!isUnknown(state, task)) continue
            if (tree.envIfActivated(t, w.ests[t], w.durs[t]) <= tau) {
                pending[kept++] = t
                continue
            }
            val lit = presents[task]
            val reason = reasonFor(state, packed(EXCLUSION, task, 0, tau))
            if (!state.pinBool(Lit.variable(lit), !Lit.isPositive(lit), reason)) return false
        }
        pending.truncateTo(kept)
        return true
    }

    // Rule out each unknown presence literal whose tasks' minimum durations, all present together, overload a window
    // with the present tasks. One sweep per literal over the present tasks and its own.
    private fun guardOverloadCheck(state: PropagationState): Boolean {
        if (guarded.isEmpty()) return true
        var present: IntArrayList? = null
        for (g in 0 until guardRuns.size - 1) {
            // An earlier exclusion may have fixed this literal through its complement's run.
            if (!isUnknown(state, guarded[guardRuns[g]])) continue
            val members = IntArrayList()
            for (r in guardRuns[g] until guardRuns[g + 1]) {
                if (minDuration(state, guarded[r]) > 0L) members.add(guarded[r])
            }
            if (members.size < 2) continue
            val mandatory = present ?: presentTasks(state).also { present = it }
            val windows = overloadWindows(state, mandatory, members) ?: continue
            val tau = overloadTime(windows) ?: continue
            val lit = presents[members[0]]
            val reason = reasonFor(state, packed(GUARD, members[0], 0, tau))
            if (!state.pinBool(Lit.variable(lit), !Lit.isPositive(lit), reason)) return false
        }
        return true
    }

    private fun presentTasks(state: PropagationState): IntArrayList {
        val out = IntArrayList()
        for (i in 0 until n) {
            if (minDuration(state, i) > 0L && OptionalPresence.isDefinitelyPresent(presents, i, state)) out.add(i)
        }
        return out
    }

    // The earliest completion time by which all of [w]'s tasks cannot fit, or null when they fit.
    private fun overloadTime(w: OverloadWindows): Long? {
        val m = w.tasks.size
        val estOrder = argsortBy(m) { a, b -> w.ests[a].compareTo(w.ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> w.lcts[a].compareTo(w.lcts[b]) }
        val tree = CumulativeThetaTree(n = m, capacity = 1L)
        tree.setLeafOrder(leafPos)
        var k = 0
        while (k < m) {
            val tau = w.lcts[lctOrder[k]]
            while (k < m && w.lcts[lctOrder[k]] == tau) {
                val t = lctOrder[k++]
                tree.activate(t, w.ests[t], w.durs[t])
            }
            if (tree.envOfTheta() > tau) return tau
        }
        return null
    }

    // Tightening [task]'s start failed against its other bound or a hole: the deduction's reason plus its own domain.
    private fun failWith(state: PropagationState, task: Int, build: View.() -> Unit): Boolean {
        state.propagatorFailures[this] = now(state).run {
            build()
            ownDomain(task)
            literals()
        }
        return false
    }

    /** Build the mandatory profile from each task's `[lst, ect)` compulsory part; fail on
     *  level > 1; shave any non-fixed task's start endpoints whose placement would overlap the profile. */
    private fun timeTable(state: PropagationState, effDur: LongArray): Boolean {
        val profile = mandatoryProfile(state, effDur)
        if (!profile.build(cap = 1L)) {
            state.propagatorFailures[this] = now(state).run {
                overloadAt(profile.overloadTime)
                literals()
            }
            return false
        }
        for (i in 0 until n) {
            checkSubsetWork(state)
            if (!OptionalPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            if (!shaveAgainstProfile(state, profile, i, d)) return false
        }
        return true
    }

    // The profile's build and each task's shave are separate methods so each compiles apart: in one body every loop
    // entered on-stack recompiled the whole of it.
    private fun mandatoryProfile(state: PropagationState, effDur: LongArray): MandatoryProfile {
        val profile = MandatoryProfile()
        for (i in 0 until n) {
            checkSubsetWork(state)
            if (!OptionalPresence.isDefinitelyPresent(presents, i, state)) continue
            val d = effDur[i]
            if (d == 0L) continue
            val dom = state.intDomains[starts[i]]
            profile.addTask(lst = dom.max, ect = dom.min + d, resource = 1L)
        }
        return profile
    }

    // Shave task [i]'s start endpoints that would add a unit overlap with the mandatory profile.
    private fun shaveAgainstProfile(state: PropagationState, profile: MandatoryProfile, i: Int, d: Long): Boolean {
        val v = starts[i]
        val dom = state.intDomains[v]
        if (dom.min == dom.max) return true
        val lstI = dom.max
        val ectI = dom.min + d
        val ownsMandatory = lstI < ectI
        var newMin = dom.min
        while (newMin <= state.intDomains[v].max) {
            checkSubsetWork(state)
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
            checkSubsetWork(state)
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
        return true
    }

    /** Pairwise rule: if `est_i + dur_i > lst_j`, task i can't end before j must start;
     *  i must come strictly after j. Tighten `start_i.min ≥ est_j + dur_j`. */
    private fun detectablePrecedences(state: PropagationState, effDur: LongArray): Boolean {
        for (i in 0 until n) {
            checkSubsetWork(state)
            if (effDur[i] == 0L) continue
            if (!OptionalPresence.isDefinitelyPresent(presents, i, state)) continue
            val vi = starts[i]
            val di = state.intDomains[vi]
            var newMinI = di.min
            var by = -1
            for (j in 0 until n) {
                checkSubsetWork(state)
                if (j == i) continue
                if (effDur[j] == 0L) continue
                if (!OptionalPresence.isDefinitelyPresent(presents, j, state)) continue
                val dj = state.intDomains[starts[j]]
                if (di.min + effDur[i] > dj.max) {
                    if (dj.min + effDur[j] > di.max) {
                        state.propagatorFailures[this] = now(state).run {
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
        val active = edgeFindingTasks(state, effDur)
        val m = active.size
        if (m < 2) return true

        val taskIds = IntArray(m) { active[it] }
        val durs = LongArray(m) { effDur[taskIds[it]] }
        val ests = LongArray(m)
        val lcts = LongArray(m)
        fillWindows(state, taskIds, durs, ests, lcts, reversed)
        val energies = LongArray(m) { durs[it] }

        val estOrder = argsortBy(m) { a, b -> ests[a].compareTo(ests[b]) }
        val leafPos = IntArray(m)
        for (leafIdx in 0 until m) leafPos[estOrder[leafIdx]] = leafIdx
        val lctOrder = argsortBy(m) { a, b -> lcts[a].compareTo(lcts[b]) }

        val tree = CumulativeThetaTree(n = m, capacity = 1L)
        tree.setLeafOrder(leafPos)

        val sweep = EdgeSweep(tree, taskIds, durs, ests, energies, lctOrder, reversed)
        var k = 0
        while (k < m) {
            checkSubsetWork(state)
            val tau = lcts[lctOrder[k]]
            while (k < m && lcts[lctOrder[k]] == tau) {
                val j = lctOrder[k]
                tree.activate(j, ests[j], energies[j])
                k++
            }
            val envTheta = tree.envOfTheta()
            if (envTheta > tau) {
                state.propagatorFailures[this] = now(state).run {
                    overloadedBy(reversed, tau)
                    literals()
                }
                return false
            }
            if (!pushOutOfTheta(state, sweep, k, tau, envTheta)) return false
        }
        return true
    }

    // The arrays one edge-finding sweep reads, indexed by position among its active tasks.
    private class EdgeSweep(
        val tree: CumulativeThetaTree,
        val taskIds: IntArray,
        val durs: LongArray,
        val ests: LongArray,
        val energies: LongArray,
        val lctOrder: IntArray,
        val reversed: Boolean,
    )

    private fun edgeFindingTasks(state: PropagationState, effDur: LongArray): IntArrayList {
        val active = IntArrayList()
        for (i in 0 until n) {
            checkSubsetWork(state)
            if (!OptionalPresence.isDefinitelyPresent(presents, i, state)) continue
            if (effDur[i] > 0) active.add(i)
        }
        return active
    }

    @Suppress("LongParameterList")
    private fun fillWindows(
        state: PropagationState,
        taskIds: IntArray,
        durs: LongArray,
        ests: LongArray,
        lcts: LongArray,
        reversed: Boolean,
    ) {
        for (t in taskIds.indices) {
            checkSubsetWork(state)
            val dom = state.intDomains[starts[taskIds[t]]]
            if (!reversed) {
                ests[t] = dom.min
                lcts[t] = dom.max + durs[t]
            } else {
                ests[t] = -(dom.max + durs[t])
                lcts[t] = -dom.min
            }
        }
    }

    // Push every task still outside Θ (from position [from] of the lct order) whose addition overloads [tau] past
    // Θ's envelope [envTheta].
    private fun pushOutOfTheta(
        state: PropagationState,
        sweep: EdgeSweep,
        from: Int,
        tau: Long,
        envTheta: Long,
    ): Boolean {
        val tree = sweep.tree
        val reversed = sweep.reversed
        for (ki in from until sweep.lctOrder.size) {
            checkSubsetWork(state)
            val cand = sweep.lctOrder[ki]
            tree.activate(cand, sweep.ests[cand], sweep.energies[cand])
            val envWith = tree.envOfTheta()
            tree.deactivate(cand)
            if (envWith <= tau) continue
            val bound = envTheta
            val task = sweep.taskIds[cand]
            val v = starts[task]
            val payload = packed(EDGE, if (reversed) 1 else 0, task, tau)
            if (!reversed) {
                if (bound > state.intDomains[v].min &&
                    !state.tightenIntMin(v, bound, reasonFor(state, payload))
                ) {
                    return failWith(state, task) { edgeReason(false, task, tau) }
                }
            } else {
                val newMax = -bound - sweep.durs[cand]
                if (newMax < state.intDomains[v].max &&
                    !state.tightenIntMax(v, newMax, reasonFor(state, payload))
                ) {
                    return failWith(state, task) { edgeReason(true, task, tau) }
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
            lastsAtLeast(i)
            if (presents.isNotEmpty()) add(Lit.negate(presents[i]))
        }

        private fun lastsAtLeast(i: Int) {
            if (durationVars.isNotEmpty()) add(state.boundLiteral(durationVars[i], true, dur(i), atTrail, atLevel))
        }

        /** [before] precedes [after]: [after] cannot end before [before]'s latest start. */
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
                if (k == except || !present(k) || dur(k) <= 0L) continue
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

        /**
         * Unknown task [cand], completing by [tau], would overload the present tasks completing by [tau]: the
         * overloaded set's windows and minimum durations, and the presence of its members other than [cand].
         */
        fun excludedBy(cand: Int, tau: Long) {
            val withCand = theta(reversed = false, tau, except = cand)
            withCand.add(cand)
            val (e, overload) = ectSubset(withCand, reversed = false)
            for (k in 0 until overload.size) {
                val x = overload[k]
                if (x == cand) lastsAtLeast(x) else task(x)
                startGe(x, e)
                startLe(x, tau - dur(x))
            }
        }

        // The tasks sharing [member]'s presence literal and completing by [tau] would together overload the present
        // tasks completing by [tau]: the overloaded set's windows and minimum durations, and the presence of its
        // present members, never the shared literal.
        fun guardExcludedBy(member: Int, tau: Long) {
            val guard = presents[member]
            val set = theta(reversed = false, tau, except = -1)
            for (k in 0 until n) {
                if (presents[k] == guard && dur(k) > 0L && lct(k, reversed = false) <= tau) set.add(k)
            }
            val (e, overload) = ectSubset(set, reversed = false)
            for (k in 0 until overload.size) {
                val x = overload[k]
                if (presents[x] == guard) lastsAtLeast(x) else task(x)
                startGe(x, e)
                startLe(x, tau - dur(x))
            }
        }

        fun literals(): IntArray = out.toIntArray()
    }

    private companion object {
        const val TIME_TABLE = 0
        const val EDGE = 1
        const val EXCLUSION = 2
        const val GUARD = 3

        fun packed(kind: Int, a: Int, b: Int, value: Long) =
            intArrayOf(kind, a, b, (value ushr 32).toInt(), value.toInt())

        fun unpack(payload: IntArray, at: Int): Long =
            (payload[at].toLong() shl 32) or (payload[at + 1].toLong() and 0xFFFFFFFFL)
    }
}
