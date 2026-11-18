package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntDomains
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.includingStop
import com.eignex.util.collections.Bits
import kotlin.time.TimeSource

internal class IndependentOpenComponent(
    val projection: IndependentSourceProjection,
    val request: OpenTheoryRequest,
)

internal class IndependentSourceProjection(
    val source: Problem,
    val selection: PreparedFactorProjections,
    val factors: IntArray,
    val bools: IntArray,
    val ints: IntArray,
    val reals: IntArray,
)

private class IndependentCoordinates(val bools: IntArray, val ints: IntArray, val reals: IntArray)

internal fun checkIndependentComponents(
    projections: PreparedFactorProjections,
    components: List<IndependentOpenComponent>,
    params: TheoryParams,
    objective: LinearObjective?,
    fallback: (TheoryParams, PreparedFactorProjections?) -> OpenTheoryExecution,
): OpenTheoryExecution {
    val started = TimeSource.Monotonic.markNow()
    require(components.all { it.projection.source === projections.problem })
    val shared = params.independentShare(components.size + 1)
    val stop = shared.cancellation.includingStop(shared.timeout)
    var stats = SolveStats.EMPTY
    val points = ArrayList<Pair<IndependentSourceProjection, OpenTheoryAssignment>>()
    for (component in components) {
        if (stop()) break
        val result = OpenTheoryPipeline.engineFor(component.request).solve(shared)
        stats = stats.mergedWith(result.stats)
        if (result is OpenTheoryResult.Unsat) {
            stats = stats.copy(run = stats.run.copy(wallMs = started.elapsedNow().inWholeMilliseconds))
            return if (objective == null) OpenTheoryExecution.Satisfy(OpenTheoryResult.Unsat(stats))
            else OpenTheoryExecution.Optimize(OpenTheoryOptimum.Infeasible(stats))
        }
        if (result is OpenTheoryResult.Sat) points += component.projection to result.assignment
    }
    val result = if (objective == null && points.size == components.size && !stop()) {
        val retained = BooleanArray(projections.problem.numFactors) { true }
        for (component in components) for (index in component.projection.factors) retained[index] = false
        val remainder = projections.componentSource(retained.indices.filter { retained[it] }.toIntArray(), stop)
        if (remainder == null) {
            OpenTheoryExecution.Satisfy(OpenTheoryResult.Unknown(
                if (stop()) shared.preparationStopReason(stop) else TerminationReason.BudgetExhausted,
                SolveStats.EMPTY))
        } else {
            val partial = fallback(shared, remainder.selection)
            val verdict = (partial as OpenTheoryExecution.Satisfy).result
            if (verdict is OpenTheoryResult.Sat) {
                points += remainder to verdict.assignment
                val joined = joinIndependentWitnesses(projections.problem, points, stop)
                OpenTheoryExecution.Satisfy(if (joined == null) {
                    OpenTheoryResult.Unknown(
                        if (stop()) shared.preparationStopReason(stop) else TerminationReason.Unsupported,
                        verdict.stats)
                } else OpenTheoryResult.Sat(joined, verdict.stats))
            } else partial
        }
    } else fallback(shared, null)
    fun merged(other: SolveStats): SolveStats = stats.mergedWith(other).let {
        it.copy(run = it.run.copy(wallMs = started.elapsedNow().inWholeMilliseconds))
    }
    return when (result) {
        is OpenTheoryExecution.Satisfy -> OpenTheoryExecution.Satisfy(when (val verdict = result.result) {
            is OpenTheoryResult.Sat -> verdict.copy(stats = merged(verdict.stats))
            is OpenTheoryResult.Unsat -> verdict.copy(stats = merged(verdict.stats))
            is OpenTheoryResult.Unknown -> verdict.copy(stats = merged(verdict.stats))
        })
        is OpenTheoryExecution.Optimize -> OpenTheoryExecution.Optimize(when (val verdict = result.result) {
            is OpenTheoryOptimum.Optimal -> verdict.copy(stats = merged(verdict.stats))
            is OpenTheoryOptimum.Infeasible -> verdict.copy(stats = merged(verdict.stats))
            is OpenTheoryOptimum.Unbounded -> verdict.copy(stats = merged(verdict.stats))
            is OpenTheoryOptimum.Bounded -> verdict.copy(stats = merged(verdict.stats))
        }, result.semantics).also { it.retainPublication(result.acceptedIncumbent) }
    }
}

