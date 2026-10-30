package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.bounding.LpPremisedBound
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.strongerThan
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal class QfLraSystem(private val model: Problem) {
    fun build(booleanValue: (Int) -> Boolean?): QfLraRelaxation {
        val rows = ArrayList<ExactRationalInequality>()
        val exactRows = ArrayList<ExactLpSourceRow>()
        for (integer in 0 until model.numIntVars) {
            val column = model.numRealVars + integer
            model.intBounds.lowerAsBigInteger(integer)?.let { rows += exactColumnLower(column, it.asFraction()) }
            model.intBounds.upperAsBigInteger(integer)?.let { rows += exactColumnUpper(column, it.asFraction()) }
        }
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        for (factor in model.factors) {
            // A private choice is not a premise expressible by a source Boolean clause.
            if (factor.linearForm is LinearForm.Disjunction) continue
            for (row in factor.linearRows) {
                val variables = row.booleanVariables()
                if (variables.any { booleanValue(it) == null }) continue
                val truth = row.activator == LinearRow.ALWAYS || booleanValue(row.activator) == true
                val comparison = row.exactComparison(model.numRealVars, truth) { booleanValue(it) == true }
                if (comparison.op == LinearOp.NE) continue
                val start = rows.size
                comparison.rowsInto(rows)
                val validity = variables.map { Lit.make(it, positive = booleanValue(it) == true) }
                for (rowIndex in start until rows.size) {
                    exactRows.add(ExactLpSourceRow(rows[rowIndex], validity))
                }
            }
        }
        return QfLraRelaxation(
            model.exactLpSourceColumns(),
            exactRows,
        )
    }
}

internal class QfLraRelaxation(sourceColumns: List<ExactLpSourceColumn>, sourceRows: List<ExactLpSourceRow>) {
    internal val sourceColumns = sourceColumns.toList()
    internal val sourceRows = sourceRows.toList()
}

internal data class ExactLpSourceRow(
    val inequality: ExactRationalInequality,
    val premiseLiterals: List<Int> = emptyList(),
)

internal data class ExactLpSourceNumber(val value: BigFraction, val ieeeBits: Long? = null)

internal data class ExactLpSourceColumn(
    val lower: ExactLpSourceNumber?,
    val upper: ExactLpSourceNumber?,
    val origin: ExactLpSourceNumber,
    val integral: Boolean,
    val tag: Int,
)

internal fun Problem.exactLpSourceColumns(): List<ExactLpSourceColumn> {
    val zero = ExactLpSourceNumber(BigFraction.ZERO)
    return List(numRealVars + numIntVars) { column ->
        val lower: ExactLpSourceNumber?
        val upper: ExactLpSourceNumber?
        val integral: Boolean
        if (column < numRealVars) {
            lower = realLower[column].takeIf(Double::isFinite)?.let {
                ExactLpSourceNumber(it.asFraction(), it.toRawBits())
            }
            upper = realUpper[column].takeIf(Double::isFinite)?.let {
                ExactLpSourceNumber(it.asFraction(), it.toRawBits())
            }
            integral = false
        } else {
            val integer = column - numRealVars
            lower = intBounds.lowerAsBigInteger(integer)?.let { ExactLpSourceNumber(it.asFraction()) }
            upper = intBounds.upperAsBigInteger(integer)?.let { ExactLpSourceNumber(it.asFraction()) }
            integral = true
        }
        val origin = lower?.takeIf { it.ieeeBits == null } ?: zero
        ExactLpSourceColumn(
            lower?.let { if (origin.value.isZero) it else ExactLpSourceNumber(it.value - origin.value) },
            upper?.let { if (origin.value.isZero) it else ExactLpSourceNumber(it.value - origin.value) },
            origin,
            integral,
            column,
        )
    }
}

internal fun LinearRow.booleanVariables(): Set<Int> = buildSet {
    if (activator != LinearRow.ALWAYS) add(activator)
    for (k in 0 until this@booleanVariables.size) {
        if (Term.isBool(ref(k))) add(Lit.variable(Term.lit(ref(k))))
    }
}

