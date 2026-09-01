package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.factor.arithmetic.complemented
import com.eignex.klause.factor.arithmetic.linearRow
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ExactMixedBoundedRow
import com.eignex.klause.lp.ExactMixedEchelonHermite
import com.eignex.klause.lp.ExactMixedTriangularBounds
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.exactMixedEchelonHermite
import com.eignex.klause.lp.exactMixedTriangularBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.BigRationalTableauRow
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.simplex.exact.exactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.exactMixedUnitCubeSolution
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.MutableIntObjectMap
import com.ionspin.kotlin.bignum.integer.BigInteger

/** An exact integer/rational witness for an open QF_LIRA or QF_LIA model. */
data class ExactLiraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Arbitrary-precision integer values indexed by model integer variable id. */
    val ints: Array<BigInteger>,
    /** Rational real values indexed by model real variable id. */
    val reals: List<BigFraction>,
)

/**
 * Exact feasibility for the supported open QF_LIRA and QF_LIA fragments.
 *
 * The Boolean skeleton is fixed first. At a Boolean leaf the rational simplex sees both integer and
 * real columns. A fractional integer witness is split at its exact [BigInteger] floor, so the child
 * boxes are disjoint and cover every integer value. This deliberately lives beside QF_LRA rather than
 * entering finite CP: the only branching here is theory-local integrality branching.
 */
class ExactLiraSolver(override val model: Problem) : Theory<ExactLiraAssignment> {
    init {
        require(model.supportsExactLira()) { "exact LIRA search requires a supported integer-containing linear model" }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLiraAssignment> {
        val cancellation = Cancellation(context::cancelled)
        return when (
            val result = ExactIntegerSearch(
                model = model,
                bools = bools.toStates(),
                cancellation = cancellation,
                consumeLeaf = context::consumeCheck,
                lowerBound = context::intLowerBound,
                upperBound = context::intUpperBound,
                emitAssignment = true,
            ).run()
        ) {
            is IntegerSearchResult.Found -> TheoryCheck.Sat(checkNotNull(result.assignment))
            IntegerSearchResult.Infeasible -> TheoryCheck.Infeasible()
            IntegerSearchResult.Cancelled, IntegerSearchResult.Budget -> TheoryCheck.Cancelled
        }
    }
}

/** Incremental exact QF_LIRA and QF_LIA search component. */
class ExactLiraSearchComponent(
    private val model: Problem,
    private val modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
) : TheoryComponent,
    SearchBrancher {
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val exactActivators = BooleanArray(model.numBoolVars).also { activators ->
        for (factor in model.factors) {
            val activator = factor.linearRow()?.activator ?: continue
            if (activator != FactorRow.ALWAYS) activators[activator] = true
        }
    }
    private val root = SearchNode()
    private val reduction = ExactLiraReductionCache(model)
    private val nodesByLevel = MutableIntObjectMap<SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null
    private var partialDirty = true

    init {
        require(
            model.supportsExactLira(),
        ) { "exact LIRA component requires a supported integer-containing linear model" }
        nodesByLevel.put(0, root)
    }

    override fun initialize(context: SearchContext): ComponentResult = propagate(context)

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
                if (exactActivators[variable]) partialDirty = true
            }

            is SearchDecision.Theory -> (decision.decision as? ExactLiraDecision)?.let { branch ->
                node = branch.node
                nodesByLevel.put(context.decisionLevel, node)
            }

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> Unit
        }
        assignment = null
        outcome = null
        return ComponentResult.Consistent
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (!partialDirty || bools.none { it == UNASSIGNED }) return ComponentResult.Consistent
        partialDirty = false
        if (!hasActiveExactConstraint()) return ComponentResult.Consistent
        val result = ExactIntegerSearch(
            model = model,
            bools = bools,
            cancellation = Cancellation(context::cancelled),
            consumeLeaf = context::consumeCheck,
            lowerBound = { null },
            upperBound = { null },
            emitAssignment = false,
        ).run()
        return when (result) {
            is IntegerSearchResult.Found -> ComponentResult.Consistent
            IntegerSearchResult.Infeasible -> ComponentResult.Conflict(partialExplanation())
            IntegerSearchResult.Cancelled, IntegerSearchResult.Budget -> ComponentResult.Indeterminate
        }
    }

    override fun retract(decisionLevel: Int) {
        var exactRowChanged = false
        for (variable in bools.indices) {
            if (boolLevels[variable] > decisionLevel) {
                bools[variable] = UNASSIGNED
                boolLevels[variable] = -1
                if (exactActivators[variable]) exactRowChanged = true
            }
        }
        nodesByLevel.removeKeysAbove(decisionLevel)
        node = nodesByLevel.valueAtMaxKey() ?: root
        assignment = null
        outcome = null
        if (exactRowChanged) partialDirty = true
    }

    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        if (context.cancelled() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val values = bools.toCompleteValues()
        val current = node.withPublishedBounds(
            model.numIntVars,
            context::intLowerBound,
            context::intUpperBound,
        )
        val comparison = current.nextComparison(model)
        if (comparison >= 0) {
            val clause = model.factors[comparison] as ComparisonClause
            return clause.vars.indices.map { literal ->
                decision(current.withComparison(comparison, literal))
            }
        }
        val disequality = current.nextDisequality(model, bools)
        if (disequality >= 0) {
            return listOf(
                decision(current.withDirection(disequality, LinearOp.GE)),
                decision(current.withDirection(disequality, LinearOp.LE)),
            )
        }
        val reduced = reduction.reduce(bools, current, Cancellation(context::cancelled))
        if (reduced == ExactLiraReduction.Infeasible) {
            outcome = ComponentCheck.Infeasible()
            return null
        }
        if (reduced == ExactLiraReduction.Interrupted) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        reduced as ExactLiraReduction.Bounded
        when (val bounded = ExactReducedLiraSystem(reduced).solve(current, Cancellation(context::cancelled))) {
            is ExactReducedSearchResult.Split -> {
                return listOf(
                    decision(
                        bounded.node.withReducedBranch(
                            IntegerBranch(bounded.integer, lower = bounded.floor + BigInteger.ONE),
                        ),
                    ),
                    decision(bounded.node.withReducedBranch(IntegerBranch(bounded.integer, upper = bounded.floor))),
                )
            }

            ExactReducedSearchResult.Infeasible -> {
                outcome = ComponentCheck.Infeasible()
                return null
            }

            ExactReducedSearchResult.Interrupted -> {
                outcome = ComponentCheck.Indeterminate
                return null
            }

            is ExactReducedSearchResult.Found -> {
                val source = bounded.sourceValues
                if ((0 until model.numIntVars).any { integer -> !source[model.numRealVars + integer].isInteger() }) {
                    outcome = ComponentCheck.Indeterminate
                    return null
                }
                assignment = ExactLiraAssignment(
                    values,
                    Array(model.numIntVars) { integer -> source[model.numRealVars + integer].num },
                    List(model.numRealVars) { real -> source[real] },
                )
                outcome = ComponentCheck.Feasible
                return null
            }
        }
    }

    override fun check(context: SearchContext): ComponentCheck = outcome ?: ComponentCheck.Indeterminate

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { value ->
            model.put(this, value)
            modelContribution?.invoke(value, model)
        }
    }

    private fun decision(node: SearchNode): SearchDecision = SearchDecision.Theory(ExactLiraDecision(node))

    private fun hasActiveExactConstraint(): Boolean = model.factors.any { factor ->
        if (factor is ComparisonClause) return@any true
        val row = factor.linearRow() ?: return@any false
        row.activator == FactorRow.ALWAYS || bools[row.activator] != UNASSIGNED
    }

    private fun partialExplanation(): SearchExplanation = SearchExplanation(
        bools.indices.mapNotNull { variable ->
            when (bools[variable]) {
                TRUE -> Lit.make(variable, positive = false)
                FALSE -> Lit.make(variable, positive = true)
                else -> null
            }
        }.toIntArray(),
    )
}

