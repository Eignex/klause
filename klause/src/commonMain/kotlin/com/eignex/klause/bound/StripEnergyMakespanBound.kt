package com.eignex.klause.bound

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.MakespanLinks
import com.eignex.klause.lp.relaxation.SchedulingView
import com.eignex.klause.lp.relaxation.energeticRhs
import com.eignex.klause.lp.relaxation.prepareMakespanLinks
import com.eignex.klause.lp.relaxation.prepareSchedulingViews
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

/**
 * Strip-energy floor on a minimized objective variable `o`: the tasks proven to end by `o` need room
 * for their energy before it. Energetic reasoning as in Baptiste, Le Pape and Nuijten,
 * *Constraint-Based Scheduling* (Springer, 2001), https://doi.org/10.1007/978-1-4615-1479-4.
 *
 * A task with start `s` and duration `d` ends by `o` when a chain of hard rows proves `o ≥ s + d`
 * ([MakespanLinks.offsetsTo]: two-variable precedence rows add their constants, max edges add zero).
 * Every such task with demand `r` on a resource of capacity `cap` runs inside `[t1, o)` except the part
 * it can left-shift before `t1`, so for every left edge `t1`
 *
 * ```
 *   cap·o  ≥  cap·t1 + Σ rᵢ·clamp(estᵢ + dᵢ − t1, 0, dᵢ)
 * ```
 *
 * ([energeticRhs] picks the strongest edge), and `o ≥ ⌈rhs / cap⌉` because `o` is integral.
 *
 * A resource is a [Cumulative] through its [SchedulingView] (constant durations, mandatory tasks,
 * minimum demand, declared capacity) or a constant-size [Diffn] projected onto either axis. Every slice
 * across the axis meets pairwise-disjoint rectangles stacked inside the perpendicular extent of the
 * counted rectangles, so that extent is a capacity and each perpendicular size a demand.
 *
 * The floor rests on the live earliest starts and, for a diffn strip, the live perpendicular bounds;
 * [StripEnergyFloor.reason] negates exactly those bound atoms. Starts the model leaves open below, and
 * perpendicular coordinates it leaves open on either side, are skipped: a search-box endpoint states no
 * schedule or packing limit.
 */