// Each normalized multi-column term gets one slack column, so every asserted atom is a bound on a single column;
// Dutertre and de Moura, "A Fast Linear-Arithmetic Solver for DPLL(T)", CAV 2006,
// https://doi.org/10.1007/11817963_11.
internal class LiveQfLraSystem(
    private val source: Problem,
    private val lp: LpPropagator,
    rowForms: List<List<ExactRowForm>> = source.factors.map { factor ->
        factor.linearRows.map { it.exactForm(source.numRealVars) }
    },
) {
    private val columns = source.numRealVars + source.numIntVars
    private val sourceColumns = source.exactLpSourceColumns()
    private val declaredFixed = sourceColumns.mapIndexedNotNull { index, column ->
        val lower = column.lower ?: return@mapIndexedNotNull null
        val upper = column.upper ?: return@mapIndexedNotNull null
        if (lower.value != upper.value) return@mapIndexedNotNull null
        index to FixedSmtColumn(lower.value + column.origin.value, SearchAtomPremise.All(emptyList()))
    }.toMap()
    private val canonicalTerms = HashMap<Map<Int, BigFraction>, Map<Int, BigFraction>>()
    private val initialTerms = HashSet<SmtTermKey>()
    private val preparedTerms = buildMap {
        for ((factorIndex, forms) in rowForms.withIndex()) {
            val factor = source.factors[factorIndex]
            val rows = factor.linearRows
            for ((rowIndex, form) in forms.withIndex()) {
                val comparison = form.comparison(true) { false }
                val keys = SmtRowKeys(comparison.terms)
                put(comparison.ordered, keys)
                val row = rows[rowIndex]
                if (factor.linearForm !is LinearForm.Disjunction && row.activator == LinearRow.ALWAYS &&
                    (0 until row.size).none { Term.isBool(row.ref(it)) }
                ) {
                    initialTerms += keys.positive
                }
            }
        }
    }
    private val normalized = preparedTerms.values.associateTo(HashMap()) { keys ->
        keys.positive to SmtTermSlot(intern(keys.positive.expression(), declaredFixed))
    }
    private val terms = normalized.filterKeys { it in initialTerms }.values
        .map { it.term.coefficients }.filter { it.size != 1 }.distinct()
    private val definitions = terms.withIndex().associate { (index, term) -> term to columns + index }.toMutableMap()
    var batchSourceBounds = true
    var observeAssertionCost = false
    val assertionCost = SmtSourceAssertionCost()

    fun install(): Boolean {
        val structural = sourceColumns.map { column ->
            ExactLpColumn(
                ExactLpBounds(
                    column.lower?.let { ExactLpSide(ExactLpNumber.of(it.value + column.origin.value)) },
                    column.upper?.let { ExactLpSide(ExactLpNumber.of(it.value + column.origin.value)) },
                ),
                integral = column.integral,
                tag = column.tag,
            )
        }
        val logical = List(terms.size) { ExactLpColumn(ExactLpBounds(), integral = false) }
        val entries = List(columns) { ArrayList<ExactLpEntry>() }
        terms.forEachIndexed { row, term ->
            for ((column, coefficient) in term) {
                if (column in entries.indices && !coefficient.isZero) {
                    entries[column] += ExactLpEntry(row, ExactLpNumber.of(coefficient.negated()))
                }
            }
        }
        return lp.install(
            source,
            ExactLpModel(
                entries,
                List(terms.size) { ExactLpNumber.of(0L) },
                structural + logical,
                List(terms.size) { ExactLpRow() },
                ExactLpObjective(List(columns + terms.size) { ExactLpNumber.of(0L) }),
            ),
        )
    }

    fun assertRow(row: ExactRationalInequality, premise: SearchAtomPremise): Boolean = assertTerms(
        SmtTermKey(row.columns, row.coefficients),
        true,
        row.rhs,
        row.strict,
        premise,
    )

    fun assertComparison(comparison: ExactComparison, direction: LinearOp?, premise: SearchAtomPremise): Boolean {
        // Only source layouts are retained; transient comparisons must not grow a search-wide cache.
        val keys = preparedTerms[comparison.ordered] ?: SmtRowKeys(comparison.terms)
        val rows = ArrayList<ExactRationalInequality>(2)
        comparison.rowsInto(rows, direction)
        for ((index, row) in rows.withIndex()) {
            val negated = comparison.op == LinearOp.GE ||
                (comparison.op == LinearOp.NE && direction == LinearOp.GE) ||
                (comparison.op == LinearOp.EQ && index == 1)
            val key = if (negated) keys.negative else keys.positive
            if (!assertTerms(key, true, row.rhs, row.strict, premise)) return false
        }
        return true
    }

    fun assertComparisons(
        comparisons: List<PremisedSmtComparison>,
        stop: Cancellation,
        committed: (Int) -> Unit,
    ): Boolean {
        if (lp.state == null) return false
        val batch = SmtSourceBoundBatch(lp, ::assertBounds, committed)
        for ((index, input) in comparisons.withIndex()) {
            if (stop()) return false
            if (lp.state?.conflict != null) return true
            require(input.comparison.op != LinearOp.NE)
            if (!batchSourceBounds || lp.state?.depth == 0) {
                if (!assertComparison(input.comparison, null, input.premise)) return false
                committed(index)
                continue
            }
            val comparison = input.comparison
            val keys = preparedTerms[comparison.ordered] ?: SmtRowKeys(comparison.terms)
            val rows = ArrayList<ExactRationalInequality>(2)
            comparison.rowsInto(rows)
            for ((side, row) in rows.withIndex()) {
                if (stop()) return false
                val negated = comparison.op == LinearOp.GE || (comparison.op == LinearOp.EQ && side == 1)
                val bound = prepareBound(if (negated) keys.negative else keys.positive,
                    true, row.rhs, row.strict, input.premise, { !stop() && batch.flush() })
                    ?: return batch.conflict
                val consistent = batch.add(bound)
                if (!consistent) {
                    if (side == rows.lastIndex) batch.complete(index)
                    return !stop() && (batch.flush() || batch.conflict)
                }
            }
            batch.complete(index)
        }
        return !stop() && (batch.flush() || batch.conflict)
    }

    fun affineImplication(comparison: ExactComparison, stop: Cancellation): PartialLinearImplication? {
        stop.charge(comparison.terms.size.toLong() + 1L)
        if (stop()) return null
        val key = preparedTerms[comparison.ordered]?.positive ?: return null
        val term = (key.slot ?: normalized[key])?.term ?: return null
        if (term.coefficients.size < 2) return null
        stop.charge(term.coefficients.size.toLong() + 1L)
        if (stop()) return null
        val column = definitions[term.coefficients] ?: return null
        val state = lp.state ?: return null
        val arithmetic = PartialImplicationArithmetic(stop)
        if (!arithmetic.admit(comparison.bound)) return null
        return partialLinearActivityImplication(comparison, { upper ->
            val columnUpper = if (term.scale.signum() > 0) upper else !upper
            val active = state.activeSide(column, columnUpper) ?: return@partialLinearActivityImplication null
            val value = arithmetic.affineValue(active.side.number.value, term.scale, term.offset)
                ?: return@partialLinearActivityImplication null
            val premise = lp.activeBoundPremise(column, columnUpper)
                ?: return@partialLinearActivityImplication null
            if (premise === SearchAtomPremise.Unavailable) return@partialLinearActivityImplication null
            ExplainedLinearBound(value, active.side.strict, term.premise(premise))
        }, stop)
    }

    fun disequalitySide(comparison: ExactComparison): DerivedDisequalitySide? {
        val key = preparedTerms[comparison.ordered]?.positive ?: SmtTermKey.of(comparison.terms)
        val fixed = rootFixedColumns(key.columns)
        val slot = if (fixed.isEmpty()) {
            key.slot ?: normalized[key] ?: return null
        } else {
            SmtTermSlot(intern(key.expression(), declaredFixed + fixed))
        }
        val term = slot.term
        val column = slot.column.takeIf { it >= 0 } ?: term.coefficients.keys.singleOrNull()
            ?: definitions[term.coefficients] ?: return null
        val threshold = term.bound(comparison.bound)
        val state = lp.state ?: return null
        for (sourceUpper in listOf(true, false)) {
            val upper = if (term.scale.signum() < 0) !sourceUpper else sourceUpper
            val active = state.activeSide(column, upper) ?: continue
            val bound = active.side.number.value
            if (if (upper) bound > threshold else bound < threshold) continue
            val premise = lp.activeBoundPremise(column, upper) ?: return null
            return DerivedDisequalitySide(if (sourceUpper) LinearOp.LE else LinearOp.GE, term.premise(premise))
        }
        return null
    }

    fun assertAtom(atom: SourceBoundAtom, premise: SearchAtomPremise): Boolean {
        val expression = sourceTerms(atom) ?: return false
        return assertTerms(SmtTermKey.of(expression), atom.upper, atom.threshold, atom.strict, premise)
    }

    fun sourceTerms(atom: SourceBoundAtom): Map<Int, BigFraction>? {
        val expression = LinkedHashMap<Int, BigFraction>()
        for (term in atom.terms) {
            val column = when (val key = term.source) {
                is SearchIntValue -> if (key.variable in 0 until source.numIntVars) {
                    source.numRealVars + key.variable
                } else {
                    return null
                }

                is SearchRealValue -> if (key.variable in 0 until source.numRealVars) key.variable else return null

                else -> return null
            }
            expression[column] = term.coefficient
        }
        return expression
    }

    private fun assertTerms(
        key: SmtTermKey,
        upper: Boolean,
        threshold: BigFraction,
        strict: Boolean,
        premise: SearchAtomPremise,
    ): Boolean {
        val bound = prepareBound(key, upper, threshold, strict, premise) ?: return false
        return measureAssertion({ assertionCost.boundNs += it }) {
            if (observeAssertionCost) {
                assertionCost.boundCalls++
                val previous = lp.state?.activeSide(bound.column, bound.upper)?.side
                if (previous == null || bound.side.strongerThan(previous, bound.upper)) assertionCost.boundSides++
            }
            lp.assertBound(bound.column, bound.upper, bound.side, bound.premise)
        }
    }

    private fun assertBounds(bounds: List<LpPremisedBound>): LpBoundBatchResult =
        measureAssertion({ assertionCost.boundNs += it }) {
            if (observeAssertionCost && bounds.isNotEmpty()) {
                assertionCost.boundCalls++
                assertionCost.boundSides += bounds.size
            }
            lp.assertBounds(bounds)
        }

    private fun prepareBound(
        key: SmtTermKey,
        upper: Boolean,
        threshold: BigFraction,
        strict: Boolean,
        premise: SearchAtomPremise,
        beforeDefinition: () -> Boolean = { true },
    ): LpPremisedBound? {
        val fixed = rootFixedColumns(key.columns)
        val slot = if (fixed.isEmpty()) {
            key.slot ?: normalized.getOrPut(key) { SmtTermSlot(intern(key.expression(), declaredFixed)) }
                .also { key.slot = it }
        } else {
            SmtTermSlot(intern(key.expression(), declaredFixed + fixed))
        }
        val term = slot.term
        val column = slot.column.takeIf { it >= 0 }
            ?: (definitionColumn(term.coefficients, beforeDefinition) ?: return null)
        slot.column = column
        return LpPremisedBound(
            column,
            if (term.scale.signum() < 0) !upper else upper,
            ExactLpSide(ExactLpNumber.of(term.bound(threshold)), strict),
            term.premise(premise),
        )
    }

    private fun definitionColumn(coefficients: Map<Int, BigFraction>, beforeAppend: () -> Boolean): Int? =
        coefficients.keys.singleOrNull() ?: definitions[coefficients] ?: run {
            if (!beforeAppend()) return null
            val state = lp.state ?: return null
            val id = state.rows.lastId + 1L
            val next = state.model.numVars
            val appended = measureAssertion({ assertionCost.definitionNs += it }) {
                if (observeAssertionCost) assertionCost.definitionAppends++
                lp.append(
                    LpScopedRow(
                        id,
                        coefficients.map { it.key to ExactLpNumber.of(it.value.negated()) },
                        ExactLpNumber.of(0L),
                        ExactLpColumn(ExactLpBounds(), integral = false),
                    ),
                    scoped = false,
                )
            }
            if (!appended) return null
            definitions[coefficients] = next
            next
        }

    private inline fun <T> measureAssertion(record: (Long) -> Unit, action: () -> T): T {
        if (!observeAssertionCost) return action()
        val started = TimeSource.Monotonic.markNow()
        return try {
            action()
        } finally {
            record(started.elapsedNow().inWholeNanoseconds)
        }
    }

    private fun intern(expression: Map<Int, BigFraction>, fixed: Map<Int, FixedSmtColumn>): NormalizedSmtTerm {
        val term = normalizeSmtTerm(expression, fixed)
        return term.copy(coefficients = canonicalTerms.getOrPut(term.coefficients) { term.coefficients })
    }

    private fun rootFixedColumns(columns: IntArray): Map<Int, FixedSmtColumn> {
        val state = lp.state ?: return emptyMap()
        if (state.depth != 0) return emptyMap()
        val fixed = columns.filter { column ->
            if (column in declaredFixed) return@filter false
            val lower = state.activeSide(column, false)?.side ?: return@filter false
            val upper = state.activeSide(column, true)?.side ?: return@filter false
            !lower.strict && !upper.strict && lower.number.value == upper.number.value &&
                lower.premises == null && upper.premises == null
        }
        if (fixed.isEmpty()) return emptyMap()
        // Capture both immutable witnesses before asserting a bound that can depend on them.
        return fixed.associateWith { column ->
            FixedSmtColumn(
                checkNotNull(state.activeSide(column, false)).side.number.value,
                SearchAtomPremise.All(
                    listOf(
                        lp.activeBoundPremise(column, false) ?: SearchAtomPremise.Unavailable,
                        lp.activeBoundPremise(column, true) ?: SearchAtomPremise.Unavailable,
                    ),
                ),
            )
        }
    }
}

