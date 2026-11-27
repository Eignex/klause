package com.eignex.klause.theory.qfnra

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.FiniteFactorEnforcement
import com.eignex.klause.solver.search.FiniteFactorEnforcer
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal class PolynomialSearchComponent(
    private val service: PolynomialTheoryService,
    private val invocation: PolynomialTheoryInvocation,
    integerColumns: Map<Int, Int> = emptyMap(),
    private val capture: ((QfNraAssignment, SearchContext) -> Sample)? = null,
) : SearchComponent, PreparingSearchComponent, FiniteFactorEnforcer {
    private val integerColumns = integerColumns.toMap()
    val witnessKey = SearchModelKey(QfNraAssignment::class)
    private val bounds = PolynomialSearchBounds(service.boolCount, invocation, this.integerColumns)
    private var signature: PolynomialSignature? = null
    private var outcome: QfNraResult? = null

    private var attached = false
    private var finiteEnforcement: FiniteFactorEnforcer? = null

    fun useFiniteEnforcement(source: Problem, factors: Set<Int>) {
        check(!attached && finiteEnforcement == null)
        finiteEnforcement = FiniteFactorEnforcement(source, factors)
    }

    override fun enforcingFiniteFactors(problem: Problem): Set<Int> =
        finiteEnforcement?.enforcingFiniteFactors(problem).orEmpty()

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        invalidate()
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) = invalidate()

    override fun initialize(context: SearchContext): ComponentResult {
        attached = true
        return propagate(context)
    }

    override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
        attached = true
        if (context.cancelled() || !context.consumeCheck()) {
            yield(ComponentResult.Indeterminate)
            return@sequence
        }
        val tightening = bounds.tighten(context, authorized = true)
        if (tightening != ComponentResult.Consistent) {
            yield(tightening)
            return@sequence
        }
        val current = bounds.signature(context)
        val pending = invocation.prepareCheck(bounds.condition(current), ExecutionControl { context.cancelled() })
        var result: QfNraResult? = null
        while (result == null) {
            result = pending.next()
            if (result == null) yield(null)
        }
        retain(current, result)
        yield(when (result) {
            is QfNraResult.Satisfiable -> ComponentResult.Consistent
            is QfNraResult.Unsatisfiable -> ComponentResult.Conflict()
            is QfNraResult.Unknown -> ComponentResult.Indeterminate
        })
    }.iterator()

    override fun propagate(context: SearchContext): ComponentResult {
        val tightening = bounds.tighten(context)
        if (tightening != ComponentResult.Consistent) return tightening
        return when (evaluate(context)) {
            is QfNraResult.Satisfiable -> ComponentResult.Consistent
            is QfNraResult.Unsatisfiable -> ComponentResult.Conflict()
            is QfNraResult.Unknown -> ComponentResult.Indeterminate
            null -> ComponentResult.Indeterminate
        }
    }

    override fun check(context: SearchContext): ComponentCheck {
        if ((0 until service.boolCount).any { context.boolValue(it) == null } ||
            integerColumns.keys.any { variable ->
                val lower = context.intLowerBound(variable)
                lower == null || lower != context.intUpperBound(variable)
            }
        ) return ComponentCheck.Indeterminate
        return when (evaluate(context)) {
            is QfNraResult.Satisfiable -> ComponentCheck.Feasible
            is QfNraResult.Unsatisfiable -> ComponentCheck.Infeasible()
            is QfNraResult.Unknown, null -> ComponentCheck.Indeterminate
        }
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        val accepted = requireNotNull(outcome as? QfNraResult.Satisfiable)
        model.contribute(witnessKey, accepted.assignment)
        capture?.let { model.contribute(completedSearchSample, it(accepted.assignment, context)) }
    }

    private fun evaluate(context: SearchContext): QfNraResult? {
        if (context.cancelled()) return null
        val current = bounds.signature(context)
        if (signature == current) return outcome
        if (!context.consumeCheck()) return null
        val result = invocation.check(bounds.condition(current), ExecutionControl { context.cancelled() })
        retain(current, result)
        return result
    }

    private fun retain(current: PolynomialSignature, result: QfNraResult) {
        signature = current.takeUnless { result is QfNraResult.Unknown }
        outcome = result.takeUnless { it is QfNraResult.Unknown }
    }

    private fun invalidate() {
        signature = null
        outcome = null
        bounds.invalidate()
    }
}

