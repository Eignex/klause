package com.eignex.klause.meta.alns

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact
import kotlin.random.Random

internal class SemanticBlockRemoval(
    val kind: Kind,
    private val definitions: InvariantNetwork? = null,
    private val maxInputs: Int = 32,
    private val maxVariables: Int = 4096,
    private val maxVisits: Int = 65_536,
) : DestroyOperator {
    enum class Kind { Assignment, Resource, Placement }

    init {
        require(maxVariables in 1..4096 && maxInputs in 1..maxVariables && maxVisits > 0)
    }

    var lastDecline: RepairBlockDecline? = null
        private set
    var lastVisits = 0
        private set
    var lastSearchedInputs = 0
        private set
    var lastRequested: FreedVars? = null
        private set

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample,
        objective: LinearObjective, fraction: Double,
    ): FreedVars = destroy(rng, problem, incumbent, objective, fraction, Assumptions.None, Cancellation.Never)

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample,
        objective: LinearObjective, fraction: Double, assumptions: Assumptions, cancellation: Cancellation,
    ): FreedVars = observed(cancellation) { work ->
        if (incumbent.numBoolVars != problem.numBoolVars || incumbent.numIntVars != problem.numIntVars ||
            incumbent.numRealVars != problem.numRealVars || problem.numRealVars != 0 ||
            problem.numBoolVars.toLong() + problem.numIntVars > Int.MAX_VALUE ||
            !fraction.isFinite() || fraction !in 0.0..1.0
        ) work.stop(RepairBlockDecline.InvalidFrame)
        work.charge()
        if (problem.numFactors == 0) work.stop(RepairBlockDecline.NoShape)
        val nominal = (fraction * (problem.numBoolVars.toLong() + problem.numIntVars)).toInt().coerceAtLeast(1)
        val start = rng.nextInt(problem.numFactors)
        var unsupported = false
        for (offset in 0 until problem.numFactors) {
            work.charge()
            val factor = problem.factors[((start.toLong() + offset) % problem.numFactors).toInt()]
            val roles = Roles(work)
            when {
                kind == Kind.Assignment && factor is AllDifferent -> {
                    if (factor.presents.isNotEmpty() || factor.exceptSet.isNotEmpty()) {
                        unsupported = true
                        continue
                    }
                    for (variable in factor.vars) roles.int(variable)
                }
                kind == Kind.Resource && factor is Cumulative -> {
                    if (factor.starts.size < 2) continue
                    resourceWindow(factor, incumbent, nominal, rng, roles, work)
                }
                kind == Kind.Placement && factor is Diffn -> {
                    if (factor.n < 2) continue
                    placementWindow(factor, incumbent, rng, roles, work)
                }
                else -> continue
            }
            var closed: ClosedRepairBlock
            do {
                val before = roles.size
                closed = RepairBlockClosure.close(problem, roles.freed(), definitions, assumptions,
                    maxInputs, maxVariables, work)
                expandPackets(problem, closed.freed, roles, work)
            } while (roles.size != before)
            if (closed.searchedInputs == 0) work.stop(RepairBlockDecline.Unavailable)
            lastRequested = roles.freed()
            lastSearchedInputs = closed.searchedInputs
            return@observed closed.freed
        }
        work.stop(if (unsupported) RepairBlockDecline.UnsupportedShape else RepairBlockDecline.NoShape)
    }

    fun closeControl(problem: BakedProblem, requested: FreedVars, assumptions: Assumptions,
        cancellation: Cancellation,
    ): FreedVars = observed(cancellation) { work ->
        val closed = RepairBlockClosure.close(problem, requested, definitions, assumptions,
            maxInputs, maxVariables, work)
        if (closed.searchedInputs == 0) work.stop(RepairBlockDecline.Unavailable)
        lastSearchedInputs = closed.searchedInputs
        lastRequested = requested
        closed.freed
    }

    private inline fun observed(cancellation: Cancellation, action: (RepairBlockWork) -> FreedVars): FreedVars {
        lastDecline = null
        lastSearchedInputs = 0
        lastRequested = null
        val work = RepairBlockWork(cancellation, maxVisits)
        return try {
            action(work)
        } catch (_: RepairBlockInterrupted) {
            lastDecline = work.decline
            FreedVars(intArrayOf(), intArrayOf())
        } catch (_: CheckedLongOverflowException) {
            lastDecline = RepairBlockDecline.Arithmetic
            FreedVars(intArrayOf(), intArrayOf())
        } finally {
            lastVisits = work.visits
        }
    }

    private inner class Roles(private val work: RepairBlockWork) {
        private val bools = IntHashSet()
        private val ints = IntHashSet()
        val size: Int get() = bools.size + ints.size
        fun int(variable: Int) {
            work.charge()
            ints.add(variable)
            bound()
        }
        fun bool(variable: Int) {
            work.charge()
            bools.add(variable)
            bound()
        }
        private fun bound() {
            if (size > maxVariables) work.stop(RepairBlockDecline.Oversized)
        }
        fun freed() = FreedVars(bools.toIntArray(), ints.toIntArray())
    }

    private fun task(factor: Cumulative, index: Int, roles: Roles) {
        roles.int(factor.starts[index])
        if (factor.durationVars.isNotEmpty()) roles.int(factor.durationVars[index])
        if (factor.resourceVars.isNotEmpty()) roles.int(factor.resourceVars[index])
        if (factor.presents.isNotEmpty()) roles.bool(Lit.variable(factor.presents[index]))
        if (factor.capacityVar >= 0) roles.int(factor.capacityVar)
    }

    private fun rectangle(factor: Diffn, index: Int, roles: Roles) {
        roles.int(factor.xs[index])
        roles.int(factor.ys[index])
        factor.widthVars?.let { roles.int(it[index]) }
        factor.heightVars?.let { roles.int(it[index]) }
    }

    private fun resourceWindow(factor: Cumulative, center: Sample, nominal: Int, rng: Random,
        roles: Roles, work: RepairBlockWork,
    ) {
        val count = factor.starts.size
        if (count > maxVariables) work.stop(RepairBlockDecline.Oversized)
        val starts = LongArray(count) { work.charge(); center.intValue(factor.starts[it]) }
        val order = (0 until count).sortedWith { left, right ->
            work.charge()
            starts[left].compareTo(starts[right]).takeIf { it != 0 } ?: left.compareTo(right)
        }
        val take = nominal.coerceIn(2, count)
        val from = rng.nextInt(count - take + 1)
        for (position in from until from + take) task(factor, order[position], roles)
    }

    private fun placementWindow(factor: Diffn, center: Sample, rng: Random, roles: Roles, work: RepairBlockWork) {
        val count = factor.n
        if (count > maxVariables) work.stop(RepairBlockDecline.Oversized)
        val left = LongArray(count)
        val right = LongArray(count)
        val bottom = LongArray(count)
        val top = LongArray(count)
        for (index in 0 until count) {
            work.charge()
            left[index] = center.intValue(factor.xs[index])
            bottom[index] = center.intValue(factor.ys[index])
            val width = factor.widthVars?.let { center.intValue(it[index]) } ?: factor.widths[index]
            val height = factor.heightVars?.let { center.intValue(it[index]) } ?: factor.heights[index]
            if (width <= 0 || height <= 0) work.stop(RepairBlockDecline.UnsupportedShape)
            right[index] = addExact(left[index], width)
            top[index] = addExact(bottom[index], height)
        }
        val depth = IntArray(count) { -1 }
        val queue = IntArrayList()
        val seed = rng.nextInt(count)
        depth[seed] = 0
        queue.add(seed)
        var head = 0
        while (head < queue.size) {
            val current = queue[head++]
            rectangle(factor, current, roles)
            if (depth[current] == 2) continue
            for (other in 0 until count) {
                work.charge()
                if (depth[other] >= 0) continue
                if (left[current] <= right[other] && left[other] <= right[current] &&
                    bottom[current] <= top[other] && bottom[other] <= top[current]
                ) {
                    depth[other] = depth[current] + 1
                    queue.add(other)
                }
            }
        }
        if (queue.size < 2) work.stop(RepairBlockDecline.Unavailable)
    }

    private fun expandPackets(problem: BakedProblem, closed: FreedVars, roles: Roles, work: RepairBlockWork) {
        if (kind == Kind.Assignment) return
        val inputs = IntHashSet().apply { for (variable in closed.ints) add(variable) }
        val boolInputs = IntHashSet().apply { for (variable in closed.bools) add(variable) }
        for (factor in problem.factors) {
            work.charge()
            if (kind == Kind.Resource && factor is Cumulative) {
                for (index in factor.starts.indices) {
                    work.charge()
                    if (factor.starts[index] in inputs ||
                        factor.durationVars.getOrNull(index)?.let { it in inputs } == true ||
                        factor.resourceVars.getOrNull(index)?.let { it in inputs } == true ||
                        factor.presents.getOrNull(index)?.let { Lit.variable(it) in boolInputs } == true
                    ) task(factor, index, roles)
                }
            }
            if (kind == Kind.Placement && factor is Diffn) {
                for (index in 0 until factor.n) {
                    work.charge()
                    if (factor.xs[index] in inputs || factor.ys[index] in inputs ||
                        factor.widthVars?.get(index)?.let { it in inputs } == true ||
                        factor.heightVars?.get(index)?.let { it in inputs } == true
                    ) rectangle(factor, index, roles)
                }
            }
        }
    }
}