internal class StripEnergyMakespanBound(
    private val problem: Problem,
    /** The minimized objective variable the floor bounds. */
    val objectiveVar: Int,
    deferred: Boolean = false,
) {

    /** One resource's tasks proven to end by [objectiveVar]. */
    private class Strip(
        val starts: IntArray,
        val durations: LongArray,
        val demands: LongArray,
        /** Declared capacity, or `0` when the capacity is the live extent of [across]. */
        val capacity: Long,
        /** Perpendicular coordinates of a diffn strip; empty for a cumulative. */
        val across: IntArray,
        /** Perpendicular sizes, aligned with [across]. */
        val acrossSizes: LongArray,
    )

    private var strips: List<Strip> = emptyList()
    private var visits = 0

    init {
        if (!deferred) preparation().forEach { }
    }

    fun preparation(): Iterator<Unit> = sequence<Unit> {
        strips = buildStrips()
    }.iterator()

    val applicable: Boolean get() = strips.isNotEmpty()

    /**
     * The strongest floor on [objectiveVar] over the strips under [session]'s live bounds, or null
     * when no strip yields one (or its arithmetic overflows). The floor need not exceed the
     * objective's current lower bound.
     */
    fun floor(session: PropagationSession): StripEnergyFloor? {
        var best: Strip? = null
        var bestValue = Long.MIN_VALUE
        for (strip in strips) {
            val value = try {
                floorOf(strip, session)
            } catch (_: CheckedLongOverflowException) {
                null
            } ?: continue
            if (value > bestValue) {
                best = strip
                bestValue = value
            }
        }
        return best?.let { StripEnergyFloor(bestValue, reasonOf(it, session)) }
    }

    private fun floorOf(strip: Strip, session: PropagationSession): Long? {
        val est = LongArray(strip.starts.size) { session.intDomain(strip.starts[it]).min }
        val cap = if (strip.capacity > 0L) strip.capacity else liveExtent(strip, session)
        if (cap <= 0L) return null
        val rhs = energeticRhs(cap, est, strip.durations, strip.demands)
        // Truncation already rounds a negative quotient up.
        return if (rhs % cap > 0L) rhs / cap + 1L else rhs / cap
    }

    /** `max(acrossᵢ.max + sizeᵢ) − min(acrossᵢ.min)`: the room the strip's rectangles stack within. */
    private fun liveExtent(strip: Strip, session: PropagationSession): Long {
        var hi = Long.MIN_VALUE
        var lo = Long.MAX_VALUE
        for (k in strip.across.indices) {
            val d = session.intDomain(strip.across[k])
            val end = addExact(d.max, strip.acrossSizes[k])
            if (end > hi) hi = end
            if (d.min < lo) lo = d.min
        }
        return subExact(hi, lo)
    }

    /** Negated `start ≥ est` atoms, plus both perpendicular bounds of a diffn strip. */
    private fun reasonOf(strip: Strip, session: PropagationSession): IntArray {
        val seen = IntHashSet()
        val lits = IntArrayList()
        fun add(lit: Int) {
            if (seen.add(lit)) lits.add(lit)
        }
        for (v in strip.starts) add(session.boundGeLit(v, session.intDomain(v).min, positive = false))
        for (v in strip.across) {
            val d = session.intDomain(v)
            add(session.boundGeLit(v, d.min, positive = false))
            add(session.boundLeLit(v, d.max, positive = false))
        }
        return lits.toIntArray()
    }

    // Structural strip construction: once, at problem load.

    private suspend fun SequenceScope<Unit>.tick() {
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }

    private suspend fun SequenceScope<Unit>.buildStrips(): List<Strip> {
        var scheduling = false
        for (f in problem.factors) {
            tick()
            if (f is Cumulative || f is Diffn) {
                scheduling = true
                break
            }
        }
        if (!scheduling) return emptyList()
        val links = prepareMakespanLinks(problem)
        val offsets = with(links) { offsetsTo(objectiveVar) }
        val out = ArrayList<Strip>()
        for (view in prepareSchedulingViews(problem)) {
            cumulativeStrip(view, offsets)?.let(out::add)
        }
        for (f in problem.factors) {
            tick()
            if (f !is Diffn || f.widthVars != null || f.heightVars != null) continue
            diffnStrip(f.xs, f.widths, f.ys, f.heights, offsets)?.let(out::add)
            diffnStrip(f.ys, f.heights, f.xs, f.widths, offsets)?.let(out::add)
        }
        return out
    }

    /** Whether a task starting at [start] with duration [dur] provably ends by [objectiveVar]. */
    private fun endsByObjective(offsets: MutableIntLongMap, start: Int, dur: Long): Boolean =
        problem.intBounds.hasLower(start) && offsets.getOrDefault(start, Long.MIN_VALUE) >= dur

    private suspend fun SequenceScope<Unit>.cumulativeStrip(view: SchedulingView, offsets: MutableIntLongMap): Strip? {
        val starts = IntArrayList()
        val durations = LongArrayList()
        val demands = LongArrayList()
        for (i in view.starts.indices) {
            tick()
            val dur = view.durations[i]
            val dem = view.resources[i]
            if (dur <= 0L || dem <= 0L || !endsByObjective(offsets, view.starts[i], dur)) continue
            starts.add(view.starts[i])
            durations.add(dur)
            demands.add(dem)
        }
        if (starts.isEmpty()) return null
        return Strip(starts.toIntArray(), durations.toLongArray(), demands.toLongArray(), view.capacity,
            EmptyIntArray, EmptyLongArray)
    }

    private suspend fun SequenceScope<Unit>.diffnStrip(
        coords: IntArray,
        sizes: LongArray,
        across: IntArray,
        acrossSizes: LongArray,
        offsets: MutableIntLongMap,
    ): Strip? {
        val starts = IntArrayList()
        val durations = LongArrayList()
        val demands = LongArrayList()
        val perpendicular = IntArrayList()
        for (i in coords.indices) {
            tick()
            if (sizes[i] <= 0L || acrossSizes[i] <= 0L || !endsByObjective(offsets, coords[i], sizes[i])) continue
            if (!problem.intBounds.hasLower(across[i]) || !problem.intBounds.hasUpper(across[i])) continue
            starts.add(coords[i])
            durations.add(sizes[i])
            demands.add(acrossSizes[i])
            perpendicular.add(across[i])
        }
        if (starts.isEmpty()) return null
        val demandArray = demands.toLongArray()
        return Strip(starts.toIntArray(), durations.toLongArray(), demandArray, 0L,
            perpendicular.toIntArray(), demandArray)
    }
}

/**
 * A floor [value] on the objective variable and its [reason]: currently-false literals negating the
 * bound atoms it rests on, so `objective ≥ value ∨ reason` holds at every solution.
 */
internal class StripEnergyFloor(val value: Long, val reason: IntArray)
