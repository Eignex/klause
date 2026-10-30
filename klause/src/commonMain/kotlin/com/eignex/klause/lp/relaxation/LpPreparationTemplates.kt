package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.lp.LinearLpProjection
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.compactRelaxationSupported
import com.eignex.klause.lp.emitExactLpRelaxation
import com.eignex.klause.lp.emitLpRelaxation
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.toLongExact
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal fun LpRelaxation.authoritativeWithTemplates(
    cancellation: Cancellation,
    onReuse: (Int) -> Unit = {},
): ExactLpModel? {
    if (cancellation()) return null
    val templates = preparationTemplates ?: return model.authoritativeModel()
    var reused = 0
    fun prepared(index: Int): LpIntegerBoundTemplate? = if (index < model.n && colVarId[index] >= 0 &&
        !colIsBool[index] && colRealId[index] < 0
    ) templates.bound(colVarId[index]) else null
    if (templates.objectiveIndependent && model.exactState == null && model.doubleView == null) {
        val imported = try {
            model.authoritativeModel { index ->
                cancellation.charge(1L)
                if (cancellation()) throw LpTemplateImportCancelled()
                prepared(index)?.compatibleColumn(model, index, cancellation).also {
                    if (cancellation()) throw LpTemplateImportCancelled()
                    if (it != null) reused++
                }
            }
        } catch (_: LpTemplateImportCancelled) {
            return null
        }
        if (cancellation()) return null
        onReuse(reused)
        return imported
    }
    val original = model.authoritativeModel() ?: return null
    val columns = List(original.numVars) { index ->
        cancellation.charge(1L)
        if (cancellation()) return null
        val current = original.column(index)
        val prepared = prepared(index)?.compatibleColumn(current, cancellation)
        if (cancellation()) return null
        if (prepared != null) reused++
        prepared ?: current
    }
    val imported = if (reused == 0) original else original.withBoundColumns(columns)
    if (cancellation()) return null
    onReuse(reused)
    return imported
}

private class LpTemplateImportCancelled : RuntimeException()

internal class LpSourceRowTemplate(private val row: LinearRow) {
    private val compactExact = row is ExactLinear && !row.strict && row.activator == LinearRow.ALWAYS &&
        row.isIntegerOnly && row.compactRelaxationSupported()
    private val integers = if (compactExact ||
        (row !is ExactLinear && row.isLongUnconditional && row.isIntegerOnly)
    ) {
        IntArray(row.size) { Term.intVar(row.ref(it)) }
    } else null
    private val coefficients = integers?.let {
        LongArray(row.size) {
            if (compactExact) (row as ExactLinear).constants.exactCoeff(it).toLongExact() else row.coeff(it)
        }
    }
    private val bound = when {
        compactExact -> (row as ExactLinear).constants.exactBound.toLongExact()
        integers != null -> row.bound
        else -> 0L
    }
    val storageUnits: Long = 2L + (integers?.size ?: 0) * 2L

