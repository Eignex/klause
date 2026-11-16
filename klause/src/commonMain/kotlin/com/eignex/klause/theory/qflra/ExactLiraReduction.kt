package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactMixedBoundedRow
import com.eignex.klause.lp.ExactMixedEchelonHermite
import com.eignex.klause.lp.ExactMixedTriangularBounds
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.closeSourceLpOwners
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactMixedEchelonHermite
import com.eignex.klause.lp.exactMixedTriangularBounds
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.lp.sourceDoubleBoundedSplit
import com.eignex.klause.lp.sourceEqualityRows
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_TWO
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.negate

internal sealed interface ExactLiraReduction {
    /** The bounded transformed system proves this leaf impossible. */
    data object Infeasible : ExactLiraReduction

    class Bounded(
        val system: ExactMixedEchelonHermite,
        val bounds: ExactMixedTriangularBounds,
        val unboundedRows: List<ExactRationalInequality>,
        val sourceRows: List<ExactRationalInequality>,
        val budget: SourceLpBudget,
    ) : ExactLiraReduction,
        AutoCloseable {
        val solver = ExactReducedLiraSystem(this)
        var extension: SourceLp? = null

        override fun close() {
            closeSourceLpOwners(listOfNotNull(solver, extension))
        }
    }

    data object Interrupted : ExactLiraReduction
}

/**
 * Cache the Boolean-leaf Double-Bounded Reduction artefact across integer branch-and-bound nodes.
 *
 * Reduced-coordinate branches are deliberately not part of this key: they search the fixed
 * double-bounded artefact, while source bounds and Boolean/disjunction choices select that artefact.
 */
