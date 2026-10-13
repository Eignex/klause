package com.eignex.klause.theory.qfnra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchModelKey
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.solver.search.completedSearchSample
import com.eignex.klause.util.Cancellation

internal class PolynomialSearchComponent(
    private val service: PolynomialTheoryService,
    private val invocation: PolynomialTheoryInvocation,
    integerColumns: Map<Int, Int> = emptyMap(),
    private val capture: ((QfNraAssignment, SearchContext) -> Sample)? = null,
) : TheoryComponent, PreparingSearchComponent {
    private val integerColumns = integerColumns.toMap()
    val witnessKey = SearchModelKey(QfNraAssignment::class)
    private var signature: PolynomialSignature? = null
    private var outcome: QfNraResult? = null

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        invalidate()
        return ComponentResult.Consistent
    }

    override fun retract(decisionLevel: Int) = invalidate()

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
        if (context.cancelled() || !context.consumeCheck()) {
            yield(ComponentResult.Indeterminate)
            return@sequence
        }
        val current = signatureOf(context)
        val pending = invocation.prepareCheck(conditionOf(current), Cancellation { context.cancelled() })
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

    override fun propagate(context: SearchContext): ComponentResult = when (evaluate(context)) {
        is QfNraResult.Satisfiable -> ComponentResult.Consistent
        is QfNraResult.Unsatisfiable -> ComponentResult.Conflict()
        is QfNraResult.Unknown -> ComponentResult.Indeterminate
        null -> ComponentResult.Indeterminate
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
        val current = signatureOf(context)
        if (signature == current) return outcome
        if (!context.consumeCheck()) return null
        val result = invocation.check(conditionOf(current), Cancellation { context.cancelled() })
        retain(current, result)
        return result
    }

    private fun signatureOf(context: SearchContext): PolynomialSignature = PolynomialSignature(
        (0 until service.boolCount).mapNotNull { id -> context.boolValue(id)?.let { id to it } }.toMap(),
        integerColumns.mapNotNull { (source, column) -> context.intLowerBound(source)?.let { column to it } }.toMap(),
        integerColumns.mapNotNull { (source, column) -> context.intUpperBound(source)?.let { column to it } }.toMap(),
    )

    private fun conditionOf(current: PolynomialSignature): PolynomialCondition = PolynomialCondition(
        current.bools, current.lower.mapValues { BigFraction.ofLong(it.value) },
        current.upper.mapValues { BigFraction.ofLong(it.value) },
    )

    private fun retain(current: PolynomialSignature, result: QfNraResult) {
        signature = current.takeUnless { result is QfNraResult.Unknown }
        outcome = result.takeUnless { it is QfNraResult.Unknown }
    }

    private fun invalidate() {
        signature = null
        outcome = null
    }
}

private data class PolynomialSignature(
    val bools: Map<Int, Boolean>,
    val lower: Map<Int, Long>,
    val upper: Map<Int, Long>,
)
