package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBothBounds
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.constructionIntArray
import com.eignex.klause.util.constructionLongArray
import com.eignex.util.arrays.argsortBy
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact

/**
 * Sound LP makespan lower bound for the scheduling globals ([Cumulative], and [Diffn] projected onto
 * its axes), coupling task energy to the makespan variable through an energetic makespan inequality.
 * See Baptiste, Le Pape and Nuijten, *Constraint-Based Scheduling* (Springer, 2001),
 * https://doi.org/10.1007/978-1-4615-1479-4.
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
 * silently corrupts optima. So the makespan variable is never *guessed*: [MakespanLinks] verifies
 * `M ≥ startᵢ + durᵢ` from the actual `Linear` rows (`M − startᵢ ≥ durᵢ`, any orientation) and from
 * `ArrayMinMax(max)` whose operands are the task ends — a task contributes to `M`'s row only when the
 * link is proven. Energy uses each task's **minimum** resource and its (constant) duration, capacity
 * uses its declared **maximum**, and `t1 ≤ M` is enforced because `M ≥ ectᵢ` for every counted task.
 * All products are checked ([mulExact] / [addExact]); an overflow degrades the row to the trivial
 * root bound `cap·M ≥ cap·M.rootMin`, never to a wrapped value. The row is [LpModel.rowGlobal] when its
 * earliest-starts are still the declared ones (so it holds at every solution) and otherwise records
 * the live start lower bounds it leaned on as [LpRowPremises], mirroring the live-big-M `ReifiedLinear`
 * rows.
 *
 * Optional tasks (presence literals) and variable durations are skipped — the makespan link is then
 * conditional / not linearly expressible — which only drops their energy from the bound (sound).
 */
