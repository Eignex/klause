package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.complemented
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactMixedBoundedRow
import com.eignex.klause.lp.ExactMixedEchelonHermite
import com.eignex.klause.lp.ExactMixedTriangularBounds
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactMixedEchelonHermite
import com.eignex.klause.lp.exactMixedTriangularBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.RationalSimplexObserver
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.simplex.exact.exactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.exactMixedUnitCubeSolution
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Exact feasibility for the supported open QF_LIRA and QF_LIA fragments.
 *
 * The Boolean skeleton is fixed first. At a Boolean leaf the rational simplex sees both integer and
 * real columns. A fractional integer witness is split at its exact [BigInteger] floor, so the child
 * boxes are disjoint and cover every integer value. This deliberately lives beside QF_LRA rather than
 * entering finite CP: the only branching here is theory-local integrality branching.
 */
class ExactLiraSolver(override val model: Problem) : Theory<ExactLiraAssignment> {
    private var smtStats: SmtStatsSink? = null
    init {
        require(model.supportsExactLira()) { "exact LIRA search requires a supported integer-containing linear model" }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLiraAssignment> =
        checkExactLinear(model, bools, context, smtStats)

    internal fun observeWith(stats: SmtStatsSink) {
        smtStats = stats
    }
}

internal fun checkExactLinear(
    model: Problem,
    bools: BooleanArray,
    context: TheoryContext,
    stats: SmtStatsSink? = null,
): TheoryCheck<ExactLiraAssignment> {
    val cancellation = Cancellation(context::cancelled)
    return when (
        val result = ColdExactIntegerSearch(
            model = model,
            bools = bools.toStates(),
            cancellation = cancellation,
            consumeLeaf = {
                context.consumeCheck()
            },
            lowerBound = context::intLowerBound,
            upperBound = context::intUpperBound,
            emitAssignment = true,
            observer = stats,
        ).run()
    ) {
        is ColdIntegerSearchResult.Found -> TheoryCheck.Sat(checkNotNull(result.assignment))

        ColdIntegerSearchResult.Infeasible -> TheoryCheck.Infeasible(
            sourceExplanation(model, bools.toStates(), cancellation, null),
        )

        ColdIntegerSearchResult.Cancelled, ColdIntegerSearchResult.Budget -> TheoryCheck.Cancelled
    }
}

private fun sourceExplanation(
    model: Problem,
    bools: IntArray,
    cancellation: Cancellation,
    observer: RationalSimplexObserver?,
): SearchExplanation? {
    if (cancellation()) return null
    // Refute the original active rows independently of private splits, reductions and shared bounds.
    val relaxation = ColdQfLraSystem(model).build { variable ->
        when (bools[variable]) {
            TRUE -> true
            FALSE -> false
            else -> null
        }
    }
    val outcome = bigRationalOutcome(relaxation.model, cancellation, observer = observer)
    return relaxation.explanation(outcome.conflict)
}

private class ColdExactIntegerSearch(
    private val model: Problem,
    private val bools: IntArray,
    private val cancellation: Cancellation,
    private val consumeLeaf: () -> Boolean,
    private val lowerBound: (Int) -> Long?,
    private val upperBound: (Int) -> Long?,
    private val emitAssignment: Boolean,
    private val observer: SmtStatsSink? = null,
) {
    private val reduction = ColdExactLiraReductionCache(model)

    fun run(): ColdIntegerSearchResult {
        val stack = ArrayDeque<ColdSearchNode>()
        stack.addLast(
            ColdSearchNode().withPublishedBounds(model.numIntVars, lowerBound, upperBound),
        )
        while (stack.isNotEmpty()) {
            if (!consumeLeaf()) return ColdIntegerSearchResult.Budget
            if (cancellation()) return ColdIntegerSearchResult.Cancelled
            val node = stack.removeLast()
            val comparison = node.nextComparison(model)
            if (comparison != null) {
                val rows = model.factors[comparison.factor].linearRows
                for (literal in rows.indices.reversed()) {
                    stack.addLast(node.withComparison(comparison, literal))
                }
                continue
            }
            val disequality = node.nextDisequality(model, bools)
            if (disequality != null) {
                stack.addLast(node.withDirection(disequality, LinearOp.GE))
                stack.addLast(node.withDirection(disequality, LinearOp.LE))
                continue
            }
            val reduced = reduction.reduce(bools, node, cancellation, observer)
            if (reduced == ColdExactLiraReduction.Infeasible) continue
            if (reduced == ColdExactLiraReduction.Interrupted) return ColdIntegerSearchResult.Cancelled
            reduced as ColdExactLiraReduction.Bounded
            when (val bounded = ColdExactReducedLiraSystem(reduced).solve(node, cancellation)) {
                is ColdExactReducedSearchResult.Split -> {
                    stack.addLast(
                        bounded.node.withReducedBranch(
                            ColdIntegerBranch(bounded.integer, lower = bounded.floor + BigInteger.ONE),
                        ),
                    )
                    stack.addLast(
                        bounded.node.withReducedBranch(ColdIntegerBranch(bounded.integer, upper = bounded.floor)),
                    )
                }

                ColdExactReducedSearchResult.Infeasible -> Unit

                ColdExactReducedSearchResult.Interrupted -> return ColdIntegerSearchResult.Cancelled

                is ColdExactReducedSearchResult.Found -> {
                    val source = bounded.sourceValues
                    if ((0 until model.numIntVars).any { integer ->
                            !source[model.numRealVars + integer].isInteger()
                        }
                    ) {
                        return ColdIntegerSearchResult.Cancelled
                    }
                    return ColdIntegerSearchResult.Found(
                        if (emitAssignment) {
                            val telemetry = observer?.let { stats ->
                                val strict = reduced.sourceRows.any(ExactRationalInequality::strict)
                                val wide = reduced.sourceRows.hasWideIntegerData() ||
                                    (0 until model.numIntVars).any { integer ->
                                        source[model.numRealVars + integer].num.abs() > WIDE_INTEGER_LIMIT
                                    }
                                stats.observeWitnessCandidate(strict, wide)
                                strict to wide
                            }
                            ExactLiraAssignment(
                                bools.toCompleteValues(),
                                Array(model.numIntVars) { integer -> source[model.numRealVars + integer].num },
                                List(model.numRealVars) { real -> source[real] },
                            ).also {
                                telemetry?.let { (strict, wide) -> observer.observeWitnessAccepted(strict, wide) }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return ColdIntegerSearchResult.Infeasible
    }
}

private sealed interface ColdIntegerSearchResult {
    data class Found(val assignment: ExactLiraAssignment?) : ColdIntegerSearchResult
    data object Infeasible : ColdIntegerSearchResult
    data object Cancelled : ColdIntegerSearchResult
    data object Budget : ColdIntegerSearchResult
}

private const val UNASSIGNED = -1
private const val FALSE = 0
private const val TRUE = 1

private fun BooleanArray.toStates(): IntArray = IntArray(size) { if (this[it]) TRUE else FALSE }

private fun IntArray.toCompleteValues(): BooleanArray = BooleanArray(size) { variable ->
    when (this[variable]) {
        TRUE -> true
        FALSE -> false
        else -> error("exact LIRA witness requested before Boolean assignment was complete")
    }
}

private fun LinearRow.truthUnder(bools: IntArray): Boolean? {
    for (k in 0 until size) {
        val reference = ref(k)
        if (Term.isBool(reference) && bools[Lit.variable(Term.lit(reference))] == UNASSIGNED) return null
    }
    if (activator == LinearRow.ALWAYS) return true
    return when (bools[activator]) {
        TRUE -> true
        FALSE -> false
        else -> null
    }
}

/** The bounded transformed system proves this Boolean/disjunction leaf impossible. */
private sealed interface ColdExactLiraReduction {
    data object Infeasible : ColdExactLiraReduction

    class Bounded(
        val system: ExactMixedEchelonHermite,
        val bounds: ExactMixedTriangularBounds,
        val unboundedRows: List<ExactRationalInequality>,
        val sourceRows: List<ExactRationalInequality>,
    ) : ColdExactLiraReduction

    data object Interrupted : ColdExactLiraReduction
}

/**
 * Cache the Boolean-leaf Double-Bounded Reduction artefact across integer branch-and-bound nodes.
 *
 * Reduced-coordinate branches are deliberately not part of this key: they search the fixed
 * double-bounded artefact, while source bounds and Boolean/disjunction choices select that artefact.
 */
private class ColdExactLiraReductionCache(private val model: Problem) {
    private val results = HashMap<ColdExactLiraReductionKey, ColdExactLiraReduction>()

    fun reduce(
        bools: IntArray,
        node: ColdSearchNode,
        cancellation: Cancellation,
        observer: SmtStatsSink? = null,
    ): ColdExactLiraReduction {
        val mark = observer?.beginReduction()
        val key = ColdExactLiraReductionKey(
            bools.toList(),
            node.branches.sortedBy { it.variable },
            node.comparisonChoices.entries.sortedBy { it.key }.map { it.key to it.value },
            node.disequalityDirections.entries.sortedBy { it.key }.map { it.key to it.value },
            node.sourceBranches,
        )
        results[key]?.let { cached ->
            mark?.let {
                observer.endReduction(
                    it,
                    cacheHit = true,
                    accepted = cached != ColdExactLiraReduction.Interrupted,
                )
            }
            return cached
        }
        val rows = sourceRows(bools, node) ?: return ColdExactLiraReduction.Interrupted.also {
            mark?.let { observer.endReduction(it, cacheHit = false, accepted = false) }
        }
        val result = when (
            val split = exactDoubleBoundedSplit(
                rows,
                model.numRealVars + model.numIntVars,
                cancellation,
            )
        ) {
            ExactDoubleBoundedSplit.Infeasible -> ColdExactLiraReduction.Infeasible

            ExactDoubleBoundedSplit.Unknown -> ColdExactLiraReduction.Interrupted

            is ExactDoubleBoundedSplit.Split -> {
                val bounded = split.bounded.map { row ->
                    ExactMixedBoundedRow(
                        row.inequality.columns.indices.associate { index ->
                            row.inequality.columns[index] to row.inequality.coefficients[index]
                        },
                        row.lower,
                        row.inequality.rhs,
                        row.inequality.strict,
                    )
                }
                val transformed = exactMixedEchelonHermite(
                    bounded,
                    realColumns = model.numRealVars,
                    integerColumns = model.numIntVars,
                    cancellation = cancellation,
                )
                if (transformed == null) {
                    ColdExactLiraReduction.Interrupted
                } else {
                    val bounds = exactMixedTriangularBounds(transformed)
                    if (bounds.inconsistent) {
                        ColdExactLiraReduction.Infeasible
                    } else {
                        ColdExactLiraReduction.Bounded(
                            transformed,
                            bounds,
                            split.unbounded.map(rows::get),
                            rows,
                        )
                    }
                }
            }
        }
        if (!cancellation()) results[key] = result
        mark?.let {
            observer.endReduction(
                it,
                cacheHit = false,
                accepted = result != ColdExactLiraReduction.Interrupted,
            )
        }
        return result
    }

    fun sourceRows(bools: IntArray, node: ColdSearchNode): List<ExactRationalInequality>? {
        val rows = ArrayList<ExactRationalInequality>()
        for (integer in 0 until model.numIntVars) {
            val column = model.numRealVars + integer
            model.intBounds.lowerAsBigInteger(integer)?.let { rows += exactColumnLower(column, it.asFraction()) }
            model.intBounds.upperAsBigInteger(integer)?.let { rows += exactColumnUpper(column, it.asFraction()) }
        }
        for (branch in node.branches) {
            val column = model.numRealVars + branch.variable
            branch.lower?.let { rows += exactColumnLower(column, it.asFraction()) }
            branch.upper?.let { rows += exactColumnUpper(column, it.asFraction()) }
        }
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        val complete = node.forEachSelectedRow(model) { factor, index, row ->
            val truth = row.truthUnder(bools) ?: return@forEachSelectedRow
            val comparison = row.exactComparison(model.numRealVars, truth) { bools[it] == TRUE }
            val direction = if (comparison.op == LinearOp.NE) {
                node.disequalityDirections[ColdRowAddress(factor, index)]
            } else {
                null
            }
            comparison.rowsInto(rows, direction)
        }
        for (atom in node.sourceBranches) rows += atom.sourceRows(model.numRealVars)
        return rows.takeIf { complete }
    }
}

private data class ColdExactLiraReductionKey(
    val bools: List<Int>,
    val branches: List<ColdIntegerBranch>,
    val comparisons: List<Pair<ColdRowAddress, Int>>,
    val directions: List<Pair<ColdRowAddress, LinearOp>>,
    val sourceBranches: List<SourceBoundAtom>,
)

/**
 * The bounded phase of Double-Bounded Reduction in mixed-echelon/Hermite coordinates.
 *
 * Only nonzero integer columns of the transformed double-bounded system are branched.  Lemma 8
 * makes each of those coordinates finite; zero columns are deliberately absent from this search and
 * are filled by [ColdExactLiraReduction.Bounded.extend] after the bounded witness is found.
 */
private class ColdExactReducedLiraSystem(private val reduction: ColdExactLiraReduction.Bounded) {
    private val realColumns = reduction.system.realColumns
    private val integerColumns = reduction.system.integerColumns
    private val columns = realColumns + integerColumns

    fun isBounded(): Boolean = (0 until integerColumns).all { integer ->
        if (!reduction.system.boundedColumn(realColumns + integer)) {
            true
        } else {
            reduction.bounds.integerLower[integer] != null && reduction.bounds.integerUpper[integer] != null
        }
    }

    fun root(node: ColdSearchNode): ColdSearchNode {
        var result = node
        for (integer in 0 until integerColumns) {
            if (!reduction.system.boundedColumn(realColumns + integer)) continue
            result = result.withReducedBranch(
                ColdIntegerBranch(
                    integer,
                    reduction.bounds.integerLower[integer],
                    reduction.bounds.integerUpper[integer],
                ),
            )
        }
        return result
    }

    fun model(node: ColdSearchNode): ExactRationalFeasibilityModel {
        val rows = ArrayList<ExactRationalInequality>(reduction.system.rows.size * 2 + node.reducedBranches.size * 2)
        for (row in reduction.system.rows) {
            rows.add(row.asUpper())
            rows.add(row.asLower())
        }
        for (branch in node.reducedBranches) {
            val column = realColumns + branch.variable
            branch.lower?.let { lower ->
                rows.add(
                    ExactRationalInequality(
                        intArrayOf(column),
                        listOf(BigFraction.MINUS_ONE),
                        BigFraction.of(lower.negate(), BigInteger.ONE),
                    ),
                )
            }
            branch.upper?.let { upper ->
                rows.add(
                    ExactRationalInequality(
                        intArrayOf(column),
                        listOf(BigFraction.ONE),
                        BigFraction.of(upper, BigInteger.ONE),
                    ),
                )
            }
        }
        for (atom in node.sourceBranches) {
            rows += atom.sourceRows(realColumns).map(reduction.system::transform)
        }
        return ExactRationalFeasibilityModel(2 * columns, rows.map { it.overFreeColumns(columns) })
    }

    fun values(witness: List<BigFraction>): List<BigFraction> =
        List(columns) { column -> witness[column] - witness[columns + column] }

    fun fractionalInteger(values: List<BigFraction>): Int? = (0 until integerColumns).firstOrNull { integer ->
        reduction.system.boundedColumn(realColumns + integer) && !values[realColumns + integer].isInteger()
    }

    fun solve(
        node: ColdSearchNode,
        cancellation: Cancellation,
        observer: RationalSimplexObserver? = null,
    ): ColdExactReducedSearchResult {
        if (!isBounded()) return ColdExactReducedSearchResult.Interrupted
        val rooted = root(node)
        val outcome = bigRationalOutcome(model(rooted), cancellation, Int.MAX_VALUE, observer)
        if (outcome.feasibility == RationalFeasibility.INFEASIBLE) return ColdExactReducedSearchResult.Infeasible
        if (outcome.feasibility != RationalFeasibility.FEASIBLE) return ColdExactReducedSearchResult.Interrupted
        val values = values(checkNotNull(outcome.witness))
        val integer = fractionalInteger(values)
        if (integer != null) {
            return ColdExactReducedSearchResult.Split(
                rooted,
                integer,
                values[realColumns + integer].floor(),
            )
        }
        return reduction.extend(values, cancellation, observer)?.takeIf { source ->
            source.satisfiesExactRows(node.sourceBranches.flatMap { it.sourceRows(realColumns) })
        }?.let(ColdExactReducedSearchResult::Found)
            ?: ColdExactReducedSearchResult.Interrupted
    }
}

private sealed interface ColdExactReducedSearchResult {
    data class Found(val sourceValues: List<BigFraction>) : ColdExactReducedSearchResult
    data class Split(val node: ColdSearchNode, val integer: Int, val floor: BigInteger) : ColdExactReducedSearchResult
    data object Infeasible : ColdExactReducedSearchResult
    data object Interrupted : ColdExactReducedSearchResult
}

private fun ColdExactLiraReduction.Bounded.extend(
    transformed: List<BigFraction>,
    cancellation: Cancellation,
    observer: RationalSimplexObserver? = null,
): List<BigFraction>? {
    val realFree = (0 until system.realColumns).filterNot(system::boundedColumn)
    val integerFree = (0 until system.integerColumns).filterNot { integer ->
        system.boundedColumn(system.realColumns + integer)
    }.map { integer -> system.realColumns + integer }
    val free = realFree + integerFree
    val compact = free.withIndex().associate { (index, column) -> column to index }
    val extensionRows = unboundedRows.map { source ->
        val transformedRow = system.transform(source)
        var rhs = transformedRow.rhs
        val coefficients = HashMap<Int, BigFraction>()
        for (entry in transformedRow.columns.indices) {
            val column = transformedRow.columns[entry]
            val coefficient = transformedRow.coefficients[entry]
            val target = compact[column]
            if (target == null) {
                rhs -= coefficient * transformed[column]
            } else {
                coefficients[target] = coefficient
            }
        }
        val ordered = coefficients.entries.sortedBy { it.key }
        ExactRationalInequality(
            ordered.map { it.key }.toIntArray(),
            ordered.map { it.value },
            rhs,
            transformedRow.strict,
        )
    }
    val extension = exactMixedUnitCubeSolution(
        extensionRows,
        realColumns = realFree.size,
        integerColumns = integerFree.size,
        cancellation,
        observer,
    ) ?: return null
    val completed = transformed.toMutableList()
    for ((index, column) in free.withIndex()) completed[column] = extension[index]
    val recovered = system.recover(completed)
    return recovered.takeIf { values -> values.satisfiesExactRows(sourceRows) }
}

private fun ExactMixedBoundedRow.asUpper(): ExactRationalInequality {
    val ordered = coefficients.entries.sortedBy { it.key }
    return ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, upper, upperStrict)
}

private fun ExactMixedBoundedRow.asLower(): ExactRationalInequality {
    val ordered = coefficients.entries.sortedBy { it.key }
    return ExactRationalInequality(
        ordered.map { it.key }.toIntArray(),
        ordered.map { it.value.negated() },
        lower.negated(),
    )
}

internal fun ExactRationalInequality.overFreeColumns(variables: Int): ExactRationalInequality {
    val terms = ArrayList<Pair<Int, BigFraction>>(columns.size * 2)
    for (entry in columns.indices) {
        terms.add(columns[entry] to coefficients[entry])
        terms.add(variables + columns[entry] to coefficients[entry].negated())
    }
    terms.sortBy { it.first }
    return ExactRationalInequality(terms.map { it.first }.toIntArray(), terms.map { it.second }, rhs, strict)
}

private fun List<BigFraction>.satisfiesExactRows(rows: List<ExactRationalInequality>): Boolean = rows.all { row ->
    var activity = BigFraction.ZERO
    for (entry in row.columns.indices) activity += this[row.columns[entry]] * row.coefficients[entry]
    if (row.strict) activity < row.rhs else activity <= row.rhs
}

// Source integer values beyond the largest exactly representable double are a useful diagnostic for
// model conversions that would otherwise silently lose an integer unit.
private val WIDE_INTEGER_LIMIT = BigInteger.fromLong(1L shl 53)

private fun List<ExactRationalInequality>.hasWideIntegerData(): Boolean = any { row ->
    (row.rhs.den == BigInteger.ONE && row.rhs.num.abs() > WIDE_INTEGER_LIMIT) ||
        row.coefficients.any { coefficient ->
            coefficient.den == BigInteger.ONE && coefficient.num.abs() > WIDE_INTEGER_LIMIT
        }
}

private data class ColdIntegerBranch(val variable: Int, val lower: BigInteger? = null, val upper: BigInteger? = null)

private data class ColdIntegerLinearBranch(
    val variables: IntArray,
    val coefficients: Array<BigInteger>,
    val lower: BigInteger? = null,
    val upper: BigInteger? = null,
) {
    fun sameShape(other: ColdIntegerLinearBranch): Boolean =
        variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)
}

private data class ColdRowAddress(val factor: Int, val row: Int) : Comparable<ColdRowAddress> {
    override fun compareTo(other: ColdRowAddress): Int = compareValuesBy(
        this,
        other,
        ColdRowAddress::factor,
        ColdRowAddress::row,
    )
}

private data class ColdSearchNode(
    val branches: List<ColdIntegerBranch> = emptyList(),
    val sourceBranches: List<SourceBoundAtom> = emptyList(),
    val retainedReduction: ColdExactLiraReduction.Bounded? = null,
    val reducedBranches: List<ColdIntegerBranch> = emptyList(),
    val transformedBranches: List<ColdIntegerLinearBranch> = emptyList(),
    val comparisonChoices: Map<ColdRowAddress, Int> = emptyMap(),
    val disequalityDirections: Map<ColdRowAddress, LinearOp> = emptyMap(),
) {
    fun withBranch(branch: ColdIntegerBranch): ColdSearchNode {
        val existing = branches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(branches = branches + branch)
        val merged = branches[existing].copy(
            lower = listOfNotNull(branches[existing].lower, branch.lower).maxOrNull(),
            upper = listOfNotNull(branches[existing].upper, branch.upper).minOrNull(),
        )
        return copy(branches = branches.toMutableList().also { it[existing] = merged })
    }

    fun withReducedBranch(branch: ColdIntegerBranch): ColdSearchNode {
        val existing = reducedBranches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(reducedBranches = reducedBranches + branch)
        val merged = reducedBranches[existing].copy(
            lower = listOfNotNull(reducedBranches[existing].lower, branch.lower).maxOrNull(),
            upper = listOfNotNull(reducedBranches[existing].upper, branch.upper).minOrNull(),
        )
        return copy(reducedBranches = reducedBranches.toMutableList().also { it[existing] = merged })
    }

    fun withTransformedBranch(branch: ColdIntegerLinearBranch): ColdSearchNode =
        copy(transformedBranches = transformedBranches + branch)

    fun withTransformedSplit(
        branch: ColdIntegerLinearBranch,
        lower: BigInteger? = null,
        upper: BigInteger? = null,
    ): ColdSearchNode = copy(
        transformedBranches = transformedBranches.map { existing ->
            if (!existing.sameShape(branch)) {
                existing
            } else {
                existing.copy(
                    lower = listOfNotNull(existing.lower, lower).maxOrNull(),
                    upper = listOfNotNull(existing.upper, upper).minOrNull(),
                )
            }
        },
    )

    fun withComparison(factor: ColdRowAddress, literal: Int): ColdSearchNode =
        copy(comparisonChoices = comparisonChoices + (factor to literal))

    fun withDirection(factor: ColdRowAddress, direction: LinearOp): ColdSearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun nextComparison(model: Problem): ColdRowAddress? = model.factors.indices.firstOrNull { index ->
        model.factors[index].linearForm is LinearForm.Disjunction &&
            ColdRowAddress(index, 0) !in comparisonChoices
    }?.let { ColdRowAddress(it, 0) }

    inline fun forEachSelectedRow(model: Problem, action: (Int, Int, LinearRow) -> Unit): Boolean {
        for ((index, factor) in model.factors.withIndex()) {
            val rows = factor.linearRows
            if (factor.linearForm is LinearForm.Disjunction) {
                val selected = comparisonChoices[ColdRowAddress(index, 0)] ?: return false
                action(index, selected, rows[selected])
            } else {
                for (rowIndex in rows.indices) action(index, rowIndex, rows[rowIndex])
            }
        }
        return true
    }

    fun nextDisequality(model: Problem, bools: IntArray): ColdRowAddress? {
        forEachSelectedRow(model) { factor, index, row ->
            val truth = row.truthUnder(bools) ?: return@forEachSelectedRow
            if ((if (truth) row.relation else row.relation.complemented()) != LinearOp.NE) return@forEachSelectedRow
            val address = ColdRowAddress(factor, index)
            if (address !in disequalityDirections) return address
        }
        return null
    }
}

private fun ColdSearchNode.withReductionBounds(reduction: ColdExactLiraReduction.Bounded): ColdSearchNode {
    var bounded = this
    for (integer in 0 until reduction.system.integerColumns) {
        val lower = reduction.bounds.integerLower[integer]
        val upper = reduction.bounds.integerUpper[integer]
        if (lower == null && upper == null) continue
        val coefficients = reduction.system.transformedIntegerCoefficients(integer)
        val branch = ColdIntegerLinearBranch(coefficients.index, coefficients.value, lower, upper)
        if (bounded.transformedBranches.none { it.sameShape(branch) }) bounded = bounded.withTransformedBranch(branch)
    }
    return bounded
}

private fun ColdSearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): ColdSearchNode {
    var bounded = this
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(BigInteger::fromLong)
        val upper = upperBound(integer)?.let(BigInteger::fromLong)
        if (lower != null || upper != null) {
            bounded = bounded.withBranch(ColdIntegerBranch(integer, lower, upper))
        }
    }
    return bounded
}

private fun SourceBoundAtom.sourceRows(realColumns: Int): List<ExactRationalInequality> {
    val columns = terms.associate { term ->
        val column = when (val key = term.source) {
            is SearchIntValue -> realColumns + key.variable
            is SearchRealValue -> key.variable
            else -> error("unsupported registered source")
        }
        column to if (upper) term.coefficient else term.coefficient.negated()
    }.entries.sortedBy { it.key }
    return listOf(
        ExactRationalInequality(
            columns.map { it.key }.toIntArray(),
            columns.map { it.value },
            if (upper) threshold else threshold.negated(),
            strict,
        ),
    )
}

private fun BigFraction.isInteger(): Boolean = den == BigInteger.ONE

private fun BigFraction.floor(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private const val SOURCE_EPOCH_MAX_NS = 100_000_000L

/**
 * Exact QF_LRA satisfiability over the source model's Boolean skeleton and continuous columns.
 *
 * Each Boolean leaf emits only its active real atoms into the existing LP assembler, then the exact
 * rational simplex decides that conjunction. The finite CP and double-simplex lanes are never entered.
 */
class ExactLraSolver(override val model: Problem) : Theory<ExactLraAssignment> {
    private var smtStats: SmtStatsSink? = null

    init {
        require(model.supportsExactLra()) {
            "exact LRA search requires a pure-real linear source model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLraAssignment> {
        if (model.factors.any { factor ->
                factor.linearForm is LinearForm.Disjunction || factor.linearRows.any { row ->
                    row.relation == LinearOp.NE ||
                        (row.relation == LinearOp.EQ && row.activator != LinearRow.ALWAYS && !bools[row.activator])
                }
            }
        ) {
            return when (val result = checkExactLinear(model, bools, context, smtStats)) {
                is TheoryCheck.Sat -> TheoryCheck.Sat(
                    ExactLraAssignment(result.assignment.bools, result.assignment.reals),
                )

                is TheoryCheck.Infeasible -> TheoryCheck.Infeasible(result.explanation)

                TheoryCheck.Cancelled -> TheoryCheck.Cancelled
            }
        }
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        val relaxation = ColdQfLraSystem(model).build { bools[it] }
        val outcome = bigRationalOutcome(
            relaxation.model,
            Cancellation(context::cancelled),
            maxPivots = Int.MAX_VALUE,
        )
        return when (outcome.feasibility) {
            RationalFeasibility.FEASIBLE -> {
                val telemetry = smtStats?.let { stats ->
                    relaxation.model.rowStrict.any { it }.also { strict ->
                        stats.observeWitnessCandidate(strict, wide = false)
                    }
                }
                val witness = requireNotNull(outcome.witness)
                TheoryCheck.Sat(
                    ExactLraAssignment(
                        bools.copyOf(),
                        List(model.numRealVars) { real ->
                            witness[real] - witness[model.numRealVars + real]
                        },
                    ),
                ).also { telemetry?.let { strict -> smtStats?.observeWitnessAccepted(strict, wide = false) } }
            }

            RationalFeasibility.UNKNOWN -> TheoryCheck.Cancelled

            RationalFeasibility.INFEASIBLE -> TheoryCheck.Infeasible(relaxation.explanation(outcome.conflict))
        }
    }

    internal fun observeWith(stats: SmtStatsSink) {
        smtStats = stats
    }
}

internal class ColdQfLraSystem(private val model: Problem) {
    fun build(booleanValue: (Int) -> Boolean?): ColdQfLraRelaxation {
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
        return ColdQfLraRelaxation(
            ExactRationalFeasibilityModel(2 * columns, rows.map { it.overFreeColumns(columns) }),
            premises,
            model.exactLpSourceColumns(),
            exactRows,
        )
    }
}

internal class ColdQfLraRelaxation(
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