internal data class PremisedSmtComparison(val comparison: ExactComparison, val premise: SearchAtomPremise)

internal class SmtSourceAssertionCost {
    var definitionAppends = 0L
    var definitionNs = 0L
    var boundCalls = 0L
    var boundSides = 0L
    var boundNs = 0L
}

private class SmtSourceBoundBatch(
    private val lp: LpPropagator,
    private val assertBounds: (List<LpPremisedBound>) -> LpBoundBatchResult,
    private val committed: (Int) -> Unit,
) {
    private val bounds = ArrayList<LpPremisedBound>()
    private val completions = ArrayList<Pair<Int, Int>>()
    private val sides = HashMap<Pair<Int, Boolean>, ExactLpSide>()
    var conflict = false
        private set

    fun add(bound: LpPremisedBound): Boolean {
        val key = bound.column to bound.upper
        val previous = sides[key] ?: lp.state?.activeSide(bound.column, bound.upper)?.side
        if (previous != null && !bound.side.strongerThan(previous, bound.upper)) return true
        bounds += bound
        sides[key] = bound.side
        return ExactLpBounds(
            sides[bound.column to false] ?: lp.state?.activeSide(bound.column, false)?.side,
            sides[bound.column to true] ?: lp.state?.activeSide(bound.column, true)?.side,
        ).consistent
    }

    fun complete(index: Int) {
        completions += index to bounds.size
    }

    fun flush(): Boolean {
        val result = assertBounds(bounds)
        if (result is LpBoundBatchResult.Declined) return false
        // Counts address strengthened sides, including both sides of an equality, rather than source rows.
        for ((index, end) in completions) if (end <= result.count) committed(index)
        bounds.clear()
        completions.clear()
        sides.clear()
        conflict = result is LpBoundBatchResult.Conflict
        return !conflict
    }
}