internal class CumulativeRelaxation(
    private val problem: Problem,
    private val includeCumulative: Boolean = true,
    // Projects each constant-size Diffn onto both axes as a cumulative: the x-axis takes starts xs,
    // durations widths, demands heights and the maximum y-extent as capacity, symmetrically for y. A
    // non-overlapping packing keeps the perpendicular demand within that extent, so the row lower-bounds a
    // strip-length variable; the maximum extent only loosens.
    private val includeDiffn: Boolean = false,
    deferred: Boolean = false,
) {

    // makespanVar is proven ≥ the end of every task in startVars. Plans are node-independent: only the
    // row's right-hand side moves with the live earliest starts.
    internal class AreaPlan(
        val makespanVar: Int,
        val capacity: Long,
        // capacity · M.rootMin, the fallback right-hand side, checked once at plan build.
        val floor: Long,
        val startVars: IntArray,
        val durations: LongArray,
        // Per-task minimum demand.
        val resources: LongArray,
    )

    internal class RowSpec(val rhs: Long, val global: Boolean, val premises: LpRowPremises?)

    var plans: List<AreaPlan> = emptyList()
        private set

    private var constructionVisits = 0

    init {
        if (!deferred) preparation().forEach { }
    }

    fun preparation(): Iterator<Unit> = sequence<Unit> {
        plans = buildPlans()
    }.iterator()

    val applicable: Boolean get() = plans.isNotEmpty()

    fun rowSpec(plan: AreaPlan, domains: RelaxationDomains): RowSpec {
        val cap = plan.capacity
        val n = plan.startVars.size
        val estLive = LongArray(n) { domains.intDomain(plan.startVars[it]).min }
        val estDecl = LongArray(n) { problem.rootDomainOf(plan.startVars[it]).min }
        val floor = plan.floor
        val energetic = try {
            energeticRhs(cap, estLive, plan.durations, plan.resources)
        } catch (_: CheckedLongOverflowException) {
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

    private suspend fun SequenceScope<Unit>.buildPlans(): List<AreaPlan> {
        val scheds = schedulingFactors()
        if (scheds.isEmpty()) return emptyList()
        val links = prepareMakespanLinks(problem)
        val out = ArrayList<AreaPlan>()
        for (s in scheds) {
            out.addAll(plansFor(s, links))
            yield(Unit)
        }
        return out
    }

    private suspend fun SequenceScope<Unit>.plansFor(s: Sched, links: MakespanLinks): List<AreaPlan> {
        // makespanVar → covered task local indices.
        val cover = MutableIntObjectMap<IntArrayList>()
        for (i in s.starts.indices) {
            if (++constructionVisits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            if (s.dur[i] <= 0 || s.res[i] <= 0) continue
            // The row's energy counts each task from its earliest start. Over a start the model leaves
            // open below that edge is the search box's invented endpoint, not the model's, so the row
            // would claim energy no solution outside the box has to spend.
            if (!problem.statesLowerBound(s.starts[i])) continue
            with(links) { endUpperBoundsOf(s.starts[i], s.dur[i]) }.forEach { m ->
                cover.getOrPut(m) { IntArrayList() }.add(i)
                if (++constructionVisits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
        }
        if (cover.isEmpty()) return emptyList()
        // Strongest coverage first, deterministic var-id tie-break.
        val ranked = ArrayList<Pair<Int, IntArrayList>>(MAX_MAKESPAN_VARS + 1)
        val order = compareByDescending<Pair<Int, IntArrayList>> { it.second.size }.thenBy { it.first }
        cover.forEach { m, idxs ->
            if (idxs.size >= MIN_COVER && problem.statesLowerBound(m)) {
                ranked.add(m to idxs)
                ranked.sortWith(order)
                if (ranked.size > MAX_MAKESPAN_VARS) ranked.removeAt(ranked.lastIndex)
            }
            if (++constructionVisits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        }
        return ranked.mapNotNull { (m, idxs) ->
            val k = idxs.size
            // `capacity · M.rootMin` is the row's fallback right-hand side, so a product that does not
            // fit has no sound stand-in: a wrapped value states a floor no solution has to reach. The
            // plan is dropped, which only drops energy.
            val floor = try {
                mulExact(s.cap, problem.rootDomainOf(m).min)
            } catch (_: CheckedLongOverflowException) {
                return@mapNotNull null
            }
            AreaPlan(
                makespanVar = m,
                capacity = s.cap,
                floor = floor,
                startVars = constructionIntArray(k) { s.starts[idxs[it]] },
                durations = constructionLongArray(k) { s.dur[idxs[it]] },
                resources = constructionLongArray(k) { s.res[idxs[it]] },
            )
        }
    }

    // Constant per-task durations, minimum demands, maximum capacity.
    private class Sched(val starts: IntArray, val dur: LongArray, val res: LongArray, val cap: Long)

    private suspend fun SequenceScope<Unit>.schedulingFactors(): List<Sched> {
        val out = ArrayList<Sched>()
        for (f in problem.factors) {
            if (++constructionVisits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            when (f) {
                is Cumulative -> {
                    if (!includeCumulative) continue
                    // Variable durations: M ≥ startᵢ + durᵢ is not a 2-var linear link. Optional tasks:
                    // the link is conditional on presence. Both are skipped (drops energy, sound).
                    if (f.durationVars.isNotEmpty() || f.presents.isNotEmpty()) continue
                    if (f.starts.size > MAX_TASKS) continue
                    // Capacity ceiling and demand floor must be the model's own, not search-box endpoints.
                    val cap = declaredCapacityOf(problem, f) ?: continue
                    val res = minimumResourcesOf(problem, f) ?: continue
                    out.add(Sched(f.starts, f.durations, res, cap))
                }

                is Diffn -> if (includeDiffn) addDiffnScheds(f, out)

                else -> Unit
            }
        }
        return out
    }

    // A variable width is not a constant duration, so variable-size diffn is skipped.
    private fun addDiffnScheds(f: Diffn, out: ArrayList<Sched>) {
        if (f.widthVars != null || f.heightVars != null) return
        if (f.xs.size > MAX_TASKS) return
        val yExtent = perpendicularExtent(f.ys, f.heights)
        if (yExtent > 0L) out.add(Sched(f.xs, f.widths, f.heights.copyOf(), yExtent))
        val xExtent = perpendicularExtent(f.xs, f.widths)
        if (xExtent > 0L) out.add(Sched(f.ys, f.heights, f.widths.copyOf(), xExtent))
    }

    // max(coordᵢ.max + sizeᵢ) − min(coordᵢ.min), an upper bound on the resource room. Zero drops the axis:
    // when a coordinate is open, the box endpoint bounds the search rather than the packing, and on
    // overflow a wrapped extent could land below the true room and tighten the row unsoundly.
    private fun perpendicularExtent(coords: IntArray, sizes: LongArray): Long {
        if (coords.isEmpty()) return 0L
        var hi = Long.MIN_VALUE
        var lo = Long.MAX_VALUE
        for (i in coords.indices) {
            if (!problem.statesBothBounds(coords[i])) return 0L
            val d = problem.rootDomainOf(coords[i])
            val end = try {
                addExact(d.max, sizes[i])
            } catch (_: CheckedLongOverflowException) {
                return 0L
            }
            if (end > hi) hi = end
            if (d.min < lo) lo = d.min
        }
        return try {
            subExact(hi, lo)
        } catch (_: CheckedLongOverflowException) {
            0L
        }
    }

    internal companion object {
        internal const val MAX_TASKS: Int = 1024

        // Tasks a makespan variable must dominate before its row is worth emitting.
        private const val MIN_COVER: Int = 2

        // Rows per scheduling factor, for the highest-coverage makespan variables.
        internal const val MAX_MAKESPAN_VARS: Int = 4
    }
}

// max over t1 of cap·t1 + Σ rᵢ·clamp(ectᵢ − t1, 0, durᵢ). The maximand is piecewise-linear in t1 with
// + → − slope changes only at the earliest starts, so the maximum over t1 ≤ max ectᵢ is at some estᵢ or
// at max ectᵢ. The sweep keeps the energy partition incrementally in O(n log n); overflow throws.
internal fun energeticRhs(cap: Long, est: LongArray, dur: LongArray, res: LongArray): Long {
    val n = est.size
    val ect = LongArray(n) { addExact(est[it], dur[it]) }
    // A = {est ≥ t1}: full energy rᵢ·durᵢ. B = {est < t1 < ect}: rᵢ·(ectᵢ − t1). C = {ect ≤ t1}: 0.
    var fullEnergy = 0L // Σ_A rᵢ·durᵢ
    var bSumREct = 0L // Σ_B rᵢ·ectᵢ
    var bSumR = 0L // Σ_B rᵢ
    for (k in 0 until n) fullEnergy = addExact(fullEnergy, mulExact(res[k], dur[k]))

    val estOrder = argsortBy(n) { left, right -> est[left].compareTo(est[right]) }
    val ectOrder = argsortBy(n) { left, right -> ect[left].compareTo(ect[right]) }
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