internal fun PreparedFactorProjections.independentOpenComponents(
    stop: ExecutionControl,
    acceptsFactor: (Int) -> Boolean = { true },
): List<IndependentOpenComponent>? {
    val connected = independentFactorGroups(stop) ?: return null
    if (connected.size < 2) return emptyList()
    val groups = connected.filter { indices -> indices.all { !stop() && acceptsFactor(it) } }
    if (stop()) return null
    if (groups.isEmpty()) return emptyList()
    val coordinates = groups.map { componentCoordinates(it, stop) ?: return null }
    val mapping = problem.componentMapping(coordinates, stop) ?: return null
    val requests = ArrayList<IndependentOpenComponent>()
    for ((index, indices) in groups.withIndex()) {
        if (stop()) return null
        val component = componentSource(indices, coordinates[index], mapping, stop) ?: return null
        val model = component.selection.problem
        val plan = model.componentPlan(component.selection, cancellation = stop)
        if (plan.encodingDecline == EncoderDecline.RESOURCE || stop()) return null
        if (plan.hasTheoryComponent) {
            requests += IndependentOpenComponent(component,
                OpenTheoryRequest(model, componentPlan = plan, presolveCancellation = stop))
        }
    }
    return requests
}

private fun PreparedFactorProjections.independentFactorGroups(stop: ExecutionControl): List<IntArray>? {
    val source = problem
    if (stop()) return null
    stop.charge(source.numFactors.toLong() + source.numBoolVars + source.numIntVars + source.numRealVars)
    if (stop()) return null
    val parents = IntArray(source.numFactors) { it }
    val boolFactors = IntArray(source.numBoolVars) { -1 }
    val intFactors = IntArray(source.numIntVars) { -1 }
    val realFactors = IntArray(source.numRealVars) { -1 }
    fun root(index: Int): Int {
        var current = index
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }
    fun connect(owners: IntArray, variable: Int, factor: Int) {
        val previous = owners[variable]
        if (previous < 0) owners[variable] = factor else parents[root(factor)] = root(previous)
    }
    ScanMeter(stop).use { scan ->
        for ((index, factor) in source.factors.withIndex()) {
            if (!scan.visit(1)) return null
            for (variable in factor.boolVars) {
                if (!scan.visit(1)) return null
                connect(boolFactors, variable, index)
            }
            for (variable in factor.intVars) {
                if (!scan.visit(1)) return null
                connect(intFactors, variable, index)
            }
            for (variable in factor.variables.reals) {
                if (!scan.visit(1)) return null
                connect(realFactors, variable, index)
            }
            for (target in providers.encodingTargets) {
                val encoder = encoder(index, target)
                for (id in encoder.requiredBooleans.keys) {
                    if (!scan.visit(1)) return null
                    if (id.index !in boolFactors.indices) return emptyList()
                    connect(boolFactors, id.index, index)
                }
                for (id in encoder.requiredIntegers) {
                    if (!scan.visit(1)) return null
                    if (id.index !in intFactors.indices) return emptyList()
                    connect(intFactors, id.index, index)
                }
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (index in source.factors.indices) {
            if (!scan.visit(1)) return null
            groups.getOrPut(root(index)) { ArrayList() }.add(index)
        }
        return if (!scan.check()) null else groups.values.map { it.toIntArray() }
    }
}

private fun PreparedFactorProjections.componentCoordinates(
    indices: IntArray,
    stop: ExecutionControl,
): IndependentCoordinates? {
    val source = problem
    val bools = LinkedHashSet<Int>()
    val ints = LinkedHashSet<Int>()
    val reals = LinkedHashSet<Int>()
    ScanMeter(stop).use { scan ->
        for (index in indices) {
            val factor = source.factors[index]
            if (!scan.visit(1L + factor.boolVars.size + factor.intVars.size + factor.variables.reals.size)) return null
            factor.boolVars.forEach(bools::add)
            factor.intVars.forEach(ints::add)
            factor.variables.reals.forEach(reals::add)
            for (target in providers.encodingTargets) {
                val encoder = encoder(index, target)
                for (id in encoder.requiredBooleans.keys) {
                    if (!scan.visit(1)) return null
                    bools.add(id.index)
                }
                for (id in encoder.requiredIntegers) {
                    if (!scan.visit(1)) return null
                    ints.add(id.index)
                }
            }
        }
        if (!scan.check()) return null
        return IndependentCoordinates(bools.toIntArray(), ints.toIntArray(), reals.toIntArray())
    }
}

private fun Problem.componentMapping(groups: List<IndependentCoordinates>, stop: ExecutionControl): VarRemap? {
    ScanMeter(stop).use { scan ->
        if (!scan.visit(numBoolVars.toLong() + numIntVars + numRealVars)) return null
        val bools = IntArray(numBoolVars) { -1 }
        val ints = IntArray(numIntVars) { -1 }
        val reals = IntArray(numRealVars) { -1 }
        // Disjoint components can share one immutable lookup into their separate local id ranges.
        for (group in groups) {
            if (!scan.visit(group.bools.size.toLong() + group.ints.size + group.reals.size)) return null
            group.bools.forEachIndexed { local, id -> bools[id] = local }
            group.ints.forEachIndexed { local, id -> ints[id] = local }
            group.reals.forEachIndexed { local, id -> reals[id] = local }
        }
        return if (scan.check()) VarRemap(bools, ints, reals) else null
    }
}

private fun PreparedFactorProjections.componentSource(
    indices: IntArray,
    stop: ExecutionControl,
): IndependentSourceProjection? {
    val coordinates = componentCoordinates(indices, stop) ?: return null
    val mapping = problem.componentMapping(listOf(coordinates), stop) ?: return null
    return componentSource(indices, coordinates, mapping, stop)
}

private fun PreparedFactorProjections.componentSource(
    indices: IntArray,
    coordinates: IndependentCoordinates,
    mapping: VarRemap,
    stop: ExecutionControl,
): IndependentSourceProjection? {
    val source = problem
    val integerIds = coordinates.ints
    val realIds = coordinates.reals
    ScanMeter(stop).use { scan ->
        val lower = LongArray(integerIds.size)
        val upper = LongArray(integerIds.size)
        val openLower = Bits(integerIds.size)
        val openUpper = Bits(integerIds.size)
        for ((local, id) in integerIds.withIndex()) {
            if (!scan.visit(1)) return null
            if (source.intBounds.hasLower(id)) lower[local] = source.intBounds.lower(id) else openLower.set(local)
            if (source.intBounds.hasUpper(id)) upper[local] = source.intBounds.upper(id) else openUpper.set(local)
        }
        val domains = if (integerIds.isEmpty() || source.declaredIntDomains.statedOrNull(integerIds[0]) == null) null
        else Array(integerIds.size) { requireNotNull(source.declaredIntDomains.statedOrNull(integerIds[it])) }
        val factors = Array(indices.size) {
            source.factors[indices[it]].remap(mapping)
        }
        if (!scan.check()) return null
        val model = Problem(
            numBoolVars = coordinates.bools.size,
            numIntVars = integerIds.size,
            declaredIntDomains = SourceIntDomains(
                IntBounds.fromModelBounds(lower, upper, openLower, openUpper), domains),
            factors = factors,
            impliedFactorMask = source.impliedFactorMask?.let { mask ->
                BooleanArray(indices.size) { mask[indices[it]] }
            },
            hasSymmetryBreaking = source.hasSymmetryBreaking,
            numRealVars = realIds.size,
            realLower = DoubleArray(realIds.size) { source.realLower[realIds[it]] },
            realUpper = DoubleArray(realIds.size) { source.realUpper[realIds[it]] },
            settings = source.settings,
            semantics = source.semantics,
        )
        return IndependentSourceProjection(source, projected(model, indices, mapping), indices,
            coordinates.bools, integerIds, realIds)
    }
}

internal fun TheoryParams.independentShare(parts: Int): TheoryParams {
    require(parts > 0)
    fun Long.share(): Long = if (this == Long.MAX_VALUE) this else this / parts
    return copy(
        maxLeaves = maxLeaves.share(),
        openWorkLimit = openWorkLimit.share(),
        maxDecisions = maxDecisions.share(),
        maxFlips = maxFlips.share(),
        maxInstructions = maxInstructions?.share(),
        openHintFlips = openHintFlips?.share(),
        sourceLpWorkLimit = sourceLpWorkLimit.share(),
        sourceLpAllocationLimit = sourceLpAllocationLimit.share(),
        sourceLpOperations = if (sourceLpOperations == Int.MAX_VALUE) Int.MAX_VALUE else sourceLpOperations / parts,
    )
}
