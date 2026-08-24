package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.LpRowPremises
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.bound.CumulativeEnergeticBound
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntObjectMap

/**
 * Sound LP makespan lower bound for the scheduling globals ([Cumulative]).
 *
 * The start-variable LP has no resource–time coupling, so [CpToLpRelaxation] drops every scheduling
 * global and the only scheduling-aware reasoning, [CumulativeEnergeticBound], is a feasibility test
 * that can never tighten the objective. This class restores an *objective* contribution by the one
 * inequality the start-variable LP can carry: an **energetic makespan bound**.
 *
 * ## The bound
 * Fix a makespan variable `M` that is provably ≥ the end of every task in a set `T` (`M ≥ startᵢ +
 * durᵢ`), and a left edge `t1 ≤ M`. Every task `i ∈ T` runs entirely inside `[t1, M]` except for the
 * part it can left-shift before `t1`, so its energy inside the window is at least
 * `rᵢ · clamp(ectᵢ − t1, 0, durᵢ)` (`ectᵢ = estᵢ + durᵢ`). The window holds at most `cap·(M − t1)`
 * energy, hence
 *
 * ```
 *   cap·M  ≥  cap·t1 + Σ_{i∈T} rᵢ · clamp(estᵢ + durᵢ − t1, 0, durᵢ)
 * ```
 *
 * is valid at every integer solution. With `t1 = min estᵢ` (all energy counts) this is the plain
 * area / energy bound; sweeping `t1` over the task earliest-starts adds the
 * energetic windowing on top (the strongest left edge wins). Because the row is a single column over
 * `M`, only the strongest `t1` is ever binding, so each `(global, M)` pair emits exactly **one** row
 * carrying the best edge — and that keeps the row count structural (warm-start safe).
 *
 * The unary (no-overlap) case is the `cap = 1`, `rᵢ = 1` special case, where the bound collapses to the
 * one-machine `M ≥ minEst + Σ durᵢ`.
 *
 * ## Soundness
 * A missing or loose scheduling relaxation only loosens the objective bound; an over-tight one
 * silently corrupts optima. So the makespan variable is never *guessed*: [makespanLinks] verifies
 * `M ≥ startᵢ + durᵢ` from the actual `Linear` rows (`M − startᵢ ≥ durᵢ`, any orientation) and from
 * `ArrayMinMax(max)` whose operands are the task ends — a task contributes to `M`'s row only when the
 * link is proven. Energy uses each task's **minimum** resource and its (constant) duration, capacity
 * uses its declared **maximum**, and `t1 ≤ M` is enforced because `M ≥ ectᵢ` for every counted task.
 * All products are checked ([mulExact] / [addExact]); an overflow degrades the row to the trivial
 * declared bound `M ≥ minDeclared`, never to a wrapped value. The row is [LpModel.rowGlobal] when its
 * earliest-starts are still the declared ones (so it holds at every solution) and otherwise records
 * the live start lower bounds it leaned on as [LpRowPremises], mirroring the live-big-M `ReifiedLinear`
 * rows.
 *
 * Optional tasks (presence literals) and variable durations are skipped — the makespan link is then
 * conditional / not linearly expressible — which only drops their energy from the bound (sound).
 */
