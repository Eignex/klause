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

internal fun LpRelaxation.authoritativeWithTemplates(
    cancellation: Cancellation,
    onReuse: (Int) -> Unit = {},
): ExactLpModel? {
    val original = model.authoritativeModel() ?: return null
    val templates = preparationTemplates ?: return original
    var reused = 0
    val columns = List(original.numVars) { index ->
        cancellation.charge(1L)
        if (cancellation()) return null
        val current = original.column(index)
        val prepared = if (index < model.n && colVarId[index] >= 0 &&
            !colIsBool[index] && colRealId[index] < 0
        ) templates.bound(colVarId[index])?.compatibleColumn(current) else null
        if (prepared != null) reused++
        prepared ?: current
    }
    onReuse(reused)
    return if (reused == 0) original else original.withBoundColumns(columns)
}

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

internal data class LpIntegerBoundTemplate(
    val variable: Int,
    val lower: Long,
    val upper: Long,
    val statedLower: Boolean,
    val statedUpper: Boolean,
) {
    val column = ExactLpColumn(
        ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)),
            ExactLpSide(ExactLpNumber.of(BigFraction.ofLong(upper) - BigFraction.ofLong(lower)))),
        origin = ExactLpNumber.of(lower), integral = true, tag = variable,
    )
    private val openColumn = column.copy(bounds = column.bounds.copy(upper = null))

    fun compatibleColumn(current: ExactLpColumn): ExactLpColumn? = when (current) {
        column -> column
        openColumn -> openColumn
        else -> null
    }
}

internal class LpFactorTemplate(
    val factor: Factor,
    val support: List<Int>,
    val rows: List<LpSourceRowTemplate>?,
) {
    val storageUnits: Long = 3L + support.size + (rows?.sumOf { it.storageUnits } ?: 0L)
}

internal class LpTemplateComponent(
    val factors: List<LpFactorTemplate>,
    val bounds: Map<Int, LpIntegerBoundTemplate>,
    val costs: List<Long>,
) {
    private val rowIndex = factors.associateWith { it.rows }

    fun rows(factor: LpFactorTemplate): List<LpSourceRowTemplate>? = rowIndex[factor]
}