internal class ExactLiraReductionCache(
    private val model: Problem,
    private val exactForms: List<List<ExactRowForm>>,
    private val disjunctionAtoms: Map<Int, List<DisjunctAtom>>,
    solveContext: () -> LpSolveContext,
    onWork: (SourceLpWorkStats) -> Unit,
) : AutoCloseable {
    private val budget = SourceLpBudget(solveContext = solveContext, onWork = onWork)

    override fun close() {
        val owners = results.values.filterIsInstance<ExactLiraReduction.Bounded>()
        results.clear()
        closeSourceLpOwners(owners)
    }
    private val results = HashMap<ExactLiraReductionKey, ExactLiraReduction>()

    fun completeReals(
        columns: Int,
        rows: List<ExactRationalInequality>,
        stop: ExecutionControl,
        onStage: (String) -> Unit,
    ): List<BigFraction>? = completeMixedRealComponents(columns, rows, budget, stop, onStage)

    fun reduce(
        bools: IntArray,
        node: SearchNode,
        cancellation: ExecutionControl,
        observer: SmtStatsSink? = null,
    ): ExactLiraReduction {
        return budget.run(emptyList(), model.numRealVars + model.numIntVars, cancellation) { token ->
            if (!admitsSourcePreparation(node)) return@run ExactLiraReduction.Interrupted
            reduceAdmitted(bools, node, token, observer)
        } ?: ExactLiraReduction.Interrupted
    }

    private fun admitsSourcePreparation(node: SearchNode): Boolean {
        val searched = node.searchedBranches(disjunctionAtoms)
        if (model.numBoolVars > 512 || model.factors.size > 128 || node.branches.size > 128 ||
            searched.size > 128 || searched.sumOf { it.terms.size.toLong() } > 512L
        ) {
            return false
        }
        var terms = 0L
        var rows = 0L
        for (factor in model.factors) {
            if (factor.linearRows.size > 128) return false
            for (row in factor.linearRows) {
                rows++
                terms += row.size
                if (rows > 128L || terms > 512L) return false
                val constants = row.constants
                if (constants is IntegralConstants && (
                        constants.exactBound.magnitudeBitLength() > 4096 ||
                            (0 until row.size).any { constants.exactCoeff(it).magnitudeBitLength() > 4096 }
                        )
                ) {
                    return false
                }
            }
        }
        for (integer in 0 until model.numIntVars) {
            if (model.intBounds.lowerAsBigInteger(integer)?.magnitudeBitLength()?.let { it > 4096 } == true ||
                model.intBounds.upperAsBigInteger(integer)?.magnitudeBitLength()?.let { it > 4096 } == true
            ) {
                return false
            }
        }
        if (node.branches.any { branch ->
                branch.variable !in 0 until model.numIntVars ||
                    branch.lower?.magnitudeBitLength()?.let { it > 4096 } == true ||
                    branch.upper?.magnitudeBitLength()?.let { it > 4096 } == true
            }
        ) {
            return false
        }
        return node.sourceBranches.all { atom ->
            listOf(
                atom.threshold,
            ).admittedSourcePoint() && atom.terms.all { listOf(it.coefficient).admittedSourcePoint() }
        }
    }

    private fun reduceAdmitted(
        bools: IntArray,
        node: SearchNode,
        cancellation: ExecutionControl,
        observer: SmtStatsSink?,
    ): ExactLiraReduction {
        val mark = observer?.beginReduction()
        val key = ExactLiraReductionKey(
            bools.toList(),
            node.branches.sortedBy { it.variable },
            node.rowAssertions.entries.sortedBy { it.key }.map {
                it.key to (it.value.payload as NamedDisjunctDecision).holds
            },
            node.disequalityDirections.entries.sortedBy { it.key }.map { it.key to it.value },
            // The rows are a conjunction, so the same branches reached in another order select the same artefact.
            node.sourceBranches.toSet(),
        )
        results[key]?.let { cached ->
            mark?.let {
                observer.endReduction(
                    it,
                    cacheHit = true,
                    accepted = cached != ExactLiraReduction.Interrupted,
                )
            }
            return cached
        }
        val rows = sourceRows(bools, node, cancellation) ?: return ExactLiraReduction.Interrupted.also {
            mark?.let { observer.endReduction(it, cacheHit = false, accepted = false) }
        }
        var admitted = false
        var result = reduceRows(rows, cancellation) { admitted = true }
        if (!admitted) {
            val equalities = sourceEqualityRows(rows, model.numRealVars + model.numIntVars, budget, cancellation)
            if (equalities != null && equalities.isNotEmpty() && equalities.size < rows.size) {
                val subset = reduceRows(equalities, cancellation)
                if (subset == ExactLiraReduction.Infeasible && !cancellation()) result = subset
                (subset as? ExactLiraReduction.Bounded)?.close()
            }
        }
        results[key] = result
        mark?.let { observer.endReduction(it, cacheHit = false, accepted = result != ExactLiraReduction.Interrupted) }
        return result
    }

    private fun reduceRows(
        rows: List<ExactRationalInequality>,
        cancellation: ExecutionControl,
        onAdmitted: () -> Unit = {},
    ): ExactLiraReduction = budget.run(rows, model.numRealVars + model.numIntVars, cancellation) { token ->
        onAdmitted()
        when (
            val split = sourceDoubleBoundedSplit(
                rows,
                model.numRealVars + model.numIntVars,
                budget,
                token,
            )
        ) {
            ExactDoubleBoundedSplit.Infeasible -> ExactLiraReduction.Infeasible

            ExactDoubleBoundedSplit.Unknown -> ExactLiraReduction.Interrupted

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
                    cancellation = token,
                )
                if (transformed == null) {
                    ExactLiraReduction.Interrupted
                } else {
                    val bounds = exactMixedTriangularBounds(transformed)
                    if (bounds.inconsistent) {
                        ExactLiraReduction.Infeasible
                    } else {
                        ExactLiraReduction.Bounded(
                            transformed,
                            bounds,
                            split.unbounded.map(rows::get),
                            rows,
                            budget,
                        )
                    }
                }
            }
        }
    } ?: ExactLiraReduction.Interrupted

    fun sourceRows(bools: IntArray, node: SearchNode, cancellation: ExecutionControl): List<ExactRationalInequality>? {
        val rows = ArrayList<ExactRationalInequality>()
        val published = node.branches.associateBy { it.variable }
        for (integer in 0 until model.numIntVars) {
            if (cancellation()) return null
            val column = model.numRealVars + integer
            val branch = published[integer]
            maxOfNullable(model.intBounds.lowerAsBigInteger(integer), branch?.lower)?.let {
                rows += exactColumnLower(column, it.asFraction())
            }
            minOfNullable(model.intBounds.upperAsBigInteger(integer), branch?.upper)?.let {
                rows += exactColumnUpper(column, it.asFraction())
            }
        }
        for (real in 0 until model.numRealVars) {
            if (cancellation()) return null
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        val complete = node.forEachSelectedRow(model, disjunctionAtoms) { factor, index, row ->
            if (cancellation()) return null
            val truth = node.rowTruth(RowAddress(factor, index), row, bools) ?: return@forEachSelectedRow
            val comparison = exactForms[factor][index].comparison(truth) { bools[it] == LIRA_TRUE }
            val direction = if (comparison.op == LinearOp.NE) {
                node.disequalityDirections[RowAddress(factor, index)]
            } else {
                null
            }
            comparison.rowsInto(rows, direction)
        }
        for (atom in node.sourceBranches) {
            if (cancellation()) return null
            rows += atom.sourceRows(model.numRealVars)
        }
        return rows.takeIf { complete }
    }
}