internal class PolynomialSearchBounds(
    private val boolCount: Int,
    private val invocation: PolynomialTheoryInvocation,
    private val integerColumns: Map<Int, Int>,
    private val fixed: Map<Int, BigFraction> = emptyMap(),
    private val checkFeasibility: Boolean = false,
) {
    private val columns = integerColumns.values.toSet()
    private var boundSignature: PolynomialSignature? = null

    fun tighten(context: SearchContext, authorized: Boolean = false, workAllowance: Long? = null): ComponentResult {
        if (columns.isEmpty() && !checkFeasibility) return ComponentResult.Consistent
        if (context.cancelled()) return ComponentResult.Indeterminate
        val current = signature(context)
        if (current == boundSignature) return ComponentResult.Consistent
        if (!authorized && !context.consumeCheck()) return ComponentResult.Indeterminate
        val premises = ArrayList<SearchAtomPremise>()
        for ((id, truth) in current.bools) {
            premises += SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(id, truth)))
        }
        for ((source, column) in integerColumns) {
            if (column in current.lower) premises += context.intLowerBoundPremise(source)
            if (column in current.upper) premises += context.intUpperBoundPremise(source)
        }
        val premise = SearchAtomPremise.All(premises)
        val bounds = invocation.bounds(condition(current), columns,
            ExecutionControl { context.cancelled() }, workAllowance)
        if (bounds.stopped) return ComponentResult.Indeterminate
        if (bounds.infeasible) return ComponentResult.Conflict(context.explainAtoms(premise))
        for ((source, column) in integerColumns) {
            val lower = bounds.lower[column]?.ceilInteger()
            if (lower != null && lower.fitsLong()) {
                val value = lower.toLongExact()
                if (current.lower[column]?.let { value > it } != false) {
                    val result = context.publish(SearchDecision.IntAtLeast(source, value), premise)
                    if (result != ComponentResult.Consistent) return result
                }
            }
            val upper = bounds.upper[column]?.negated()?.ceilInteger()?.let { -it }
            if (upper != null && upper.fitsLong()) {
                val value = upper.toLongExact()
                if (current.upper[column]?.let { value < it } != false) {
                    val result = context.publish(SearchDecision.IntAtMost(source, value), premise)
                    if (result != ComponentResult.Consistent) return result
                }
            }
        }
        boundSignature = current
        return ComponentResult.Consistent
    }

    fun signature(context: SearchContext): PolynomialSignature = PolynomialSignature(
        (0 until boolCount).mapNotNull { id -> context.boolValue(id)?.let { id to it } }.toMap(),
        integerColumns.mapNotNull { (source, column) -> context.intLowerBound(source)?.let { column to it } }.toMap(),
        integerColumns.mapNotNull { (source, column) -> context.intUpperBound(source)?.let { column to it } }.toMap(),
    )

    fun condition(current: PolynomialSignature): PolynomialCondition {
        fun numeric(bounds: Map<Int, Long>): Map<Int, BigFraction> {
            val converted = bounds.mapValues { BigFraction.ofLong(it.value) }
            return if (fixed.isEmpty()) converted else fixed + converted
        }
        return PolynomialCondition(current.bools, numeric(current.lower), numeric(current.upper))
    }

    fun invalidate() {
        boundSignature = null
    }
}

internal data class PolynomialSignature(
    val bools: Map<Int, Boolean>,
    val lower: Map<Int, Long>,
    val upper: Map<Int, Long>,
)
