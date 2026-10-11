package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.AffineEqualityStats
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.magnitudeBitLength
import kotlin.time.TimeSource.Monotonic

internal class ExactLiraAffineEqualities(
    model: Problem,
    forms: List<List<ExactRowForm>>,
    private val observe: ((AffineEqualityStats) -> Unit)? = null,
    private val accept: (SearchDecision, SearchContext) -> ComponentResult,
) {
    private val rows = if (model.numRealVars != 0) emptyList() else {
        model.factors.flatMapIndexed { factorIndex, factor ->
            if (factor.linearForm is LinearForm.Disjunction) emptyList() else {
                factor.linearRows.mapIndexedNotNull { rowIndex, row ->
                    if ((0 until row.size).any { Term.isBool(row.ref(it)) }) return@mapIndexedNotNull null
                    Source(forms[factorIndex][rowIndex].comparison(true) { false }, row.activator)
                }
            }
        }
    }
    private val facts = rows.filter { source ->
        val comparison = source.comparison
        (comparison.op == LinearOp.EQ || comparison.op == LinearOp.NE) &&
            comparison.terms.size <= MAX_TERMS && comparison.bound.withinLimit() &&
            comparison.terms.values.all { it == BigFraction.ONE || it == BigFraction.ONE.negated() }
    }
    private val declared = (0 until model.numIntVars).mapNotNull { column ->
        val value = model.intBounds.lowerAsBigInteger(column) ?: return@mapNotNull null
        if (value != model.intBounds.upperAsBigInteger(column)) return@mapNotNull null
        BigFraction.of(value, BIG_ONE).takeIf { it.withinLimit() }?.let { column to it }
    }
    private val basis = HashMap<Int, Equation>()
    private var foundationReady = false
    private var queryCursor = 0
    private var remaining = 0
    private var interrupted = false
    private var progressStop: Cancellation = Cancellation.Never
    private var basisResets = 0L
    private var factsAdded = 0L
    private var queryReductions = 0L
    private var constantQueries = 0L
    private var implications = 0L
    private var conflicts = 0L
    var implied = false
        private set

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        basisResets = 0
        factsAdded = 0
        queryReductions = 0
        constantQueries = 0
        implications = 0
        conflicts = 0
        remaining = MAX_VISITS
        val mark = Monotonic.markNow()
        return try {
            propagateWithin(context, stop)
        } finally {
            observe?.invoke(
                AffineEqualityStats(
                    passes = 1,
                    basisResets = basisResets,
                    factsAdded = factsAdded,
                    queryReductions = queryReductions,
                    constantQueries = constantQueries,
                    implications = implications,
                    conflicts = conflicts,
                    termVisits = (MAX_VISITS - remaining).toLong(),
                    budgetStops = if (remaining == 0) 1 else 0,
                    activeNs = mark.elapsedNow().inWholeNanoseconds,
                ),
            )
        }
    }

    private fun propagateWithin(context: SearchContext, stop: Cancellation): ComponentResult {
        implied = false
        if (stop()) return ComponentResult.Indeterminate
        if (rows.isEmpty()) return ComponentResult.Consistent
        interrupted = false
        val stride = PollStride()
        val metered = stop.workMeter() != null
        progressStop = Cancellation { (metered || stride.due()) && stop() }
        for (source in facts) {
            if (progressStop()) return ComponentResult.Indeterminate
            if (source.processed && !source.active(context)) {
                basisResets++
                basis.clear()
                facts.forEach { it.processed = false }
                foundationReady = false
                break
            }
        }
        if (!foundationReady) {
            for ((column, value) in declared) {
                if (progressStop()) return ComponentResult.Indeterminate
                basis[column] = Equation(emptyMap(), value, emptySet())
            }
            foundationReady = true
        }
        for (source in facts) {
            if (progressStop()) return ComponentResult.Indeterminate
            if (remaining == 0) break
            if (source.processed || !source.active(context)) continue
            val reduced = reduce(source.comparison)
            if (reduced == null) {
                if (interrupted) return ComponentResult.Indeterminate
                continue
            }
            val literal = source.literal(context)
            if (reduced.terms.isEmpty()) {
                if (!reduced.bound.isZero) return conflict(context, reduced.guards, literal, stop)
                source.processed = true
                factsAdded++
                continue
            }
            val guards = reduced.guards.toMutableSet()
            if (literal != ALWAYS) guards += literal
            if (guards.size > MAX_GUARDS) continue
            val pivot = reduced.terms.keys.maxOrNull() ?: continue
            val inverse = reduced.terms.getValue(pivot).reciprocal()
            val terms = HashMap<Int, BigFraction>()
            var complete = true
            for ((column, value) in reduced.terms) {
                if (column == pivot) continue
                if (!step()) {
                    complete = false
                    break
                }
                val normalized = value * inverse
                if (!normalized.withinLimit()) {
                    complete = false
                    break
                }
                terms[column] = normalized
            }
            if (interrupted) return ComponentResult.Indeterminate
            if (!complete) continue
            val bound = reduced.bound * inverse
            if (!bound.withinLimit() || terms.values.any { !it.withinLimit() }) continue
            if (terms.isEmpty() && bound.den != BIG_ONE) {
                return conflict(context, reduced.guards, literal, stop)
            }
            basis[pivot] = Equation(terms, bound, guards.toSet())
            source.processed = true
            factsAdded++
        }
        if (interrupted) return ComponentResult.Indeterminate
        var scanned = 0
        while (scanned < rows.size && remaining > 0) {
            if (progressStop()) return ComponentResult.Indeterminate
            val source = rows[queryCursor]
            queryCursor++
            if (queryCursor == rows.size) queryCursor = 0
            scanned++
            if (source.processed) continue
            queryReductions++
            val reduced = reduce(source.comparison)
            if (reduced == null) {
                if (interrupted) return ComponentResult.Indeterminate
                continue
            }
            if (reduced.terms.isNotEmpty()) continue
            constantQueries++
            val truth = source.comparison.truth(reduced.bound)
            val assigned = if (source.activator == ALWAYS) true else context.boolValue(source.activator)
            if (assigned == truth) continue
            if (assigned != null) return conflict(context, reduced.guards, source.literal(context), stop)
            val decision = SearchDecision.Bool(Lit.make(source.activator, truth))
            val reason = context.explainAtoms(premise(reduced.guards), decision) ?: continue
            if (stop()) return ComponentResult.Indeterminate
            val result = context.imply(decision.literal, reason)
            if (result !is ComponentResult.Consistent) return result
            val accepted = accept(decision, context)
            if (accepted !is ComponentResult.Consistent) return accepted
            implied = true
            implications++
        }
        return if (interrupted || stop()) ComponentResult.Indeterminate else ComponentResult.Consistent
    }

    private fun reduce(comparison: ExactComparison): Reduced? {
        if (comparison.terms.size > MAX_TERMS || !comparison.bound.withinLimit() ||
            comparison.terms.values.any { !it.withinLimit() }
        ) return null
        val terms = comparison.terms.toMutableMap()
        var bound = comparison.bound
        val guards = HashSet<Int>()
        while (true) {
            var pivot: Int? = null
            for (column in terms.keys) {
                if (!step()) return null
                if (column in basis && (pivot == null || column > pivot)) pivot = column
            }
            val column = pivot ?: break
            val equation = basis.getValue(column)
            val coefficient = checkNotNull(terms.remove(column))
            bound -= coefficient * equation.bound
            if (!bound.withinLimit()) return null
            guards += equation.guards
            if (guards.size > MAX_GUARDS) return null
            for ((other, value) in equation.terms) {
                if (!step()) return null
                val next = (terms[other] ?: BigFraction.ZERO) - coefficient * value
                if (!next.withinLimit()) return null
                if (next.isZero) terms.remove(other) else terms[other] = next
                if (terms.size > MAX_TERMS) return null
            }
        }
        return Reduced(terms, bound, guards)
    }

    private fun step(): Boolean {
        if (progressStop()) {
            interrupted = true
            return false
        }
        if (remaining == 0) return false
        remaining--
        return true
    }

    private fun conflict(
        context: SearchContext,
        guards: Set<Int>,
        literal: Int,
        stop: Cancellation,
    ): ComponentResult {
        if (stop()) return ComponentResult.Indeterminate
        val leaves = if (literal == ALWAYS) guards else guards + literal
        val reason = context.explainAtoms(premise(leaves))
        return if (stop()) ComponentResult.Indeterminate else {
            conflicts++
            ComponentResult.Conflict(reason)
        }
    }

    private fun premise(guards: Set<Int>): SearchAtomPremise = SearchAtomPremise.All(
        guards.map { SearchAtomPremise.Asserted(SearchDecision.Bool(it)) },
    )

    private fun BigFraction.withinLimit(): Boolean =
        num.magnitudeBitLength() <= MAX_BITS && den.magnitudeBitLength() <= MAX_BITS

    private fun ExactComparison.truth(residual: BigFraction): Boolean = when (op) {
        LinearOp.EQ -> residual.isZero
        LinearOp.NE -> !residual.isZero
        LinearOp.LE -> if (strict) BigFraction.ZERO < residual else BigFraction.ZERO <= residual
        LinearOp.GE -> if (strict) BigFraction.ZERO > residual else BigFraction.ZERO >= residual
    }

    private class Source(val comparison: ExactComparison, val activator: Int) {
        var processed = false

        fun active(context: SearchContext): Boolean {
            val truth = if (activator == ALWAYS) true else context.boolValue(activator)
            return (comparison.op == LinearOp.EQ && truth == true) ||
                (comparison.op == LinearOp.NE && truth == false)
        }

        fun literal(context: SearchContext): Int =
            if (activator == ALWAYS) ALWAYS else Lit.make(activator, checkNotNull(context.boolValue(activator)))
    }

    private data class Equation(val terms: Map<Int, BigFraction>, val bound: BigFraction, val guards: Set<Int>)
    private data class Reduced(val terms: Map<Int, BigFraction>, val bound: BigFraction, val guards: Set<Int>)

    private companion object {
        const val ALWAYS = -1
        const val MAX_TERMS = 32
        const val MAX_BITS = 256
        const val MAX_GUARDS = 256
        const val MAX_VISITS = 8192
    }
}