    fun emit(builder: RelaxationBuilder, projection: LinearLpProjection) {
        if (integers != null) builder.linearRow(row.relation, integers, requireNotNull(coefficients), bound)
        else if (row is ExactLinear) row.emitExactLpRelaxation(builder)
        else row.emitLpRelaxation(builder, projection)
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal data class LpIntegerBoundTemplate(
    val variable: Int,
    val lower: Long,
    val upper: Long,
    val statedLower: Boolean,
    val statedUpper: Boolean,
) {
    private val completedColumn = AtomicReference<ExactLpColumn?>(null)
    val column: ExactLpColumn get() = requireNotNull(column(false, Cancellation.Never))

    private fun column(open: Boolean, cancellation: Cancellation): ExactLpColumn? {
        val previous = completedColumn.load()
        if (previous != null && (previous.bounds.upper == null) == open) return previous
        cancellation.charge(COLUMN_WORK)
        if (cancellation()) return null
        val width = upper - lower
        val current = ExactLpColumn(
            ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)), if (open) null else ExactLpSide(
                if (width >= 0L) ExactLpNumber.of(width)
                else ExactLpNumber.of(BigFraction.ofLong(upper) - BigFraction.ofLong(lower)),
            )),
            origin = ExactLpNumber.of(lower), integral = true, tag = variable,
        )
        completedColumn.compareAndSet(previous, current)
        return current
    }

    fun compatibleColumn(current: ExactLpColumn, cancellation: Cancellation = Cancellation.Never): ExactLpColumn? {
        if (!current.integral || current.tag != variable || current.origin.legacyLong() != lower) return null
        val lo = current.bounds.lower ?: return null
        if (lo.strict || lo.premises != null || lo.number.legacyLong() != 0L) return null
        val hi = current.bounds.upper
        val width = upper - lower
        if (hi != null && (hi.strict || hi.premises != null || hi.number.ieeeBits != null ||
            (width >= 0L && hi.number.legacyLong() != width))
        ) return null
        return column(hi == null, cancellation)?.takeIf { it == current }
    }

    fun compatibleColumn(model: LpModel, index: Int, cancellation: Cancellation): ExactLpColumn? {
        val width = upper - lower
        if (model.doubleView != null || model.exactState != null || model.colContinuous[index] ||
            model.probeClampedLo[index] || model.tag[index] != variable || model.loShift[index] != lower || width < 0L
        ) return null
        val open = !model.hasFiniteUpper(index) || model.probeClampedHi[index]
        if (!open && model.upper[index] != width) return null
        return column(open, cancellation)
    }

    companion object {
        const val STORAGE_UNITS = 24L
        const val COLUMN_WORK = 23L
    }
}

internal class LpFactorTemplate(
    val factor: Factor,
    val rows: List<LpSourceRowTemplate>?,
) {
    private val integerVariables = factor.intVars
    private val booleanVariables = factor.boolVars
    val supportSize: Int get() = integerVariables.size + booleanVariables.size
    val storageUnits: Long = 4L + supportSize + (rows?.sumOf { it.storageUnits } ?: 0L)

    fun reference(index: Int): Int = if (index < integerVariables.size) {
        Term.ofIntVar(integerVariables[index])
    } else {
        Term.ofLit(booleanVariables[index - integerVariables.size] shl 1)
    }
}

internal class LpTemplateComponent(
    val factors: List<LpFactorTemplate>,
    val bounds: Map<Int, LpIntegerBoundTemplate>,
    val references: IntArray,
    val costs: List<Long>,
)