private data class ExactLiraReductionKey(
    val bools: List<Int>,
    val branches: List<IntegerBranch>,
    val comparisons: List<Pair<RowAddress, Boolean>>,
    val directions: List<Pair<RowAddress, LinearOp>>,
    val sourceBranches: Set<SourceBoundAtom>,
)

/**
 * The bounded phase of the Double-Bounded Reduction in mixed-echelon/Hermite coordinates; M. Bromberger,
 * "A Reduction from Unbounded Linear Mixed Arithmetic Problems into Bounded Problems", IJCAR 2018,
 * https://doi.org/10.1007/978-3-319-94205-6_22.
 *
 * Only nonzero integer columns of the transformed double-bounded system are branched. Lemma 8 of that paper
 * makes each of those coordinates finite; zero columns are deliberately absent from this search and
 * are filled by [ExactLiraReduction.Bounded.extend] after the bounded witness is found.
 */
internal class ExactReducedLiraSystem(private val reduction: ExactLiraReduction.Bounded) : AutoCloseable {
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

    fun root(node: SearchNode): SearchNode {
        var result = node
        for (integer in 0 until integerColumns) {
            if (!reduction.system.boundedColumn(realColumns + integer)) continue
            result = result.withReducedBranch(
                IntegerBranch(
                    integer,
                    reduction.bounds.integerLower[integer],
                    reduction.bounds.integerUpper[integer],
                ),
            )
        }
        return result
    }