internal class CumulativeRelaxation(
    private val problem: Problem,
    /** Project [Cumulative] factors into makespan plans. */
    private val includeCumulative: Boolean = true,
    /** Also project each constant-size [Diffn] onto both axes as a cumulative: the
     *  x-axis is `start = xs, dur = widths, res = heights, cap = the max y-extent`, and symmetrically
     *  for the y-axis. A non-overlapping packing keeps the per-slice perpendicular demand within the
     *  bounding-box extent, so the energetic row (whose `t1 = min-est` case is the area bound
     *  `Σ wᵢ·hᵢ ≤ W·H`) lower-bounds a strip-length / extent variable. Using the *maximum* perpendicular
     *  extent as capacity only loosens, so it stays sound; off by default. */
    private val includeDiffn: Boolean = false,
) {

    /**
     * One emit plan: a verified makespan variable [makespanVar] (proven ≥ the end of every task in
     * [startVars]) and that task set's energy data. Structural and node-independent, so the
     * relaxation emits exactly one row per plan at every node — only its right-hand side moves with
     * the live earliest-starts, so the row count stays stable for warm starting.
     */
    internal class AreaPlan(
        val makespanVar: Int,
        /** Declared capacity upper bound (`> 0`); the energy ceiling per unit time. */
        val capacity: Long,
        val startVars: IntArray,
        /** Per-task constant duration (`> 0`). */
        val durations: LongArray,
        /** Per-task minimum resource demand (`> 0`). */
        val resources: LongArray,
    )

    /** The validated, live right-hand side of one [AreaPlan]'s row at a node. */
    internal class RowSpec(val rhs: Long, val global: Boolean, val premises: LpRowPremises?)

    val plans: List<AreaPlan> = buildPlans()

    val applicable: Boolean get() = plans.isNotEmpty()

    /**
     * The makespan row for [plan] under the live [domains]: `capacity·M ≥ rhs`. The `rhs` is the best
     * energetic left edge over the live earliest-starts; on overflow it degrades to the declared
     * `capacity · M.declaredMin`. The row is global iff every counted task's live earliest-start is
     * still its declared one; otherwise the tightened starts it used are recorded as premises.
     */
    fun rowSpec(plan: AreaPlan, domains: RelaxationDomains): RowSpec {
        val cap = plan.capacity
        val n = plan.startVars.size
        val estLive = LongArray(n) { domains.intDomain(plan.startVars[it]).min }
        val estDecl = LongArray(n) { problem.requireFiniteIntDomains()[plan.startVars[it]].min }
        val floor = cap * problem.requireFiniteIntDomains()[plan.makespanVar].min

        val energetic = try {
            energeticRhs(cap, estLive, plan.durations, plan.resources)
        } catch (_: LpOverflowException) {
            null
        }
        if (energetic == null || energetic <= floor) {
            return RowSpec(floor, global = true, premises = null)
        }
        var allDeclared = true
        for (k in 0 until n) if (estLive[k] != estDecl[k]) allDeclared = false
        val premises = if (allDeclared) null else livePremises(plan.startVars, estLive, estDecl)
        return RowSpec(energetic, global = allDeclared, premises = premises)
    }

    /**
     * `max over t1 of cap·t1 + Σ rᵢ·clamp(ectᵢ − t1, 0, durᵢ)`. The maximand is piecewise-linear in
     * `t1` with `+` → `−` slope changes only at the earliest-starts, so its maximum over the valid
     * range `t1 ≤ max ectᵢ` is attained at some `estᵢ` (or the right endpoint `max ectᵢ`). The sweep
     * visits the earliest-starts in ascending order, maintaining the full-energy / partial-energy
     * partition incrementally in `O(n log n)`. Throws [LpOverflowException] on any product overflow.
     */
    private fun energeticRhs(cap: Long, est: LongArray, dur: LongArray, res: LongArray): Long {
        val n = est.size
        val ect = LongArray(n) { addExact(est[it], dur[it]) }
        // A = {est ≥ t1}: full energy rᵢ·durᵢ. B = {est < t1 < ect}: rᵢ·(ectᵢ − t1). C = {ect ≤ t1}: 0.
        var fullEnergy = 0L // Σ_A rᵢ·durᵢ
        var bSumREct = 0L // Σ_B rᵢ·ectᵢ
        var bSumR = 0L // Σ_B rᵢ
        for (k in 0 until n) fullEnergy = addExact(fullEnergy, mulExact(res[k], dur[k]))

        val estOrder = (0 until n).sortedBy { est[it] }
        val ectOrder = (0 until n).sortedBy { ect[it] }
        var ei = 0 // next est event (task entering B)
        var ci = 0 // next ect event (task leaving B for C)
        var maxEct = Long.MIN_VALUE
        for (k in 0 until n) if (ect[k] > maxEct) maxEct = ect[k]

        // Candidate left edges: the distinct earliest-starts, then the right endpoint max ectᵢ.
        var best = Long.MIN_VALUE
        var idx = 0
        while (idx <= n) {
            val t1 = if (idx < n) est[estOrder[idx]] else maxEct
            if (idx < n && idx > 0 && est[estOrder[idx]] == est[estOrder[idx - 1]]) {
                idx++
                continue // dedupe equal earliest-starts
            }
            // Advance events strictly below / at t1: est_k < t1 enters B; ect_k ≤ t1 leaves to C.
            while (ei < n && est[estOrder[ei]] < t1) {
                val k = estOrder[ei]
                fullEnergy = subExact(fullEnergy, mulExact(res[k], dur[k]))
                bSumREct = addExact(bSumREct, mulExact(res[k], ect[k]))
                bSumR = addExact(bSumR, res[k])
                ei++
            }
            while (ci < n && ect[ectOrder[ci]] <= t1) {
                val k = ectOrder[ci]
                if (est[k] < t1) { // only tasks that had entered B leave to C
                    bSumREct = subExact(bSumREct, mulExact(res[k], ect[k]))
                    bSumR = subExact(bSumR, res[k])
                }
                ci++
            }
            // energy(t1) = Σ_A rᵢ·durᵢ + Σ_B rᵢ·ectᵢ − t1·Σ_B rᵢ.
            val energy = addExact(fullEnergy, subExact(bSumREct, mulExact(t1, bSumR)))
            val f = addExact(mulExact(cap, t1), energy)
            if (f > best) best = f
            idx++
        }
        return best
    }

    /** Records the tightened (live) earliest-starts the row leaned on as `startᵢ ≥ estLiveᵢ` atoms. */
    private fun livePremises(startVars: IntArray, estLive: LongArray, estDecl: LongArray): LpRowPremises {
        val pv = IntArrayList()
        val pt = LongArrayList()
        var count = 0
        for (k in startVars.indices) {
            if (estLive[k] == estDecl[k]) continue
            pv.add(startVars[k])
            pt.add(estLive[k])
            count++
        }
        return LpRowPremises(pv.toIntArray(), BooleanArray(count) { false }, pt.toLongArray())
    }

    // Structural plan construction: once, at problem load.

    private fun buildPlans(): List<AreaPlan> {
        val scheds = schedulingFactors()
        if (scheds.isEmpty()) return emptyList()
        val links = makespanLinks()
        val out = ArrayList<AreaPlan>()
        for (s in scheds) out.addAll(plansFor(s, links))
        return out
    }

    /** Plans for one scheduling factor: a makespan variable ≥ the end of every task it covers. */
    private fun plansFor(s: Sched, links: MakespanLinks): List<AreaPlan> {
        // makespanVar → covered task local-indices.
        val cover = MutableIntObjectMap<IntArrayList>()
        for (i in s.starts.indices) {
            if (s.dur[i] <= 0 || s.res[i] <= 0) continue
            links.endUpperBoundsOf(s.starts[i], s.dur[i]).forEach { m ->
                cover.getOrPut(m) { IntArrayList() }.add(i)
            }
        }
        if (cover.isEmpty()) return emptyList()
        // Strongest coverage first; deterministic var-id tie-break. Keep at most MAX_MAKESPAN_VARS.
        val coverEntries = ArrayList<Pair<Int, IntArrayList>>(cover.size)
        cover.forEach { m, idxs -> coverEntries.add(m to idxs) }
        val ranked = coverEntries
            .filter { it.second.size >= MIN_COVER }
            .sortedWith(compareByDescending<Pair<Int, IntArrayList>> { it.second.size }.thenBy { it.first })
            .take(MAX_MAKESPAN_VARS)
        return ranked.map { (m, idxs) ->
            val k = idxs.size
            AreaPlan(
                makespanVar = m,
                capacity = s.cap,
                startVars = IntArray(k) { s.starts[idxs[it]] },
                durations = LongArray(k) { s.dur[idxs[it]] },
                resources = LongArray(k) { s.res[idxs[it]] },
            )
        }
    }

    /** A scheduling factor normalized to constant per-task duration / minimum demand / max capacity. */
    private class Sched(val starts: IntArray, val dur: LongArray, val res: LongArray, val cap: Long)

    private fun schedulingFactors(): List<Sched> {
        val out = ArrayList<Sched>()
        for (f in problem.factors) {
            when (f) {
                is Cumulative -> {
                    if (!includeCumulative) continue
                    // Variable durations: M ≥ startᵢ + durᵢ is not a 2-var linear link. Optional tasks:
                    // the link is conditional on presence. Both are skipped (drops energy, sound).
                    if (f.durationVars.isNotEmpty() || f.presents.isNotEmpty()) continue
                    if (f.starts.size > MAX_TASKS) continue
                    val cap = if (f.capacityVar >=
                        0
                    ) {
                        problem.requireFiniteIntDomains()[f.capacityVar].max
                    } else {
                        f.capacity
                    }
                    if (cap <= 0L) continue
                    val res = LongArray(f.starts.size) { i ->
                        if (f.resourceVars.isNotEmpty()) {
                            problem.requireFiniteIntDomains()[f.resourceVars[i]].min
                        } else {
                            f.resources[i]
                        }
                    }
                    out.add(Sched(f.starts, f.durations, res, cap))
                }

                is Diffn -> if (includeDiffn) addDiffnScheds(f, out)

                else -> Unit
            }
        }
        return out
    }

    /** Project a constant-size [Diffn] onto both axes (see [includeDiffn]): the x-axis cumulative has
     *  capacity the maximum y-extent and vice versa. Variable-size diffn is skipped (a variable width
     *  is not a constant duration); zero-extent axes contribute no resource room and are dropped. */
    private fun addDiffnScheds(f: Diffn, out: ArrayList<Sched>) {
        if (f.widthVars != null || f.heightVars != null) return
        if (f.xs.size > MAX_TASKS) return
        val yExtent = perpendicularExtent(f.ys, f.heights)
        if (yExtent > 0L) out.add(Sched(f.xs, f.widths, f.heights.copyOf(), yExtent))
        val xExtent = perpendicularExtent(f.xs, f.widths)
        if (xExtent > 0L) out.add(Sched(f.ys, f.heights, f.widths.copyOf(), xExtent))
    }

    /** Maximum extent of the bounding box along an axis: `max(coordᵢ.max + sizeᵢ) − min(coordᵢ.min)`.
     *  An upper bound on the perpendicular resource room, so using it as the cumulative capacity only
     *  loosens the energetic bound (sound). */
    private fun perpendicularExtent(coords: IntArray, sizes: LongArray): Long {
        var hi = Long.MIN_VALUE
        var lo = Long.MAX_VALUE
        for (i in coords.indices) {
            val d = problem.requireFiniteIntDomains()[coords[i]]
            if (d.max + sizes[i] > hi) hi = d.max + sizes[i]
            if (d.min < lo) lo = d.min
        }
        return hi - lo
    }

    /**
     * Verified "`w ≥ v + c`" links harvested once from the problem: two-variable `Linear` rows that
     * imply it directly, plus `ArrayMinMax(max)` reverse edges (`result ≥ operand`). For a task with
     * start `s` and duration `d`, [endUpperBoundsOf] returns every variable provably ≥ `s + d` — i.e.
     * provably ≥ the task's end — by closing the direct links through the max edges.
     */
    private inner class MakespanLinks {
        /** `geFrom[v]` = pairs `(w, c)` with a proven `w ≥ v + c`, from two-variable Linear rows. */
        private val geFrom = MutableIntObjectMap<ArrayList<LongArray>>() // value: [w, c]

        /** `maxResultsOf[operand]` = `ArrayMinMax(max)` results, each ≥ that operand. */
        private val maxResultsOf = MutableIntObjectMap<IntArrayList>()

        init {
            for (f in problem.factors) {
                when (f) {
                    is Linear -> if (f.isIntegerCore && f.vars.size == 2) addLinearLinks(f)

                    is ArrayMinMax -> if (f.max) {
                        for (x in f.xs) maxResultsOf.getOrPut(x) { IntArrayList() }.add(f.result)
                    }

                    else -> Unit
                }
            }
        }

        /** Record every `w ≥ v + c` implied by a two-variable [Linear] `coeffA·a + coeffB·b op bound`. */
        private fun addLinearLinks(f: Linear) {
            // Only ±1-coefficient rows produce a link; a coefficient outside ±1 (including any beyond
            // Int range) matches neither pattern below and adds nothing.
            val a = f.vars[0]
            val b = f.vars[1]
            val ca = f.coeff(0)
            val cb = f.coeff(1)
            val bound = f.bound
            // "≥ bound" holds for GE/EQ; "≤ bound" (⇔ "≥ −bound" after negating) holds for LE/EQ.
            if (f.op == LinearOp.GE || f.op == LinearOp.EQ) addGeForm(a, ca, b, cb, bound)
            if (f.op == LinearOp.LE || f.op == LinearOp.EQ) addGeForm(a, -ca, b, -cb, -bound)
        }

        /** From `ca·a + cb·b ≥ rhs`, record `w ≥ s + rhs` when the coefficients are a `(+1, −1)` pair. */
        private fun addGeForm(a: Int, ca: Long, b: Int, cb: Long, rhs: Long) {
            if (ca == 1L && cb == -1L) add(w = a, v = b, c = rhs)
            if (ca == -1L && cb == 1L) add(w = b, v = a, c = rhs)
        }

        private fun add(w: Int, v: Int, c: Long) {
            geFrom.getOrPut(v) { ArrayList() }.add(longArrayOf(w.toLong(), c))
        }

        fun endUpperBoundsOf(start: Int, dur: Long): IntHashSet {
            val result = IntHashSet()
            val queue = IntArrayDeque()
            // Seed: vars directly proven ≥ start + c with c ≥ dur are ≥ the task end.
            geFrom[start]?.forEach { wc ->
                if (wc[1] >= dur) {
                    val w = wc[0].toInt()
                    if (result.add(w)) queue.addLast(w)
                }
            }
            // Close through max edges: result = max(...) ≥ operand ≥ end ⇒ result ≥ end too.
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                maxResultsOf[v]?.forEach { m -> if (result.add(m)) queue.addLast(m) }
            }
            return result
        }
    }

    private fun makespanLinks(): MakespanLinks = MakespanLinks()

    internal companion object {
        /** Per-factor task cap: above it the `O(n log n)` row build is skipped (sound loosening). */
        internal const val MAX_TASKS: Int = 1024

        /** Minimum tasks a makespan variable must dominate before a row is worth emitting. */
        private const val MIN_COVER: Int = 2

        /** Most rows emitted per scheduling factor (the highest-coverage makespan variables). */
        internal const val MAX_MAKESPAN_VARS: Int = 4
    }
}
