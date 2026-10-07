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
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactForm
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue

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
    private val preparedTerms = rowForms.flatten().associate { form ->
        val comparison = form.comparison(true) { false }
        comparison.ordered to SmtRowKeys(comparison.terms)
    }
    private val normalized = preparedTerms.values.associateTo(HashMap()) { keys ->
        keys.positive to SmtTermSlot(intern(keys.positive.expression(), declaredFixed))
    }
    private val terms = normalized.values.map { it.term.coefficients }.filter { it.size != 1 }.distinct()
    private val definitions = terms.withIndex().associate { (index, term) -> term to columns + index }.toMutableMap()

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
        return lp.install(
            source,
            ExactLpModel(
                List(columns) { column ->
                    terms.mapIndexedNotNull { index, term ->
                        term[column]?.takeUnless { it.isZero }?.let {
                            ExactLpEntry(index, ExactLpNumber.of(it.negated()))
                        }
                    }
                },
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
        val fixed = rootFixedColumns(key.columns)
        val slot = if (fixed.isEmpty()) {
            key.slot ?: normalized.getOrPut(key) { SmtTermSlot(intern(key.expression(), declaredFixed)) }
                .also { key.slot = it }
        } else {
            SmtTermSlot(intern(key.expression(), declaredFixed + fixed))
        }
        val term = slot.term
        val column = slot.column.takeIf { it >= 0 } ?: (definitionColumn(term.coefficients) ?: return false)
        slot.column = column
        return lp.assertBound(
            column,
            if (term.scale.signum() < 0) !upper else upper,
            ExactLpSide(ExactLpNumber.of(term.bound(threshold)), strict),
            term.premise(premise),
        )
    }

    private fun definitionColumn(coefficients: Map<Int, BigFraction>): Int? =
        coefficients.keys.singleOrNull() ?: definitions[coefficients] ?: run {
            val state = lp.state ?: return null
            val id = state.rows.lastId + 1L
            val next = state.model.numVars
            if (!lp.append(
                    LpScopedRow(
                        id,
                        coefficients.map { it.key to ExactLpNumber.of(it.value.negated()) },
                        ExactLpNumber.of(0L),
                        ExactLpColumn(ExactLpBounds(), integral = false),
                    ),
                    scoped = false,
                )
            ) {
                return null
            }
            definitions[coefficients] = next
            next
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
        val premises = lp.rootBoundPremises() ?: return emptyMap()
        return fixed.associateWith { column ->
            FixedSmtColumn(
                checkNotNull(state.activeSide(column, false)).side.number.value,
                SearchAtomPremise.All(
                    listOf(
                        premises[column to false] ?: SearchAtomPremise.Unavailable,
                        premises[column to true] ?: SearchAtomPremise.Unavailable,
                    ),
                ),
            )
        }
    }
}

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
