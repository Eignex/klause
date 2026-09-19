package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
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
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.util.Cancellation

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
        val premises = MutableList(rows.size) { intArrayOf() }
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
                val literals = variables.map { Lit.make(it, positive = booleanValue(it) != true) }.toIntArray()
                repeat(rows.size - start) { premises.add(literals) }
                val validity = variables.map { Lit.make(it, positive = booleanValue(it) == true) }
                for (rowIndex in start until rows.size) {
                    exactRows.add(ExactLpSourceRow(rows[rowIndex], validity))
                }
            }
        }
        val columns = model.numRealVars + model.numIntVars
        return QfLraRelaxation(
            ExactRationalFeasibilityModel(2 * columns, rows.map { it.overFreeColumns(columns) }),
            premises,
            model.exactLpSourceColumns(),
            exactRows,
        )
    }
}

internal class QfLraRelaxation(
    val model: ExactRationalFeasibilityModel,
    private val rowPremises: List<IntArray>,
    sourceColumns: List<ExactLpSourceColumn>,
    sourceRows: List<ExactLpSourceRow>,
) {
    internal val sourceColumns = sourceColumns.toList()
    internal val sourceRows = sourceRows.toList()

    fun explanation(conflict: BigRationalConflict?): SearchExplanation? {
        if (conflict == null) return null
        // Split columns have only their intrinsic nonnegative lower bound; every source bound is a row.
        if (conflict.bounds.any { it.column < model.n && it.upper }) return null
        return SearchExplanation(
            conflict.rows.flatMap { rowPremises[it].asIterable() }.distinct().sorted().toIntArray(),
        )
    }
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

internal class LiveQfLraSystem(private val source: Problem, private val lp: LpPropagator) {
    private val columns = source.numRealVars + source.numIntVars
    private val sourceColumns = source.exactLpSourceColumns()
    private val declaredFixed = sourceColumns.mapIndexedNotNull { index, column ->
        val lower = column.lower ?: return@mapIndexedNotNull null
        val upper = column.upper ?: return@mapIndexedNotNull null
        if (lower.value != upper.value) return@mapIndexedNotNull null
        index to FixedSmtColumn(lower.value + column.origin.value, SearchAtomPremise.All(emptyList()))
    }.toMap()
    private val canonicalTerms = HashMap<Map<Int, BigFraction>, Map<Int, BigFraction>>()
    private val normalized = source.factors.flatMap { it.linearRows }.map { row ->
        row.exactComparison(source.numRealVars, true) { false }.terms
    }.associateWith { intern(it, declaredFixed) }.toMutableMap()
    private val terms = normalized.values.map { it.coefficients }.filter { it.size != 1 }.distinct()
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

    fun refreshEpoch(token: Cancellation, validatePublication: () -> Boolean): Boolean {
        val retainedDefinitions = definitions.toMap()
        return lp.refreshSourceEpoch(token) {
            definitions == retainedDefinitions && validatePublication()
        }
    }

    fun assertRow(row: ExactRationalInequality, premise: SearchAtomPremise): Boolean = assertTerms(
        row.columns.indices.associate { row.columns[it] to row.coefficients[it] },
        true,
        row.rhs,
        row.strict,
        premise,
    )

    fun assertAtom(atom: SourceBoundAtom, premise: SearchAtomPremise): Boolean {
        val expression = sourceTerms(atom) ?: return false
        return assertTerms(expression, atom.upper, atom.threshold, atom.strict, premise)
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
        expression: Map<Int, BigFraction>,
        upper: Boolean,
        threshold: BigFraction,
        strict: Boolean,
        premise: SearchAtomPremise,
    ): Boolean {
        val fixed = rootFixedColumns(expression)
        val term = if (fixed.isEmpty()) {
            normalized.getOrPut(expression.toMap()) { intern(expression, declaredFixed) }
        } else {
            intern(expression, declaredFixed + fixed)
        }
        val coefficients = term.coefficients
        val column = coefficients.keys.singleOrNull() ?: definitions[coefficients] ?: run {
            val state = lp.state ?: return false
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
                return false
            }
            definitions[coefficients] = next
            next
        }
        return lp.assertBound(
            column,
            if (term.scale.signum() < 0) !upper else upper,
            ExactLpSide(ExactLpNumber.of(term.bound(threshold)), strict),
            term.premise(premise),
        )
    }

    private fun intern(expression: Map<Int, BigFraction>, fixed: Map<Int, FixedSmtColumn>): NormalizedSmtTerm {
        val term = normalizeSmtTerm(expression, fixed)
        return term.copy(coefficients = canonicalTerms.getOrPut(term.coefficients) { term.coefficients })
    }

    private fun rootFixedColumns(expression: Map<Int, BigFraction>): Map<Int, FixedSmtColumn> {
        val state = lp.state ?: return emptyMap()
        if (state.depth != 0) return emptyMap()
        val fixed = expression.keys.filter { column ->
            if (column in declaredFixed) return@filter false
            val lower = state.activeSide(column, false)?.side ?: return@filter false
            val upper = state.activeSide(column, true)?.side ?: return@filter false
            !lower.strict && !upper.strict && lower.number.value == upper.number.value &&
                lower.premises == null && upper.premises == null
        }
        if (fixed.isEmpty()) return emptyMap()
        // Capture both immutable witnesses before asserting a bound that can depend on them.
        val premises = lp.epochBasePremises() ?: return emptyMap()
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