    fun model(node: SearchNode): List<ExactRationalInequality> {
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
                        BigFraction.of(lower.negate(), BIG_ONE),
                    ),
                )
            }
            branch.upper?.let { upper ->
                rows.add(
                    ExactRationalInequality(
                        intArrayOf(column),
                        listOf(BigFraction.ONE),
                        BigFraction.of(upper, BIG_ONE),
                    ),
                )
            }
        }
        for (atom in node.sourceBranches) {
            rows += atom.sourceRows(realColumns).map(reduction.system::transform)
        }
        return rows
    }

    private val owner = SourceLp(model(root(SearchNode())), columns, reduction.budget)

    override fun close() = owner.close()

    fun fractionalInteger(values: List<BigFraction>): Int? = (0 until integerColumns).firstOrNull { integer ->
        reduction.system.boundedColumn(realColumns + integer) && !values[realColumns + integer].isInteger()
    }

    fun solve(node: SearchNode, searched: List<SourceBoundAtom>,
        cancellation: ExecutionControl): ExactReducedSearchResult {
        return reduction.budget.run(reduction.sourceRows, columns, cancellation) { token ->
            if (searched.size > 128 || searched.sumOf { it.terms.size.toLong() } > 512L ||
                node.sourceBranches.any { atom ->
                    !listOf(
                        atom.threshold,
                    ).admittedSourcePoint() || atom.terms.any { !listOf(it.coefficient).admittedSourcePoint() }
                }
            ) {
                return@run ExactReducedSearchResult.Interrupted
            }
            if (!isBounded()) return@run ExactReducedSearchResult.Interrupted
            val rooted = root(node)
            val outcome = owner.solve(
                token,
                branches = node.sourceBranches.flatMap { it.sourceRows(realColumns).map(reduction.system::transform) },
            ) ?: return@run ExactReducedSearchResult.Interrupted
            if (outcome.verdict == LpVerdict.INFEASIBLE) return@run ExactReducedSearchResult.Infeasible
            val values = outcome.exactPrimal?.take(columns) ?: return@run ExactReducedSearchResult.Interrupted
            val integer = fractionalInteger(values)
            if (integer != null) {
                val value = values[realColumns + integer]
                return@run ExactReducedSearchResult.Split(
                    rooted,
                    integer,
                    floorDiv(value.num, value.den),
                )
            }
            reduction.extend(values, token)?.takeIf { source ->
                source.satisfiesSourceRows(node.sourceBranches.flatMap { it.sourceRows(realColumns) }, token)
            }?.let(ExactReducedSearchResult::Found)
                ?: ExactReducedSearchResult.Interrupted
        } ?: ExactReducedSearchResult.Interrupted
    }
}

internal sealed interface ExactReducedSearchResult {
    data class Found(val sourceValues: List<BigFraction>) : ExactReducedSearchResult
    data class Split(val node: SearchNode, val integer: Int, val floor: BigInt) : ExactReducedSearchResult
    data object Infeasible : ExactReducedSearchResult
    data object Interrupted : ExactReducedSearchResult
}

private fun ExactLiraReduction.Bounded.extend(
    transformed: List<BigFraction>,
    cancellation: ExecutionControl,
): List<BigFraction>? {
    return budget.run(
        unboundedRows,
        system.realColumns + system.integerColumns,
        cancellation,
        rhs = transformed,
    ) { token ->
        val realFree = (0 until system.realColumns).filterNot(system::boundedColumn)
        val integerFree = (0 until system.integerColumns).filterNot { integer ->
            system.boundedColumn(system.realColumns + integer)
        }.map { integer -> system.realColumns + integer }
        val free = realFree + integerFree
        val compact = free.withIndex().associate { (index, column) -> column to index }
        val extensionRows = unboundedRows.map { source ->
            if (token()) return@run null
            val transformedRow = system.transform(source)
            var rhs = transformedRow.rhs
            val coefficients = HashMap<Int, BigFraction>()
            for (entry in transformedRow.columns.indices) {
                if (token()) return@run null
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
        val half = BigFraction.of(BIG_ONE, BIG_TWO)
        val shifted = extensionRows.map { row ->
            if (token()) return@run null
            var norm = BigFraction.ZERO
            for (i in row.columns.indices) {
                if (token()) return@run null
                if (row.columns[i] >= realFree.size) {
                    val value = row.coefficients[i]
                    norm += if (value < BigFraction.ZERO) value.negated() else value
                }
            }
            ExactRationalInequality(row.columns, row.coefficients, row.rhs - half * norm, row.strict)
        }
        val owner = extension ?: SourceLp(shifted, free.size, budget).also { extension = it }
        val point = owner.solve(token, rhs = shifted.map { it.rhs })?.exactPrimal?.take(free.size) ?: return@run null
        val extension = point.mapIndexed { index, value ->
            if (token()) return@run null
            if (index < realFree.size) value else {
                val shiftedValue = value + half
                floorDiv(shiftedValue.num, shiftedValue.den).asFraction()
            }
        }
        if (!extension.satisfiesSourceRows(extensionRows, token)) return@run null
        val completed = transformed.toMutableList()
        for ((index, column) in free.withIndex()) completed[column] = extension[index]
        if (token()) return@run null
        val recovered = system.recover(completed)
        recovered.takeIf { values -> values.satisfiesSourceRows(sourceRows, token) }
    }
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