internal data class DerivedDisequalitySide(val direction: LinearOp, val premise: SearchAtomPremise)

// A term as a lookup key: columns ascending, with the hash taken once, since every theory check reasserts the same
// rows and a map-keyed lookup pays a hash probe per entry to compare.
private class SmtTermKey(val columns: IntArray, private val coefficients: List<BigFraction>) {
    private val hash = 31 * columns.contentHashCode() + coefficients.hashCode()
    var slot: SmtTermSlot? = null

    fun expression(): Map<Int, BigFraction> = columns.indices.associate { columns[it] to coefficients[it] }

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean = other is SmtTermKey && hash == other.hash &&
        columns.contentEquals(other.columns) && coefficients == other.coefficients

    companion object {
        fun of(expression: Map<Int, BigFraction>): SmtTermKey {
            val entries = expression.entries.sortedBy { it.key }
            return SmtTermKey(IntArray(entries.size) { entries[it].key }, entries.map { it.value })
        }
    }
}

private class SmtRowKeys(terms: Map<Int, BigFraction>) {
    val positive = SmtTermKey.of(terms)
    val negative = SmtTermKey.of(terms.mapValues { (_, coefficient) -> coefficient.negated() })
}

private class SmtTermSlot(val term: NormalizedSmtTerm) {
    var column: Int = -1
}