private class ExactIntegerSearch(
    private val model: Problem,
    private val bools: IntArray,
    private val cancellation: Cancellation,
    private val consumeLeaf: () -> Boolean,
    private val lowerBound: (Int) -> Long?,
    private val upperBound: (Int) -> Long?,
    private val emitAssignment: Boolean,
) {
    private val reduction = ExactLiraReductionCache(model)

    fun run(): IntegerSearchResult {
        val stack = ArrayDeque<SearchNode>()
        stack.addLast(
            SearchNode().withPublishedBounds(model.numIntVars, lowerBound, upperBound),
        )
        while (stack.isNotEmpty()) {
            if (!consumeLeaf()) return IntegerSearchResult.Budget
            if (cancellation()) return IntegerSearchResult.Cancelled
            val node = stack.removeLast()
            val comparison = node.nextComparison(model)
            if (comparison >= 0) {
                val clause = model.factors[comparison] as ComparisonClause
                for (literal in clause.vars.indices.reversed()) {
                    stack.addLast(node.withComparison(comparison, literal))
                }
                continue
            }
            val disequality = node.nextDisequality(model, bools)
            if (disequality >= 0) {
                stack.addLast(node.withDirection(disequality, LinearOp.GE))
                stack.addLast(node.withDirection(disequality, LinearOp.LE))
                continue
            }
            val reduced = reduction.reduce(bools, node, cancellation)
            if (reduced == ExactLiraReduction.Infeasible) continue
            if (reduced == ExactLiraReduction.Interrupted) return IntegerSearchResult.Cancelled
            reduced as ExactLiraReduction.Bounded
            when (val bounded = ExactReducedLiraSystem(reduced).solve(node, cancellation)) {
                is ExactReducedSearchResult.Split -> {
                    stack.addLast(
                        bounded.node.withReducedBranch(
                            IntegerBranch(bounded.integer, lower = bounded.floor + BigInteger.ONE),
                        ),
                    )
                    stack.addLast(bounded.node.withReducedBranch(IntegerBranch(bounded.integer, upper = bounded.floor)))
                }

                ExactReducedSearchResult.Infeasible -> Unit

                ExactReducedSearchResult.Interrupted -> return IntegerSearchResult.Cancelled

                is ExactReducedSearchResult.Found -> {
                    val source = bounded.sourceValues
                    if ((0 until model.numIntVars).any { integer ->
                            !source[model.numRealVars + integer].isInteger()
                        }
                    ) {
                        return IntegerSearchResult.Cancelled
                    }
                    return IntegerSearchResult.Found(
                        if (emitAssignment) {
                            ExactLiraAssignment(
                                bools.toCompleteValues(),
                                Array(model.numIntVars) { integer -> source[model.numRealVars + integer].num },
                                List(model.numRealVars) { real -> source[real] },
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return IntegerSearchResult.Infeasible
    }
}

private sealed interface IntegerSearchResult {
    data class Found(val assignment: ExactLiraAssignment?) : IntegerSearchResult
    data object Infeasible : IntegerSearchResult
    data object Cancelled : IntegerSearchResult
    data object Budget : IntegerSearchResult
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

private fun FactorRow.truthUnder(bools: IntArray): Boolean? = if (activator == FactorRow.ALWAYS) {
    true
} else {
    when (bools[activator]) {
        TRUE -> true
        FALSE -> false
        else -> null
    }
}

/** The bounded transformed system proves this Boolean/disjunction leaf impossible. */
private sealed interface ExactLiraReduction {
    data object Infeasible : ExactLiraReduction

    class Bounded(
        val system: ExactMixedEchelonHermite,
        val bounds: ExactMixedTriangularBounds,
        val unboundedRows: List<ExactRationalInequality>,
        val sourceRows: List<ExactRationalInequality>,
    ) : ExactLiraReduction

    data object Interrupted : ExactLiraReduction
}

/**
 * Cache the Boolean-leaf Double-Bounded Reduction artefact across integer branch-and-bound nodes.
 *
 * Reduced-coordinate branches are deliberately not part of this key: they search the fixed
 * double-bounded artefact, while source bounds and Boolean/disjunction choices select that artefact.
 */
private class ExactLiraReductionCache(private val model: Problem) {
    private val results = HashMap<ExactLiraReductionKey, ExactLiraReduction>()

    fun reduce(bools: IntArray, node: SearchNode, cancellation: Cancellation): ExactLiraReduction {
        val key = ExactLiraReductionKey(
            bools.toList(),
            node.branches.sortedBy { it.variable },
            node.comparisonChoices.entries.sortedBy { it.key }.map { it.key to it.value },
            node.disequalityDirections.entries.sortedBy { it.key }.map { it.key to it.value },
        )
        results[key]?.let { return it }
        val rows = sourceRows(bools, node) ?: return ExactLiraReduction.Interrupted
        val result = when (
            val split = exactDoubleBoundedSplit(
                rows,
                model.numRealVars + model.numIntVars,
                cancellation,
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
                    cancellation = cancellation,
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
                        )
                    }
                }
            }
        }
        if (!cancellation()) results[key] = result
        return result
    }

    private fun sourceRows(bools: IntArray, node: SearchNode): List<ExactRationalInequality>? {
        val rows = ArrayList<ExactRationalInequality>()
        fun add(terms: Map<Int, BigFraction>, rhs: BigFraction, strict: Boolean = false) {
            val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
            rows.add(
                ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, rhs, strict),
            )
        }
        fun addLower(column: Int, lower: BigInteger) {
            add(mapOf(column to BigFraction.MINUS_ONE), BigFraction.of(-lower, BigInteger.ONE))
        }
        fun addUpper(column: Int, upper: BigInteger) {
            add(mapOf(column to BigFraction.ONE), BigFraction.of(upper, BigInteger.ONE))
        }
        for (integer in 0 until model.numIntVars) {
            val column = model.numRealVars + integer
            model.intBounds.lowerAsBigInteger(integer)?.let { addLower(column, it) }
            model.intBounds.upperAsBigInteger(integer)?.let { addUpper(column, it) }
        }
        for (branch in node.branches) {
            val column = model.numRealVars + branch.variable
            branch.lower?.let { addLower(column, it) }
            branch.upper?.let { addUpper(column, it) }
        }
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { lower ->
                add(mapOf(real to BigFraction.MINUS_ONE), requireNotNull(BigFraction.ofDouble(lower)).negated())
            }
            model.realUpper[real].takeIf(Double::isFinite)?.let { upper ->
                add(mapOf(real to BigFraction.ONE), requireNotNull(BigFraction.ofDouble(upper)))
            }
        }
        for ((index, factor) in model.factors.withIndex()) {
            when (factor) {
                is ComparisonClause -> {
                    val literal = node.comparisonChoices[index] ?: return null
                    addComparison(rows, factor, literal, node.disequalityDirections[index])
                }

                else -> {
                    val row = factor.linearRow() ?: continue
                    val truth = row.truthUnder(bools) ?: continue
                    addFactorRow(rows, row, truth, node.disequalityDirections[index])
                }
            }
        }
        return rows
    }

    private fun addComparison(
        rows: MutableList<ExactRationalInequality>,
        clause: ComparisonClause,
        literal: Int,
        direction: LinearOp?,
    ) {
        val terms = mapOf(model.numRealVars + clause.vars[literal] to BigFraction.ONE)
        val bound = BigFraction.ofLong(clause.consts[literal])
        addRelation(rows, terms, clause.ops[literal], bound, strict = false, direction, hasReals = false)
    }

    private fun addFactorRow(
        rows: MutableList<ExactRationalInequality>,
        row: FactorRow,
        truth: Boolean,
        direction: LinearOp?,
    ) {
        val actualOp = if (truth) row.op else row.op.complemented()
        val actualStrict = if (truth) row.strict else !row.strict
        when (row) {
            is FactorRow.Wide -> {
                val terms = HashMap<Int, BigFraction>()
                for (index in row.intVars.indices) {
                    terms.add(
                        model.numRealVars + row.intVars[index],
                        BigFraction.of(row.coefficients[index], BigInteger.ONE),
                    )
                }
                addRelation(rows, terms, actualOp, BigFraction.of(row.bound, BigInteger.ONE), false, direction, false)
            }

            is FactorRow.Doubles -> {
                val terms = HashMap<Int, BigFraction>()
                val integerCoeffs = row.integerCoeffs
                if (integerCoeffs != null) {
                    for (index in row.intVars.indices) {
                        terms.add(model.numRealVars + row.intVars[index], BigFraction.ofLong(integerCoeffs[index]))
                    }
                } else {
                    for (index in row.intVars.indices) {
                        terms.add(
                            model.numRealVars + row.intVars[index],
                            requireNotNull(BigFraction.ofDouble(row.intCoeffs[index])),
                        )
                    }
                }
                for (index in row.realVars.indices) {
                    terms.add(row.realVars[index], requireNotNull(BigFraction.ofDouble(row.realCoeffs[index])))
                }
                addRelation(
                    rows,
                    terms,
                    actualOp,
                    row.integerBound?.let(BigFraction::ofLong) ?: requireNotNull(BigFraction.ofDouble(row.bound)),
                    actualStrict,
                    direction,
                    row.realVars.isNotEmpty(),
                )
            }
        }
    }

    private fun addRelation(
        rows: MutableList<ExactRationalInequality>,
        terms: Map<Int, BigFraction>,
        op: LinearOp,
        bound: BigFraction,
        strict: Boolean,
        direction: LinearOp?,
        hasReals: Boolean,
    ) {
        fun add(terms: Map<Int, BigFraction>, bound: BigFraction, strict: Boolean) {
            val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
            rows.add(
                ExactRationalInequality(ordered.map { it.key }.toIntArray(), ordered.map { it.value }, bound, strict),
            )
        }
        fun negate(): Map<Int, BigFraction> = terms.mapValues { (_, value) -> value.negated() }
        when (op) {
            LinearOp.LE -> add(terms, bound, strict)

            LinearOp.GE -> add(negate(), bound.negated(), strict)

            LinearOp.EQ -> {
                add(terms, bound, false)
                add(negate(), bound.negated(), false)
            }

            LinearOp.NE -> when (requireNotNull(direction) { "exact disequality direction is missing" }) {
                LinearOp.LE -> add(terms, if (hasReals) bound else bound - BigFraction.ONE, hasReals)

                LinearOp.GE -> add(
                    negate(),
                    if (hasReals) bound.negated() else bound.negated() - BigFraction.ONE,
                    hasReals,
                )

                else -> error("exact disequality direction must be an inequality")
            }
        }
    }
}

private data class ExactLiraReductionKey(
    val bools: List<Int>,
    val branches: List<IntegerBranch>,
    val comparisons: List<Pair<Int, Int>>,
    val directions: List<Pair<Int, LinearOp>>,
)

/**
 * The bounded phase of Double-Bounded Reduction in mixed-echelon/Hermite coordinates.
 *
 * Only nonzero integer columns of the transformed double-bounded system are branched.  Lemma 8
 * makes each of those coordinates finite; zero columns are deliberately absent from this search and
 * are filled by [ExactLiraReduction.Bounded.extend] after the bounded witness is found.
 */
private class ExactReducedLiraSystem(private val reduction: ExactLiraReduction.Bounded) {
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

    fun model(node: SearchNode): ExactRationalFeasibilityModel {
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
        return ExactRationalFeasibilityModel(2 * columns, rows.map { it.overFreeColumns(columns) })
    }

    fun values(witness: List<BigFraction>): List<BigFraction> =
        List(columns) { column -> witness[column] - witness[columns + column] }

    fun fractionalInteger(values: List<BigFraction>): Int? = (0 until integerColumns).firstOrNull { integer ->
        reduction.system.boundedColumn(realColumns + integer) && !values[realColumns + integer].isInteger()
    }

    fun solve(node: SearchNode, cancellation: Cancellation): ExactReducedSearchResult {
        if (!isBounded()) return ExactReducedSearchResult.Interrupted
        val rooted = root(node)
        val outcome = bigRationalOutcome(model(rooted), cancellation, Int.MAX_VALUE)
        if (outcome.feasibility == RationalFeasibility.INFEASIBLE) return ExactReducedSearchResult.Infeasible
        if (outcome.feasibility != RationalFeasibility.FEASIBLE) return ExactReducedSearchResult.Interrupted
        val values = values(checkNotNull(outcome.witness))
        val integer = fractionalInteger(values)
        if (integer != null) {
            return ExactReducedSearchResult.Split(
                rooted,
                integer,
                values[realColumns + integer].floor(),
            )
        }
        return reduction.extend(values, cancellation)?.let(ExactReducedSearchResult::Found)
            ?: ExactReducedSearchResult.Interrupted
    }
}

private sealed interface ExactReducedSearchResult {
    data class Found(val sourceValues: List<BigFraction>) : ExactReducedSearchResult
    data class Split(val node: SearchNode, val integer: Int, val floor: BigInteger) : ExactReducedSearchResult
    data object Infeasible : ExactReducedSearchResult
    data object Interrupted : ExactReducedSearchResult
}

private fun ExactLiraReduction.Bounded.extend(
    transformed: List<BigFraction>,
    cancellation: Cancellation,
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

private fun ExactRationalInequality.overFreeColumns(variables: Int): ExactRationalInequality {
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

private data class IntegerBranch(val variable: Int, val lower: BigInteger? = null, val upper: BigInteger? = null)

private data class IntegerLinearBranch(
    val variables: IntArray,
    val coefficients: Array<BigInteger>,
    val lower: BigInteger? = null,
    val upper: BigInteger? = null,
) {
    fun sameShape(other: IntegerLinearBranch): Boolean =
        variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)
}

private data class SearchNode(
    val branches: List<IntegerBranch> = emptyList(),
    val reducedBranches: List<IntegerBranch> = emptyList(),
    val transformedBranches: List<IntegerLinearBranch> = emptyList(),
    val comparisonChoices: Map<Int, Int> = emptyMap(),
    val disequalityDirections: Map<Int, LinearOp> = emptyMap(),
    val cuts: List<ExactGmiCut> = emptyList(),
) {
    fun withBranch(branch: IntegerBranch): SearchNode {
        val existing = branches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(branches = branches + branch)
        val merged = branches[existing].copy(
            lower = listOfNotNull(branches[existing].lower, branch.lower).maxOrNull(),
            upper = listOfNotNull(branches[existing].upper, branch.upper).minOrNull(),
        )
        return copy(branches = branches.toMutableList().also { it[existing] = merged })
    }

    fun withReducedBranch(branch: IntegerBranch): SearchNode {
        val existing = reducedBranches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(reducedBranches = reducedBranches + branch)
        val merged = reducedBranches[existing].copy(
            lower = listOfNotNull(reducedBranches[existing].lower, branch.lower).maxOrNull(),
            upper = listOfNotNull(reducedBranches[existing].upper, branch.upper).minOrNull(),
        )
        return copy(reducedBranches = reducedBranches.toMutableList().also { it[existing] = merged })
    }

    fun withTransformedBranch(branch: IntegerLinearBranch): SearchNode =
        copy(transformedBranches = transformedBranches + branch)

    fun withTransformedSplit(
        branch: IntegerLinearBranch,
        lower: BigInteger? = null,
        upper: BigInteger? = null,
    ): SearchNode = copy(
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

    fun withComparison(factor: Int, literal: Int): SearchNode =
        copy(comparisonChoices = comparisonChoices + (factor to literal))

    fun withDirection(factor: Int, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun withCut(cut: ExactGmiCut): SearchNode = copy(cuts = cuts + cut)

    fun nextComparison(model: Problem): Int {
        for (index in model.factors.indices) {
            if (model.factors[index] is ComparisonClause && index !in comparisonChoices) return index
        }
        return -1
    }

    fun nextDisequality(model: Problem, bools: IntArray): Int {
        for (index in model.factors.indices) {
            if (index in disequalityDirections) continue
            val comparison = model.factors[index] as? ComparisonClause
            if (comparison != null) {
                val literal = comparisonChoices[index] ?: continue
                if (comparison.ops[literal] == LinearOp.NE) return index
                continue
            }
            val row = model.factors[index].linearRow() ?: continue
            val truth = row.truthUnder(bools) ?: continue
            if ((if (truth) row.op else row.op.complemented()) == LinearOp.NE) return index
        }
        return -1
    }
}

private fun SearchNode.withReductionBounds(reduction: ExactLiraReduction.Bounded): SearchNode {
    var bounded = this
    for (integer in 0 until reduction.system.integerColumns) {
        val lower = reduction.bounds.integerLower[integer]
        val upper = reduction.bounds.integerUpper[integer]
        if (lower == null && upper == null) continue
        val coefficients = reduction.system.transformedIntegerCoefficients(integer)
        val branch = IntegerLinearBranch(coefficients.index, coefficients.value, lower, upper)
        if (bounded.transformedBranches.none { it.sameShape(branch) }) bounded = bounded.withTransformedBranch(branch)
    }
    return bounded
}

private fun SearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): SearchNode {
    var bounded = this
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(BigInteger::fromLong)
        val upper = upperBound(integer)?.let(BigInteger::fromLong)
        if (lower != null || upper != null) {
            bounded = bounded.withBranch(IntegerBranch(integer, lower, upper))
        }
    }
    return bounded
}

private data class ExactLiraDecision(val node: SearchNode) : SearchTheoryDecision

@Suppress("UnusedPrivateClass")
private class QfLiraSystem(private val model: Problem) {
    fun build(bools: IntArray, node: SearchNode, cancellation: Cancellation): QfLiraLeaf? {
        val builder = LpBuilder()
        // Split every source column as p - n. Both halves use true open-above columns rather than
        // the LP probe box: an exact infeasibility proof must never rest on an invented frontier.
        val intsPositive = IntArray(model.numIntVars) { builder.addOpenAboveVar(0L) }
        val intsNegative = IntArray(model.numIntVars) { builder.addOpenAboveVar(0L) }
        val realsPositive = IntArray(model.numRealVars) { builder.addOpenAboveVar(0L) }
        val realsNegative = IntArray(model.numRealVars) { builder.addOpenAboveVar(0L) }
        val constants = BigConstantEncoder(builder, cancellation)
        for (integer in 0 until model.numIntVars) {
            if (cancellation()) return null
            model.intBounds.lowerOrNull(integer)?.let(BigInteger::fromLong)?.let { lower ->
                if (!constants.addBound(intsPositive[integer], intsNegative[integer], Relation.GE, lower)) return null
            }
            model.intBounds.upperOrNull(integer)?.let(BigInteger::fromLong)?.let { upper ->
                if (!constants.addBound(intsPositive[integer], intsNegative[integer], Relation.LE, upper)) return null
            }
        }
        for (real in 0 until model.numRealVars) {
            if (cancellation()) return null
            model.realLower[real].takeIf(Double::isFinite)?.let { lower ->
                builder.addRealRow(
                    intArrayOf(realsPositive[real], realsNegative[real]),
                    doubleArrayOf(1.0, -1.0),
                    Relation.GE,
                    lower,
                )
            }
            model.realUpper[real].takeIf(Double::isFinite)?.let { upper ->
                builder.addRealRow(
                    intArrayOf(realsPositive[real], realsNegative[real]),
                    doubleArrayOf(1.0, -1.0),
                    Relation.LE,
                    upper,
                )
            }
        }
        for (branch in node.branches) {
            if (cancellation()) return null
            if (branch.lower != null) {
                if (!constants.addBound(
                        intsPositive[branch.variable],
                        intsNegative[branch.variable],
                        Relation.GE,
                        branch.lower,
                    )
                ) {
                    return null
                }
            }
            if (branch.upper != null) {
                if (!constants.addBound(
                        intsPositive[branch.variable],
                        intsNegative[branch.variable],
                        Relation.LE,
                        branch.upper,
                    )
                ) {
                    return null
                }
            }
        }
        for (branch in node.transformedBranches) {
            if (cancellation()) return null
            if (branch.lower != null) {
                if (!addIntegerCombinationBound(
                        builder,
                        intsPositive,
                        intsNegative,
                        constants,
                        branch.variables,
                        Array(branch.coefficients.size) { index -> branch.coefficients[index].negate() },
                        Relation.LE,
                        branch.lower.negate(),
                    )
                ) {
                    return null
                }
            }
            if (branch.upper != null) {
                if (!addIntegerCombinationBound(
                        builder,
                        intsPositive,
                        intsNegative,
                        constants,
                        branch.variables,
                        branch.coefficients,
                        Relation.LE,
                        branch.upper,
                    )
                ) {
                    return null
                }
            }
        }
        // Every linear shape states its row the same way; only whether a Boolean gates it differs, and
        // an ungated row is read as one whose activator is true.
        for ((index, factor) in model.factors.withIndex()) {
            if (cancellation()) return null
            if (factor is ComparisonClause) {
                val literal = requireNotNull(node.comparisonChoices[index]) {
                    "comparison clause must be selected before exact feasibility"
                }
                if (!addComparison(
                        intsPositive,
                        intsNegative,
                        constants,
                        factor.vars[literal],
                        factor.ops[literal],
                        factor.consts[literal],
                        node.disequalityDirections[index],
                    )
                ) {
                    return null
                }
                continue
            }
            val row = factor.linearRow() ?: continue
            val truth = row.truthUnder(bools) ?: continue
            val direction = node.disequalityDirections[index]
            when (row) {
                is FactorRow.Wide -> if (!addWide(
                        builder, intsPositive, intsNegative, constants, row.intVars,
                        row.coefficients, if (truth) row.op else row.op.complemented(), row.bound, direction,
                    )
                ) {
                    return null
                }

                is FactorRow.Doubles -> addReified(
                    builder = builder,
                    intsPositive = intsPositive,
                    intsNegative = intsNegative,
                    realsPositive = realsPositive,
                    realsNegative = realsNegative,
                    intVars = row.intVars,
                    intCoeffs = row.intCoeffs,
                    realVars = row.realVars,
                    realCoeffs = row.realCoeffs,
                    op = row.op,
                    bound = row.bound,
                    strict = row.strict,
                    truth = truth,
                    disequalityDirection = direction,
                    hasReals = row.realVars.isNotEmpty(),
                )
            }
        }
        for (cut in node.cuts) {
            if (cancellation()) return null
            builder.addRealRow(cut.columns, cut.coefficients, Relation.GE, cut.rhs)
        }
        return QfLiraLeaf(builder.build(Sense.MINIMIZE), intsPositive, intsNegative, realsPositive, realsNegative)
    }

    private fun addComparison(
        positive: IntArray,
        negative: IntArray,
        constants: BigConstantEncoder,
        variable: Int,
        op: LinearOp,
        bound: Long,
        direction: LinearOp?,
    ): Boolean {
        val (actualOp, actualBound) = lowerWideDisequality(op, BigInteger.fromLong(bound), direction)
        return constants.addBound(
            positive[variable],
            negative[variable],
            when (actualOp) {
                LinearOp.LE -> Relation.LE
                LinearOp.GE -> Relation.GE
                LinearOp.EQ -> Relation.EQ
                LinearOp.NE -> error("comparison disequality must be directed")
            },
            actualBound,
        )
    }

    private fun addReified(
        builder: LpBuilder,
        intsPositive: IntArray,
        intsNegative: IntArray,
        realsPositive: IntArray,
        realsNegative: IntArray,
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
        truth: Boolean,
        disequalityDirection: LinearOp?,
        hasReals: Boolean,
    ) {
        val actualOp = if (truth) {
            op
        } else {
            when (op) {
                LinearOp.LE -> LinearOp.GE
                LinearOp.GE -> LinearOp.LE
                LinearOp.EQ -> LinearOp.NE
                LinearOp.NE -> LinearOp.EQ
            }
        }
        val (rel, rhs, actualStrict) = lowerDisequality(
            actualOp,
            bound,
            if (truth) strict else !strict,
            disequalityDirection,
            hasReals,
        )
        add(
            builder,
            intsPositive,
            intsNegative,
            realsPositive,
            realsNegative,
            intVars,
            intCoeffs,
            realVars,
            realCoeffs,
            rel,
            rhs,
            actualStrict,
        )
    }

    private fun addWide(
        builder: LpBuilder,
        positive: IntArray,
        negative: IntArray,
        constants: BigConstantEncoder,
        variables: IntArray,
        coefficients: Array<BigInteger>,
        op: LinearOp,
        bound: BigInteger,
        direction: LinearOp?,
    ): Boolean {
        val (relation, rhs) = lowerWideDisequality(op, bound, direction)
        val columns = ArrayList<Int>()
        val values = ArrayList<Double>()
        for (index in variables.indices) {
            if (!constants.appendProduct(coefficients[index], positive[variables[index]], columns, values, 1.0)) {
                return false
            }
            if (!constants.appendProduct(coefficients[index], negative[variables[index]], columns, values, -1.0)) {
                return false
            }
        }
        if (!constants.appendConstant(-rhs, columns, values)) return false
        builder.addRealRow(columns.toIntArray(), values.toDoubleArray(), relation(relation), 0.0)
        return true
    }

    private fun addIntegerCombinationBound(
        builder: LpBuilder,
        positive: IntArray,
        negative: IntArray,
        constants: BigConstantEncoder,
        variables: IntArray,
        coefficients: Array<BigInteger>,
        relation: Relation,
        bound: BigInteger,
    ): Boolean {
        val columns = ArrayList<Int>()
        val values = ArrayList<Double>()
        for (index in variables.indices) {
            if (!constants.appendProduct(coefficients[index], positive[variables[index]], columns, values, 1.0)) {
                return false
            }
            if (!constants.appendProduct(coefficients[index], negative[variables[index]], columns, values, -1.0)) {
                return false
            }
        }
        if (!constants.appendConstant(-bound, columns, values)) return false
        builder.addRealRow(columns.toIntArray(), values.toDoubleArray(), relation, 0.0)
        return true
    }

    private fun add(
        builder: LpBuilder,
        intsPositive: IntArray,
        intsNegative: IntArray,
        realsPositive: IntArray,
        realsNegative: IntArray,
        intVars: IntArray,
        intCoeffs: DoubleArray,
        realVars: IntArray,
        realCoeffs: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean,
    ) {
        require(op != LinearOp.NE) { "QF_LIRA disequality must be lowered before exact feasibility" }
        val columns = IntArray(2 * (intVars.size + realVars.size))
        val values = DoubleArray(columns.size)
        for (i in intVars.indices) {
            columns[2 * i] = intsPositive[intVars[i]]
            values[2 * i] = intCoeffs[i]
            columns[2 * i + 1] = intsNegative[intVars[i]]
            values[2 * i + 1] = -intCoeffs[i]
        }
        for (i in realVars.indices) {
            val offset = 2 * (intVars.size + i)
            columns[offset] = realsPositive[realVars[i]]
            values[offset] = realCoeffs[i]
            columns[offset + 1] = realsNegative[realVars[i]]
            values[offset + 1] = -realCoeffs[i]
        }
        builder.addRealRow(columns, values, relation(op), bound, strict)
    }

    // A wide row is added to the theory through its exact constants ([addWide]), never through these
    // double readings.
    private fun lowerDisequality(
        op: LinearOp,
        bound: Double,
        strict: Boolean,
        direction: LinearOp?,
        hasReals: Boolean,
    ): Triple<LinearOp, Double, Boolean> = if (op == LinearOp.NE) {
        when (requireNotNull(direction) { "integer disequality direction is missing" }) {
            LinearOp.LE -> if (hasReals) {
                Triple(LinearOp.LE, bound, true)
            } else {
                Triple(LinearOp.LE, bound - 1.0, false)
            }

            LinearOp.GE -> if (hasReals) {
                Triple(LinearOp.GE, bound, true)
            } else {
                Triple(LinearOp.GE, bound + 1.0, false)
            }

            else -> error("integer disequality direction must be an inequality")
        }
    } else {
        Triple(op, bound, strict)
    }

    private fun relation(op: LinearOp): Relation = when (op) {
        LinearOp.LE -> Relation.LE
        LinearOp.GE -> Relation.GE
        LinearOp.EQ -> Relation.EQ
        LinearOp.NE -> error("checked by add")
    }
}

private fun lowerWideDisequality(op: LinearOp, bound: BigInteger, direction: LinearOp?): Pair<LinearOp, BigInteger> =
    if (op == LinearOp.NE) {
        when (requireNotNull(direction) { "wide integer disequality direction is missing" }) {
            LinearOp.LE -> LinearOp.LE to (bound - BigInteger.ONE)
            LinearOp.GE -> LinearOp.GE to (bound + BigInteger.ONE)
            else -> error("wide integer disequality direction must be an inequality")
        }
    } else {
        op to bound
    }

private class QfLiraLeaf(
    val model: com.eignex.klause.lp.engine.LpModel,
    val integerPositive: IntArray,
    val integerNegative: IntArray,
    val realPositive: IntArray,
    val realNegative: IntArray,
) {
    fun value(witness: List<BigFraction>, positive: Int, negative: Int): BigFraction =
        witness[positive] - witness[negative]

    fun value(witness: List<BigFraction>, branch: IntegerLinearBranch): BigFraction =
        branch.variables.indices.fold(BigFraction.ZERO) { sum, index ->
            sum + BigFraction.of(branch.coefficients[index], BigInteger.ONE) *
                value(witness, integerPositive[branch.variables[index]], integerNegative[branch.variables[index]])
        }

    fun gmiCut(tableau: List<BigRationalTableauRow>?): ExactGmiCut? {
        if (model.rowStrict.any { it }) return null
        val integral = BooleanArray(model.n)
        for (column in integerPositive) integral[column] = true
        for (column in integerNegative) integral[column] = true
        for (row in tableau.orEmpty()) {
            if (row.basic !in 0 until model.n || !integral[row.basic]) continue
            val fraction = row.rhs.fractionalPart()
            if (fraction.isZero) continue
            val coefficients = ArrayList<BigFraction>()
            val columns = ArrayList<Int>()
            for (index in row.columns.indices) {
                val column = row.columns[index]
                val coefficient = if (column < model.n && integral[column]) {
                    row.coefficients[index].fractionalPart()
                } else {
                    row.coefficients[index].mixedIntegerCoefficient(fraction)
                }
                if (!coefficient.isZero) {
                    columns.add(column)
                    coefficients.add(coefficient)
                }
            }
            val projected = projectSlacks(columns, coefficients, fraction) ?: continue
            ExactGmiCut.from(projected)?.let { return it }
        }
        return null
    }

    private fun projectSlacks(columns: List<Int>, coefficients: List<BigFraction>, rhs: BigFraction): ExactCutRow? {
        val terms = HashMap<Int, BigFraction>()
        var cutRhs = rhs
        for (index in columns.indices) {
            val column = columns[index]
            val coefficient = coefficients[index]
            if (column < model.n) {
                terms.add(column, coefficient)
                continue
            }
            val row = column - model.n
            if (model.hasUpper[column] && model.upper[column] == 0L) continue
            cutRhs -= coefficient * requireNotNull(BigFraction.ofDouble(model.rhsD(row)))
            for (sourceColumn in 0 until model.n) {
                model.forEachInColumnD(sourceColumn) { sourceRow, value ->
                    if (sourceRow == row) {
                        terms.add(sourceColumn, coefficient * requireNotNull(BigFraction.ofDouble(value)).negated())
                    }
                }
            }
        }
        val ordered = terms.entries.filter { !it.value.isZero }.sortedBy { it.key }
        return ExactCutRow(
            ordered.map { it.key }.toIntArray(),
            ordered.map { it.value },
            cutRhs,
        )
    }
}

private class ExactGmiCut(val columns: IntArray, val coefficients: DoubleArray, val rhs: Double) {
    fun sameAs(other: ExactGmiCut): Boolean =
        columns.contentEquals(other.columns) && coefficients.contentEquals(other.coefficients) && rhs == other.rhs

    companion object {
        fun from(row: ExactCutRow): ExactGmiCut? {
            val rhs = row.rhs.exactDoubleOrNull() ?: return null
            val coefficients = DoubleArray(row.coefficients.size)
            for (index in coefficients.indices) {
                coefficients[index] = row.coefficients[index].exactDoubleOrNull() ?: return null
            }
            return ExactGmiCut(row.columns, coefficients, rhs)
        }
    }
}

private class ExactCutRow(val columns: IntArray, val coefficients: List<BigFraction>, val rhs: BigFraction)

private fun MutableMap<Int, BigFraction>.add(column: Int, value: BigFraction) {
    val sum = (this[column] ?: BigFraction.ZERO) + value
    if (sum.isZero) remove(column) else this[column] = sum
}

private fun BigFraction.fractionalPart(): BigFraction = this - BigFraction.of(floor(), BigInteger.ONE)

private fun BigFraction.mixedIntegerCoefficient(fraction: BigFraction): BigFraction = if (signum() >= 0) {
    this
} else {
    BigFraction.of(fraction.num, fraction.den - fraction.num) * negated()
}

private fun BigFraction.exactDoubleOrNull(): Double? {
    val value = toDouble()
    return value.takeIf { it.isFinite() && BigFraction.ofDouble(it) == this }
}

private class BigConstantEncoder(private val builder: LpBuilder, private val cancellation: Cancellation) {
    private val base = BigInteger.ONE shl 40
    private val columns = ArrayList<Int>()
    private val scaledColumns = HashMap<Int, ArrayList<Int>>()

    fun addBound(positive: Int, negative: Int, relation: Relation, bound: BigInteger): Boolean {
        val rowColumns = arrayListOf(positive, negative)
        val rowValues = arrayListOf(1.0, -1.0)
        if (!appendConstant(-bound, rowColumns, rowValues)) return false
        builder.addRealRow(rowColumns.toIntArray(), rowValues.toDoubleArray(), relation, 0.0)
        return true
    }

    fun appendProduct(
        coefficient: BigInteger,
        column: Int,
        targetColumns: MutableList<Int>,
        targetValues: MutableList<Double>,
        multiplier: Double,
    ): Boolean {
        val digits = digits(coefficient) ?: return false
        for ((digit, value) in digits.withIndex()) {
            if (cancellation()) return false
            if (value.isZero()) continue
            targetColumns.add(scaled(column, digit) ?: return false)
            targetValues.add(multiplier * value.longValue().toDouble())
        }
        return true
    }

    fun appendConstant(value: BigInteger, targetColumns: MutableList<Int>, targetValues: MutableList<Double>): Boolean {
        val digits = digits(value) ?: return false
        if (!ensureDigits(digits.size)) return false
        for ((digit, part) in digits.withIndex()) {
            if (cancellation()) return false
            if (part.isZero()) continue
            targetColumns.add(columns[digit])
            targetValues.add(part.longValue().toDouble())
        }
        return true
    }

    private fun ensureDigits(needed: Int): Boolean {
        while (columns.size < needed) {
            if (cancellation()) return false
            val column = builder.addOpenAboveVar(0L)
            if (columns.isEmpty()) {
                builder.addRealRow(intArrayOf(column), doubleArrayOf(1.0), Relation.EQ, 1.0)
            } else {
                builder.addRealRow(
                    intArrayOf(column, columns.last()),
                    doubleArrayOf(1.0, -(1L shl 40).toDouble()),
                    Relation.EQ,
                    0.0,
                )
            }
            columns.add(column)
        }
        return true
    }

    private fun digits(value: BigInteger): List<BigInteger>? {
        var remaining = value.abs()
        val sign = if (value < BigInteger.ZERO) -BigInteger.ONE else BigInteger.ONE
        val result = ArrayList<BigInteger>()
        do {
            if (cancellation()) return null
            result.add((remaining % base) * sign)
            remaining /= base
        } while (remaining != BigInteger.ZERO)
        return result
    }

    private fun scaled(column: Int, exponent: Int): Int? {
        val chain = scaledColumns.getOrPut(column) { arrayListOf(column) }
        while (chain.size <= exponent) {
            if (cancellation()) return null
            val next = builder.addOpenAboveVar(0L)
            builder.addRealRow(
                intArrayOf(next, chain.last()),
                doubleArrayOf(1.0, -(1L shl 40).toDouble()),
                Relation.EQ,
                0.0,
            )
            chain.add(next)
        }
        return chain[exponent]
    }
}

private fun BigFraction.isInteger(): Boolean = den == BigInteger.ONE

private fun BigFraction.floor(): BigInteger {
    val quotient = num / den
    return if (num < BigInteger.ZERO && num % den != BigInteger.ZERO) quotient - BigInteger.ONE else quotient
}

private fun IntBounds.lowerOrNull(variable: Int): Long? = if (hasLower(
        variable,
    )
) {
    lower(variable)
} else {
    null
}

private fun IntBounds.upperOrNull(variable: Int): Long? = if (hasUpper(
        variable,
    )
) {
    upper(variable)
} else {
    null
}