internal class LpPreparationTemplates private constructor(
    private val source: Problem,
    private val namespace: Any,
    private val semantics: Any,
    private val finite: Boolean,
    private val dimensions: List<Int>,
    private val objectiveConstant: Long,
    val factors: List<LpFactorTemplate>,
    val bounds: Map<Int, LpIntegerBoundTemplate>,
    val components: List<LpTemplateComponent>,
    val reboundSource: Boolean,
    val reusedFactors: Int,
    val reusedBounds: Int,
    val reusedComponents: Int,
    val storageUnits: Long,
) {
    private val factorComponents = buildMap {
        for (component in components) for (factor in component.factors) put(factor, component)
    }
    private val boundComponents = buildMap {
        for (component in components) for (variable in component.bounds.keys) put(variable, component)
    }

    fun bound(variable: Int): LpIntegerBoundTemplate? = boundComponents[variable]?.bounds?.get(variable)

    fun emit(problem: Problem, factorId: Int, builder: RelaxationBuilder, projection: LinearLpProjection): Boolean {
        require(source === problem) { "LP templates belong to a different prepared source" }
        val factor = factors[factorId]
        val rows = factorComponents.getValue(factor).rows(factor) ?: return false
        rows.forEach { it.emit(builder, projection) }
        return true
    }

    companion object {
        private const val MAX_UNITS = 262_144L

        fun prepare(
            selection: PreparedFactorProjections,
            namespace: Any,
            objective: LinearObjective?,
            previous: LpPreparationTemplates?,
            token: Cancellation,
            onWork: (Long) -> Unit = {},
        ): LpPreparationTemplates? {
            val problem = selection.problem
            // Continuous and custom projections keep their own authority and preparation path.
            if (problem.numRealVars != 0 || !selection.builtInRelaxation) return null
            val dimensions = listOf(problem.numBoolVars, problem.numIntVars, problem.numRealVars)
            val old = previous?.takeIf {
                it.namespace === namespace && it.semantics == problem.semantics &&
                    it.finite == (problem is BakedProblem) && it.dimensions == dimensions
            }
            var units = 0L
            try {
                fun visit(count: Long = 1L): Boolean {
                    units += count
                    token.charge(count)
                    return units <= MAX_UNITS && !token()
                }
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
                        val support = ArrayList<Int>()
                        for (variable in factor.intVars) {
                            if (!visit()) return null
                            support.add(Term.ofIntVar(variable))
                        }
                        for (variable in factor.boolVars) {
                            if (!visit()) return null
                            support.add(Term.ofLit(variable shl 1))
                        }
                        val declared = if (selection.capabilities(factorId).relaxation != RelaxationCapability.SOUND ||
                            factor is LinearIndicator ||
                            factor is ReifiedPseudoBoolean
                        ) null else if (factor is ExactLinear) listOf(factor)
                        else factor.impliedLinearRows.takeIf { it.isNotEmpty() }
                        val rows = declared?.map { row ->
                            if (!visit(1L + row.size)) return null
                            LpSourceRowTemplate(row)
                        }
                        LpFactorTemplate(factor, support.distinct(), rows)
                    }
                    storage += entry.storageUnits
                    if (storage > MAX_UNITS) return null
                    factors.add(entry)
                    for (reference in entry.support) {
                        if (!visit()) return null
                        if (!Term.isInt(reference)) continue
                        val variable = Term.intVar(reference)
                        if (variable in bounds) continue
                        val domain = problem.rootDomainOf(variable)
                        val statedLower = problem.statesLowerBound(variable)
                        val statedUpper = problem.statesUpperBound(variable)
                        val retainedBound = old?.bounds?.get(variable)?.takeIf {
                            it.lower == domain.min && it.upper == domain.max &&
                                it.statedLower == statedLower && it.statedUpper == statedUpper
                        }
                        if (retainedBound != null) reusedBounds++
                        if (retainedBound == null && !visit(24L)) return null
                        bounds[variable] = retainedBound ?: LpIntegerBoundTemplate(variable, domain.min, domain.max,
                            statedLower, statedUpper)
                        storage += 24L
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
                    for (index in factors.indices) for (reference in factors[index].support) {
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
                    val seen = HashSet<Int>()
                    for (entry in group) for (reference in entry.support) {
                        if (!visit()) return null
                        if (!seen.add(reference)) continue
                        if (Term.isInt(reference)) {
                            val variable = Term.intVar(reference)
                            componentBounds[variable] = bounds.getValue(variable)
                            costs.add(objective?.intCoefficientValues?.getOrElse(variable) { 0L } ?: 0L)
                        } else {
                            costs.add(objective?.boolWeightValues?.getOrElse(Term.lit(reference) shr 1) { 0L } ?: 0L)
                        }
                    }
                    storage += group.size * 3L + seen.size * 3L
                    if (storage > MAX_UNITS) return null
                    val retained = previousComponents[group.firstOrNull()]?.takeIf {
                        old?.objectiveConstant == (objective?.constant ?: 0L) && it.factors == group &&
                            it.bounds == componentBounds && it.costs == costs
                    }
                    if (retained != null) reusedComponents++
                    retained ?: LpTemplateComponent(group.toList(), componentBounds, costs)
                }
                if (!visit(1L + factors.size * 2L + bounds.size)) return null
                return LpPreparationTemplates(problem, namespace, problem.semantics, problem is BakedProblem,
                    dimensions,
                    objective?.constant ?: 0L, factors, bounds, components, old != null && old.source !== problem,
                    reusedFactors, reusedBounds,
                    reusedComponents, storage)
            } finally {
                onWork(units)
            }
        }
    }
}