internal class LpPreparationTemplates private constructor(
    private val source: Problem,
    private val namespace: Any,
    private val rowScope: Any,
    private val semantics: Any,
    private val finite: Boolean,
    private val objectiveConstant: Long,
    val objectiveIndependent: Boolean,
    val factors: List<LpFactorTemplate>,
    val bounds: Map<Int, LpIntegerBoundTemplate>,
    val components: List<LpTemplateComponent>,
    val reboundSource: Boolean,
    val reusedFactors: Int,
    val reusedBounds: Int,
    val reusedComponents: Int,
    val storageUnits: Long,
    val reusedIndexes: Boolean = false,
) {
    fun bound(variable: Int): LpIntegerBoundTemplate? = bounds[variable]

    fun emit(problem: Problem, factorId: Int, builder: RelaxationBuilder, projection: LinearLpProjection): Boolean {
        require(source === problem) { "LP templates belong to a different prepared source" }
        val rows = factors[factorId].rows ?: return false
        rows.forEach { it.emit(builder, projection) }
        return true
    }

    companion object {
        private const val MAX_UNITS = 262_144L

        fun prepare(
            selection: PreparedFactorProjections,
            namespace: Any,
            rowScope: Any,
            objective: LinearObjective?,
            previous: LpPreparationTemplates?,
            token: Cancellation,
            onWork: (Long) -> Unit = {},
            reuseIndexes: Boolean = true,
            reuseMetadata: Boolean = false,
        ): LpPreparationTemplates? {
            val problem = selection.problem
            // Continuous and custom projections keep their own authority and preparation path.
            if (problem.numRealVars != 0 || !selection.builtInRelaxation) return null
            val old = previous?.takeIf {
                it.namespace === namespace && it.semantics == problem.semantics &&
                    it.finite == (problem is BakedProblem) && it.source.numBoolVars == problem.numBoolVars &&
                    it.source.numIntVars == problem.numIntVars && it.source.numRealVars == problem.numRealVars
            }
            var units = 0L
            var pendingColumnWork = 0L
            try {
                fun visit(count: Long = 1L): Boolean {
                    units += count
                    token.charge(count)
                    return units + pendingColumnWork <= MAX_UNITS && !token()
                }
                fun reserveBound(): Boolean {
                    pendingColumnWork += LpIntegerBoundTemplate.COLUMN_WORK
                    return visit()
                }
                fun unchanged(): Boolean {
                    if (old == null || old.objectiveIndependent || old.factors.size != problem.numFactors ||
                        old.objectiveConstant != (objective?.constant ?: 0L)
                    ) return false
                    for (index in old.factors.indices) {
                        if (!visit() || old.factors[index].factor !== problem.factors[index]) return false
                    }
                    for ((variable, bound) in old.bounds) {
                        if (!visit()) return false
                        val domain = problem.rootDomainOf(variable)
                        if (bound.lower != domain.min || bound.upper != domain.max ||
                            bound.statedLower != problem.statesLowerBound(variable) ||
                            bound.statedUpper != problem.statesUpperBound(variable)
                        ) return false
                    }
                    for (component in old.components) for (index in component.references.indices) {
                        if (!visit()) return false
                        val reference = component.references[index]
                        val cost = if (Term.isInt(reference)) {
                            objective?.intCoefficientValues?.getOrElse(Term.intVar(reference)) { 0L } ?: 0L
                        } else {
                            objective?.boolWeightValues?.getOrElse(Term.lit(reference) shr 1) { 0L } ?: 0L
                        }
                        if (cost != component.costs[index]) return false
                    }
                    return true
                }
                fun rebind(): LpPreparationTemplates? {
                    if (old == null || old.factors.size != problem.numFactors) return null
                    if (old.rowScope !== rowScope) for (index in old.factors.indices) {
                        if (!visit() || old.factors[index].factor !== problem.factors[index]) return null
                    }
                    var changedBounds: MutableMap<Int, LpIntegerBoundTemplate>? = null
                    var reusedBounds = old.bounds.size
                    val stableBounds = problem.declaredIntDomains.stableEndpoints &&
                        old.source.declaredIntDomains === problem.declaredIntDomains
                    if (!stableBounds) for ((variable, bound) in old.bounds) {
                        if (!visit()) return null
                        val domain = problem.rootDomainOf(variable)
                        val lower = problem.statesLowerBound(variable)
                        val upper = problem.statesUpperBound(variable)
                        if (bound.lower == domain.min && bound.upper == domain.max &&
                            bound.statedLower == lower && bound.statedUpper == upper
                        ) continue
                        if (changedBounds == null) {
                            if (!visit(old.bounds.size.toLong())) return null
                            changedBounds = LinkedHashMap(old.bounds)
                        }
                        if (!reserveBound()) return null
                        changedBounds[variable] = LpIntegerBoundTemplate(variable, domain.min, domain.max, lower, upper)
                        reusedBounds--
                    }
                    var changedComponents: MutableList<LpTemplateComponent>? = null
                    var reusedComponents = old.components.size
                    if (changedBounds != null) for ((componentId, component) in old.components.withIndex()) {
                        if (!visit()) return null
                        var componentBounds: MutableMap<Int, LpIntegerBoundTemplate>? = null
                        for ((variable, bound) in component.bounds) {
                            if (!visit()) return null
                            val current = changedBounds.getValue(variable)
                            if (current === bound) continue
                            if (componentBounds == null) {
                                if (!visit(component.bounds.size.toLong())) return null
                                componentBounds = LinkedHashMap(component.bounds)
                            }
                            componentBounds[variable] = current
                        }
                        if (componentBounds == null) continue
                        if (changedComponents == null) {
                            if (!visit(old.components.size.toLong())) return null
                            changedComponents = old.components.toMutableList()
                        }
                        if (!visit()) return null
                        changedComponents[componentId] = LpTemplateComponent(component.factors,
                            componentBounds, component.references, component.costs)
                        reusedComponents--
                    }
                    if (!visit()) return null
                    return LpPreparationTemplates(
                        problem, namespace, rowScope, problem.semantics, problem is BakedProblem,
                        0L, true, old.factors, changedBounds ?: old.bounds,
                        changedComponents ?: old.components, old.source !== problem, old.factors.size, reusedBounds,
                        reusedComponents, old.storageUnits, changedBounds == null && changedComponents == null)
                }
                if (reuseMetadata) {
                    val rebound = rebind()
                    if (rebound != null) return rebound
                }
                if (!reuseMetadata && reuseIndexes && unchanged()) {
                    if (!visit()) return null
                    return requireNotNull(old).let {
                        LpPreparationTemplates(
                            problem, namespace, rowScope, problem.semantics, problem is BakedProblem,
                            it.objectiveConstant, false, it.factors, it.bounds, it.components,
                            it.source !== problem, it.factors.size, it.bounds.size, it.components.size,
                            it.storageUnits, true)
                    }
                }
                if (units + pendingColumnWork > MAX_UNITS || token()) return null
                val oldFactors = HashMap<Factor, LpFactorTemplate>()
                for (entry in old?.factors.orEmpty()) {
                    if (!visit()) return null
                    oldFactors[entry.factor] = entry
                }
                var reusedFactors = 0
                val factors = ArrayList<LpFactorTemplate>()
                val bounds = LinkedHashMap<Int, LpIntegerBoundTemplate>()
                var reusedBounds = 0
                var storage = 0L
                for ((factorId, factor) in problem.factors.withIndex()) {
                    if (!visit()) return null
                    val retained = oldFactors[factor]?.takeIf { it.factor === factor }
                    val entry = if (retained != null) {
                        reusedFactors++
                        retained
                    } else {
                        if (!visit(factor.structuralKeyWeight.toLong())) return null
                        val declared = if (selection.capabilities(factorId).relaxation != RelaxationCapability.SOUND ||
                            factor is LinearIndicator ||
                            factor is ReifiedPseudoBoolean
                        ) null else if (factor is ExactLinear) listOf(factor)
                        else factor.impliedLinearRows.takeIf { it.isNotEmpty() }
                        val rows = declared?.map { row ->
                            if (!visit(1L + row.size)) return null
                            LpSourceRowTemplate(row)
                        }
                        LpFactorTemplate(factor, rows)
                    }
                    storage += entry.storageUnits
                    if (storage > MAX_UNITS) return null
                    factors.add(entry)
                    for (supportId in 0 until entry.supportSize) {
                        val reference = entry.reference(supportId)
                        if (!visit()) return null
                        if (!Term.isInt(reference)) continue
                        val variable = Term.intVar(reference)
                        if (variable in bounds) continue
                        storage += LpIntegerBoundTemplate.STORAGE_UNITS
                        if (storage > MAX_UNITS) return null
                        val domain = problem.rootDomainOf(variable)
                        val statedLower = problem.statesLowerBound(variable)
                        val statedUpper = problem.statesUpperBound(variable)
                        val retainedBound = old?.bounds?.get(variable)?.takeIf {
                            it.lower == domain.min && it.upper == domain.max &&
                                it.statedLower == statedLower && it.statedUpper == statedUpper
                        }
                        if (retainedBound != null) reusedBounds++
                        if (retainedBound == null && !reserveBound()) return null
                        bounds[variable] = retainedBound ?: LpIntegerBoundTemplate(variable, domain.min, domain.max,
                            statedLower, statedUpper)
                    }
                }
                val unchangedIncidence = old != null && old.factors.size == factors.size && factors.indices.all {
                    if (!visit()) return null
                    old.factors[it] === factors[it]
                }
                val groups = if (unchangedIncidence) {
                    requireNotNull(old).components.map { it.factors }
                } else {
                    val parents = IntArray(factors.size) { it }
                    val ranks = IntArray(factors.size)
                    fun root(index: Int): Int {
                        var current = index
                        while (parents[current] != current) {
                            parents[current] = parents[parents[current]]
                            current = parents[current]
                        }
                        return current
                    }
                    val owners = HashMap<Int, Int>()
                    for (index in factors.indices) for (supportId in 0 until factors[index].supportSize) {
                        val reference = factors[index].reference(supportId)
                        if (!visit()) return null
                        val owner = owners.put(reference, index) ?: continue
                        val first = root(index)
                        val second = root(owner)
                        if (first == second) continue
                        if (ranks[first] < ranks[second]) parents[first] = second else {
                            parents[second] = first
                            if (ranks[first] == ranks[second]) ranks[first]++
                        }
                    }
                    val collected = LinkedHashMap<Int, MutableList<LpFactorTemplate>>()
                    for (index in factors.indices) {
                        if (!visit()) return null
                        collected.getOrPut(root(index)) { ArrayList() }.add(factors[index])
                    }
                    collected.values.toList()
                }
                val previousComponents = old?.components.orEmpty().associateBy { it.factors.firstOrNull() }
                var reusedComponents = 0
                val components = groups.map { group ->
                    val componentBounds = LinkedHashMap<Int, LpIntegerBoundTemplate>()
                    val costs = ArrayList<Long>()
                    val seen = LinkedHashSet<Int>()
                    for (entry in group) for (supportId in 0 until entry.supportSize) {
                        val reference = entry.reference(supportId)
                        if (!visit()) return null
                        if (!seen.add(reference)) continue
                        if (Term.isInt(reference)) {
                            val variable = Term.intVar(reference)
                            componentBounds[variable] = bounds.getValue(variable)
                            if (!reuseMetadata) {
                                costs.add(objective?.intCoefficientValues?.getOrElse(variable) { 0L } ?: 0L)
                            }
                        } else if (!reuseMetadata) {
                            val variable = Term.lit(reference) shr 1
                            costs.add(objective?.boolWeightValues?.getOrElse(variable) { 0L } ?: 0L)
                        }
                    }
                    storage += group.size * 3L + seen.size * 4L
                    if (storage > MAX_UNITS) return null
                    val retained = previousComponents[group.firstOrNull()]?.takeIf {
                        (reuseMetadata || old?.objectiveConstant == (objective?.constant ?: 0L)) &&
                            it.factors == group && it.bounds == componentBounds && it.costs == costs
                    }
                    if (retained != null) reusedComponents++
                    retained ?: run {
                        if (!visit(seen.size.toLong())) return null
                        LpTemplateComponent(group.toList(), componentBounds, seen.toIntArray(), costs)
                    }
                }
                if (!visit()) return null
                return LpPreparationTemplates(problem, namespace, rowScope, problem.semantics, problem is BakedProblem,
                    if (reuseMetadata) 0L else objective?.constant ?: 0L, reuseMetadata,
                    factors, bounds, components, old != null && old.source !== problem,
                    reusedFactors, reusedBounds,
                    reusedComponents, storage)
            } finally {
                onWork(units)
            }
        }
    }
}
