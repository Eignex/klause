package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Lit
import java.math.BigInteger

internal class ComponentCostScope(
    val source: String,
    conditions: Map<Int, Boolean>,
    variables: List<Int>,
    clauses: List<List<Int>>,
    weights: List<Long>,
    val restrictionCutoff: BigInteger? = null,
) {
    val conditions = conditions.toMap()
    val variables = variables.toList()
    val clauses = clauses.map { it.toList() }
    val weights = weights.toList()

    init {
        require(variables.distinct().size == variables.size)
        require(variables.size == weights.size)
        require(clauses.all { clause -> clause.all { it >= 0 && Lit.variable(it) in weights.indices } })
    }

    fun cost(witness: List<Boolean>): BigInteger {
        require(witness.size == weights.size)
        return weights.indices.fold(BigInteger.ZERO) { sum, i ->
            if (witness[i]) sum + BigInteger.valueOf(weights[i]) else sum
        }
    }

    fun accepts(witness: List<Boolean>): Boolean = witness.size == weights.size && clauses.all { clause ->
        clause.any { Lit.evaluate(it, witness[Lit.variable(it)]) }
    }

    fun trivialFloor(): BigInteger = weights.fold(BigInteger.ZERO) { sum, w ->
        sum + BigInteger.valueOf(minOf(0L, w))
    }

    override fun equals(other: Any?): Boolean = other is ComponentCostScope && source == other.source &&
        conditions == other.conditions && variables == other.variables && clauses == other.clauses &&
        weights == other.weights && restrictionCutoff == other.restrictionCutoff

    override fun hashCode(): Int = listOf(source, conditions, variables, clauses, weights, restrictionCutoff).hashCode()
}

internal data class ComponentCostBounds private constructor(
    val scope: ComponentCostScope,
    val lower: BigInteger,
    val witness: List<Boolean>?,
    val sourceInfeasible: Boolean,
) {
    val upper: BigInteger? get() = witness?.let(scope::cost)
    val exact: BigInteger? get() = upper?.takeIf { it == lower && !sourceInfeasible }

    fun withWitness(values: List<Boolean>): ComponentCostBounds {
        require(!sourceInfeasible && scope.accepts(values))
        val cost = scope.cost(values)
        require(cost >= lower)
        return if (upper == null || cost < checkNotNull(upper)) copy(witness = values.toList()) else this
    }

    // A complete check of source AND cost < threshold proves a floor, never source infeasibility.
    fun refutedBelow(checkedScope: ComponentCostScope, threshold: BigInteger): ComponentCostBounds {
        require(checkedScope == scope)
        require(upper == null || threshold <= checkNotNull(upper))
        return copy(lower = maxOf(lower, threshold))
    }

    fun refutedSource(checkedScope: ComponentCostScope): ComponentCostBounds {
        require(checkedScope == scope && witness == null && scope.restrictionCutoff == null)
        return copy(sourceInfeasible = true)
    }

    companion object {
        fun opening(scope: ComponentCostScope): ComponentCostBounds =
            ComponentCostBounds(scope, scope.trivialFloor(), null, false)
    }
}

internal class ScopedComponentBounds(
    private val fixedAndIsolated: BigInteger,
    private val scopes: List<ComponentCostScope>,
) {
    init {
        require(scopes.flatMap { it.variables }.distinct().size == scopes.sumOf { it.variables.size })
        require(scopes.map { it.source to it.conditions }.distinct().size <= 1)
    }

    fun floor(children: List<ComponentCostBounds>): BigInteger {
        checkScopes(children)
        return children.fold(fixedAndIsolated) { total, child -> total + child.lower }
    }

    fun improvementBudget(child: Int, cutoff: BigInteger, children: List<ComponentCostBounds>): BigInteger {
        checkScopes(children)
        checkCutoff(cutoff)
        require(child in children.indices)
        return cutoff - fixedAndIsolated - children.indices.filter { it != child }
            .fold(BigInteger.ZERO) { sum, i -> sum + children[i].lower }
    }

    fun closes(cutoff: BigInteger, children: List<ComponentCostBounds>): Boolean {
        checkScopes(children)
        checkCutoff(cutoff)
        return children.any { it.sourceInfeasible } || floor(children) >= cutoff
    }

    fun exact(children: List<ComponentCostBounds>): BigInteger? {
        checkScopes(children)
        if (children.any { it.exact == null || it.scope.restrictionCutoff != null }) return null
        return children.fold(fixedAndIsolated) { total, child -> total + checkNotNull(child.exact) }
    }

    private fun checkScopes(children: List<ComponentCostBounds>) {
        require(children.map { it.scope } == scopes)
    }

    private fun checkCutoff(cutoff: BigInteger) {
        require(scopes.all { it.restrictionCutoff == null || cutoff <= it.restrictionCutoff })
    }
}
