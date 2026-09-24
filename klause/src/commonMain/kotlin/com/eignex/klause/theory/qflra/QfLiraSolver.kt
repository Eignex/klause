package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegralConstants
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
import com.eignex.klause.lp.SourceLp
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.admittedSourcePoint
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.closeSourceLpOwners
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactMixedEchelonHermite
import com.eignex.klause.lp.exactMixedTriangularBounds
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.lp.sourceDoubleBoundedSplit
import com.eignex.klause.lp.sourceEqualityRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.RegisteredTheoryDecision
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchBrancher
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
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

/** Incremental source arithmetic on the shared LP and search lifecycles. */
class ExactLiraSearchComponent(
    private val model: Problem,
    private val modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
) : TheoryComponent,
    SearchBrancher,
    AutoCloseable {
    private var smtStats: SmtStatsSink? = null
    private val bools = IntArray(model.numBoolVars) { UNASSIGNED }
    private val boolLevels = IntArray(model.numBoolVars) { -1 }
    private val root = SearchNode()
    private val reduction = ExactLiraReductionCache(model, { solveContext }) { smtStats?.observeSourceLp(it) }
    private val nodesByLevel = MutableIntObjectMap<SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null
    private var candidate: List<BigFraction>? = null
    private var dirty = true
    private var solveContext = LpSolveContext.Production
    private val arithmeticRows = model.factors.flatMap { it.linearRows }.filter { row ->
        (0 until row.size).any { !Term.isBool(row.ref(it)) }
    }
    private val arithmeticVariables = arithmeticRows.flatMap { it.booleanVariables() }.toSet()
    private val branchNames = HashMap<SourceBoundAtom, SearchDecision>()
    private var context: SearchContext? = null
    private val lpDelegate: Lazy<LpPropagator> = lazy {
        LpPropagator(
            object : LpSearchPolicy {
                override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                    accept(decision, context)
                override fun propagate(context: SearchContext): ComponentResult = relax(context)
                override fun check(context: SearchContext): ComponentCheck = outcome ?: ComponentCheck.Indeterminate
                override fun nextBranch(context: SearchContext): List<SearchDecision>? = branch(context)
                override fun retract(decisionLevel: Int) = retractSource(decisionLevel)
            },
            solveContext = solveContext,
            cancellation = Cancellation { context?.cancelled() == true },
            certificationObserver = object : LpCertificationObserver {
                override fun observe(certifier: LpCertifier, success: Boolean) = Unit
                override fun observeExactInput(accepted: Boolean) = Unit
                override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
                override fun observeContinuation(metrics: ExactContinuationMetrics) {
                    smtStats?.observeContinuation(metrics)
                }
            },
        )
    }
    private val lp: LpPropagator by lpDelegate
    private val system by lazy { LiveQfLraSystem(model, lp) }

    internal fun solveWith(context: LpSolveContext) {
        check(this.context == null)
        solveContext = context
    }

    internal val lpMetrics get() = lp.metrics

    internal fun observeWith(stats: SmtStatsSink) {
        smtStats = stats
    }

    init {
        require(model.supportsExactLira() || model.supportsExactLra()) { "unsupported exact linear component" }
        nodesByLevel.put(0, root)
    }

    override fun initialize(context: SearchContext): ComponentResult {
        check(this.context == null) { "a theory component belongs to one immutable source session" }
        this.context = context
        if (!system.install()) return ComponentResult.Indeterminate
        return lp.initialize(context)
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult = lp.assert(
        decision,
        context,
    )
    override fun propagate(context: SearchContext): ComponentResult = lp.propagate(context)
    override fun check(context: SearchContext): ComponentCheck = lp.check(context)
    override fun nextBranch(context: SearchContext): List<SearchDecision>? = lp.nextBranch(context)
    override fun retract(decisionLevel: Int) = lp.retract(decisionLevel)
    override fun onRestart(context: SearchContext) = lp.onRestart(context)
    override fun close() {
        closeSourceLpOwners(listOfNotNull(if (lpDelegate.isInitialized()) lp else null, reduction))
    }

    private fun accept(decision: SearchDecision, context: SearchContext): ComponentResult {
        if (decision is SearchDecision.Bool && decision.literal ushr 1 !in bools.indices) {
            return ComponentResult.Consistent
        }
        assignment = null
        outcome = null
        candidate = null
        val wasDirty = dirty
        dirty = true
        when (decision) {
            is SearchDecision.Bool -> {
                val variable = decision.literal ushr 1
                bools[variable] = if (decision.literal and 1 == 0) TRUE else FALSE
                boolLevels[variable] = context.decisionLevel
                node = node.copy(retainedReduction = null)
                if (variable !in arithmeticVariables && bools.any { it == UNASSIGNED }) {
                    nodesByLevel.put(context.decisionLevel, node)
                    dirty = wasDirty
                    return ComponentResult.Consistent
                }
            }

            is SearchDecision.Theory -> when (val payload = decision.decision) {
                is RegisteredTheoryDecision -> {
                    val atom = payload.payload as? SourceBoundAtom ?: return ComponentResult.Consistent
                    if (context.atomLiteral(decision) == null) return ComponentResult.Indeterminate
                    if (system.sourceTerms(atom) == null) return ComponentResult.Indeterminate
                    branchNames[atom] = decision
                    val branchRows = atom.sourceRows(model.numRealVars)
                    val retained = node.retainedReduction?.takeIf { reduced ->
                        reduced.budget.run(
                            branchRows,
                            model.numRealVars + model.numIntVars,
                            Cancellation(context::cancelled),
                        ) {
                            branchRows.all { row ->
                                reduced.system.transform(row).columns.all(reduced.system::boundedColumn)
                            }
                        } == true
                    }
                    node = node.copy(sourceBranches = node.sourceBranches + atom, retainedReduction = retained)
                    if (!system.assertAtom(atom, SearchAtomPremise.Asserted(decision))) {
                        return ComponentResult.Indeterminate
                    }
                }

                is ExactLiraDecision -> {
                    node = if (payload.direction == null) {
                        node.withComparison(payload.address, payload.option)
                    } else {
                        node.withDirection(payload.address, payload.direction)
                    }
                    node = node.copy(retainedReduction = null)
                }
            }

            is SearchDecision.IntAtMost, is SearchDecision.IntAtLeast, is SearchDecision.IntEqual -> {
                node = node.withPublishedBounds(model.numIntVars, context::intLowerBound, context::intUpperBound)
                    .copy(retainedReduction = null)
            }
        }
        nodesByLevel.put(context.decisionLevel, node)
        return ComponentResult.Consistent
    }

    private fun assertSource(context: SearchContext): Boolean {
        for ((factorIndex, factor) in model.factors.withIndex()) {
            val selected = if (factor.linearForm is LinearForm.Disjunction) {
                node.comparisonChoices[RowAddress(factorIndex, 0)] ?: continue
            } else {
                null
            }
            for ((index, row) in factor.linearRows.withIndex()) {
                if (selected != null && selected != index) continue
                val truth = row.truthUnder(bools) ?: continue
                val comparison = row.exactComparison(model.numRealVars, truth) { bools[it] == TRUE }
                val direction = node.disequalityDirections[RowAddress(factorIndex, index)]
                if (comparison.op == LinearOp.NE && direction == null) continue
                val rows = ArrayList<ExactRationalInequality>()
                comparison.rowsInto(rows, direction)
                val leaves = row.booleanVariables().map { variable ->
                    SearchAtomPremise.Asserted(SearchDecision.Bool(Lit.make(variable, bools[variable] == TRUE)))
                }.toMutableList<SearchAtomPremise>()
                if (selected != null || direction != null) leaves += SearchAtomPremise.Unavailable
                val premise = SearchAtomPremise.All(leaves)
                for (inequality in rows) if (!system.assertRow(inequality, premise)) return false
            }
        }
        for (atom in node.sourceBranches) {
            val premise = branchNames[atom]?.let(SearchAtomPremise::Asserted) ?: SearchAtomPremise.Unavailable
            if (!system.assertAtom(atom, premise)) return false
        }
        for (integer in 0 until model.numIntVars) {
            context.intLowerBound(integer)?.let { value ->
                if (!system.assertRow(
                        exactColumnLower(model.numRealVars + integer, BigFraction.ofLong(value)),
                        SearchAtomPremise.Unavailable,
                    )
                ) {
                    return false
                }
            }
            context.intUpperBound(integer)?.let { value ->
                if (!system.assertRow(
                        exactColumnUpper(model.numRealVars + integer, BigFraction.ofLong(value)),
                        SearchAtomPremise.Unavailable,
                    )
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun relax(context: SearchContext): ComponentResult {
        if (!dirty) return ComponentResult.Consistent
        if (bools.any { it == UNASSIGNED } && arithmeticRows.none {
                it.truthUnder(bools) != null
            }
        ) {
            return ComponentResult.Consistent
        }
        val asserted = assertSource(context)
        if (!asserted || context.cancelled()) {
            return ComponentResult.Indeterminate
        }
        if (!context.consumeCheck()) return ComponentResult.Indeterminate
        val result = lp.solve() ?: return ComponentResult.Indeterminate
        if (context.cancelled()) return ComponentResult.Indeterminate
        dirty = false
        candidate = result.exactPrimal?.take(model.numRealVars + model.numIntVars)
        if (result.verdict == LpVerdict.INFEASIBLE) {
            val explanation = lp.explainConflict(result.conflictSupport, context)
            smtStats?.observeConflict(explanation)
            outcome = ComponentCheck.Infeasible(explanation)
            return ComponentResult.Conflict(explanation)
        }
        val complete = bools.none { it == UNASSIGNED } && node.nextComparison(model) == null &&
            node.nextDisequality(model, bools) == null
        if (complete) {
            result.exactPrimal?.take(model.numRealVars + model.numIntVars)?.let { point ->
                acceptWitness(point)?.let {
                    assignment = it
                    outcome = ComponentCheck.Feasible
                }
            }
        }
        // Source integer branching requires an independently checked continuous witness.
        return ComponentResult.Consistent
    }

    private fun branch(context: SearchContext): List<SearchDecision>? {
        if (bools.any { it == UNASSIGNED } || outcome != null) return null
        if (context.cancelled() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        node.nextComparison(model)?.let { comparison ->
            return model.factors[comparison.factor].linearRows.indices.map {
                SearchDecision.Theory(ExactLiraDecision(comparison, option = it))
            }
        }
        node.nextDisequality(model, bools)?.let { address ->
            return listOf(LinearOp.GE, LinearOp.LE).map {
                SearchDecision.Theory(ExactLiraDecision(address, direction = it))
            }
        }
        if (candidate == null) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        val reduced = node.retainedReduction ?: reduction.reduce(
            bools,
            node.withPublishedBounds(model.numIntVars, context::intLowerBound, context::intUpperBound),
            Cancellation(context::cancelled),
            smtStats,
        )
        if (reduced == ExactLiraReduction.Infeasible) {
            outcome = ComponentCheck.Infeasible()
            smtStats?.observeConflict(null)
            return null
        }
        if (reduced !is ExactLiraReduction.Bounded) {
            if (!context.cancelled() && (context as? SearchSession)?.canCommitOpenTheoryDecision() != false) {
                sourceIntegerSplit(requireNotNull(candidate), context)?.let { return it }
            }
            outcome = ComponentCheck.Indeterminate
            return null
        }
        node = node.copy(retainedReduction = reduced)
        nodesByLevel.put(context.decisionLevel, node)
        candidate?.let { point ->
            for (integer in 0 until reduced.system.integerColumns) {
                if (!reduced.system.boundedColumn(reduced.system.realColumns + integer)) continue
                val coefficients = reduced.system.transformedIntegerCoefficients(integer)
                var value = BigFraction.ZERO
                for (entry in coefficients.index.indices) {
                    value +=
                        coefficients.value[entry].asFraction() * point[model.numRealVars + coefficients.index[entry]]
                }
                if (!value.isInteger()) return registeredSplit(reduced, integer, value, context)
            }
        }
        val extension = (0 until reduced.system.realColumns + reduced.system.integerColumns)
            .any { !reduced.system.boundedColumn(it) }
        if (!extension) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        when (val bounded = reduced.solver.solve(node, Cancellation(context::cancelled))) {
            is ExactReducedSearchResult.Split -> {
                return registeredSplit(reduced, bounded.integer, bounded.floor.asFraction(), context)
            }

            ExactReducedSearchResult.Infeasible -> {
                outcome = ComponentCheck.Infeasible()
                smtStats?.observeConflict(null)
            }

            ExactReducedSearchResult.Interrupted -> outcome = ComponentCheck.Indeterminate

            is ExactReducedSearchResult.Found -> {
                assignment = acceptWitness(bounded.sourceValues)
                outcome = if (assignment != null && !context.cancelled()) {
                    ComponentCheck.Feasible
                } else {
                    ComponentCheck.Indeterminate
                }
            }
        }
        return null
    }

    private fun registeredSplit(
        reduced: ExactLiraReduction.Bounded,
        integer: Int,
        value: BigFraction,
        context: SearchContext,
    ): List<SearchDecision>? {
        val coefficients = reduced.system.transformedIntegerCoefficients(integer)
        val terms = coefficients.index.indices.map {
            SourceBoundTerm(SearchIntValue(coefficients.index[it]), coefficients.value[it].asFraction())
        }
        val split = SourceBoundAtom.integerSplit(context, terms, value)
        if (split == null) outcome = ComponentCheck.Indeterminate
        return split?.alternatives()
    }

    private fun sourceIntegerSplit(point: List<BigFraction>, context: SearchContext): List<SearchDecision>? {
        if (point.size != model.numRealVars + model.numIntVars) return null
        for (integer in 0 until model.numIntVars) {
            val value = point[model.numRealVars + integer]
            if (value.isInteger()) continue
            return SourceBoundAtom.integerSplit(
                context,
                listOf(SourceBoundTerm(SearchIntValue(integer), BigFraction.ONE)),
                value,
            )?.alternatives()
        }
        return null
    }

    private fun acceptWitness(point: List<BigFraction>): ExactLiraAssignment? {
        if (point.size != model.numRealVars + model.numIntVars ||
            (0 until model.numIntVars).any { !point[model.numRealVars + it].isInteger() }
        ) {
            return null
        }
        val rows = reduction.sourceRows(bools, node, Cancellation { context?.cancelled() == true }) ?: return null
        if (!point.satisfiesSourceRows(rows, Cancellation { context?.cancelled() == true })) return null
        val strict = rows.any { it.strict }
        val wide = rows.hasWideIntegerData() || point.any { it.num.abs() > WIDE_INTEGER_LIMIT }
        smtStats?.observeWitnessCandidate(strict, wide)
        return ExactLiraAssignment(
            bools.toCompleteValues(),
            Array(model.numIntVars) { point[model.numRealVars + it].num },
            point.take(model.numRealVars),
        ).also {
            smtStats?.observeWitnessAccepted(strict, wide)
        }
    }

    private fun retractSource(decisionLevel: Int) {
        for (variable in bools.indices) {
            if (boolLevels[variable] > decisionLevel) {
                bools[variable] = UNASSIGNED
                boolLevels[variable] = -1
            }
        }
        nodesByLevel.removeKeysAbove(decisionLevel)
        node = nodesByLevel.valueAtMaxKey() ?: root
        assignment = null
        outcome = null
        candidate = null
        dirty = true
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        if (outcome != ComponentCheck.Feasible || context.cancelled()) return
        assignment?.let { value ->
            model.put(this, if (this.model.numIntVars == 0) ExactLraAssignment(value.bools, value.reals) else value)
            modelContribution?.invoke(value, model)
        }
    }
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
private sealed interface ExactLiraReduction {
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
private class ExactLiraReductionCache(
    private val model: Problem,
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

    fun reduce(
        bools: IntArray,
        node: SearchNode,
        cancellation: Cancellation,
        observer: SmtStatsSink? = null,
    ): ExactLiraReduction {
        return budget.run(emptyList(), model.numRealVars + model.numIntVars, cancellation) { token ->
            if (!admitsSourcePreparation(node)) return@run ExactLiraReduction.Interrupted
            reduceAdmitted(bools, node, token, observer)
        } ?: ExactLiraReduction.Interrupted
    }

    private fun admitsSourcePreparation(node: SearchNode): Boolean {
        if (model.numBoolVars > 512 || model.factors.size > 128 || node.branches.size > 128 ||
            node.sourceBranches.size > 128 || node.sourceBranches.sumOf { it.terms.size.toLong() } > 512L
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
                        constants.exactBound.bitLength() > 4096 ||
                            (0 until row.size).any { constants.exactCoeff(it).bitLength() > 4096 }
                        )
                ) {
                    return false
                }
            }
        }
        for (integer in 0 until model.numIntVars) {
            if (model.intBounds.lowerAsBigInteger(integer)?.bitLength()?.let { it > 4096 } == true ||
                model.intBounds.upperAsBigInteger(integer)?.bitLength()?.let { it > 4096 } == true
            ) {
                return false
            }
        }
        if (node.branches.any { branch ->
                branch.variable !in 0 until model.numIntVars ||
                    branch.lower?.bitLength()?.let { it > 4096 } == true ||
                    branch.upper?.bitLength()?.let { it > 4096 } == true
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
        cancellation: Cancellation,
        observer: SmtStatsSink?,
    ): ExactLiraReduction {
        val mark = observer?.beginReduction()
        val key = ExactLiraReductionKey(
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
        cancellation: Cancellation,
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

    fun sourceRows(bools: IntArray, node: SearchNode, cancellation: Cancellation): List<ExactRationalInequality>? {
        val rows = ArrayList<ExactRationalInequality>()
        val published = node.branches.associateBy { it.variable }
        for (integer in 0 until model.numIntVars) {
            if (cancellation()) return null
            val column = model.numRealVars + integer
            val branch = published[integer]
            listOfNotNull(model.intBounds.lowerAsBigInteger(integer), branch?.lower).maxOrNull()?.let {
                rows += exactColumnLower(column, it.asFraction())
            }
            listOfNotNull(model.intBounds.upperAsBigInteger(integer), branch?.upper).minOrNull()?.let {
                rows += exactColumnUpper(column, it.asFraction())
            }
        }
        for (real in 0 until model.numRealVars) {
            if (cancellation()) return null
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        val complete = node.forEachSelectedRow(model) { factor, index, row ->
            if (cancellation()) return null
            val truth = row.truthUnder(bools) ?: return@forEachSelectedRow
            val comparison = row.exactComparison(model.numRealVars, truth) { bools[it] == TRUE }
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
    val comparisons: List<Pair<RowAddress, Int>>,
    val directions: List<Pair<RowAddress, LinearOp>>,
    val sourceBranches: List<SourceBoundAtom>,
)

/**
 * The bounded phase of Double-Bounded Reduction in mixed-echelon/Hermite coordinates.
 *
 * Only nonzero integer columns of the transformed double-bounded system are branched.  Lemma 8
 * makes each of those coordinates finite; zero columns are deliberately absent from this search and
 * are filled by [ExactLiraReduction.Bounded.extend] after the bounded witness is found.
 */
private class ExactReducedLiraSystem(private val reduction: ExactLiraReduction.Bounded) : AutoCloseable {
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
        return rows
    }

    private val owner = SourceLp(model(root(SearchNode())), columns, reduction.budget)

    override fun close() = owner.close()

    fun fractionalInteger(values: List<BigFraction>): Int? = (0 until integerColumns).firstOrNull { integer ->
        reduction.system.boundedColumn(realColumns + integer) && !values[realColumns + integer].isInteger()
    }

    fun solve(node: SearchNode, cancellation: Cancellation): ExactReducedSearchResult {
        return reduction.budget.run(reduction.sourceRows, columns, cancellation) { token ->
            if (node.sourceBranches.size > 128 || node.sourceBranches.sumOf { it.terms.size.toLong() } > 512L ||
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
                return@run ExactReducedSearchResult.Split(
                    rooted,
                    integer,
                    values[realColumns + integer].floor(),
                )
            }
            reduction.extend(values, token)?.takeIf { source ->
                source.satisfiesSourceRows(node.sourceBranches.flatMap { it.sourceRows(realColumns) }, token)
            }?.let(ExactReducedSearchResult::Found)
                ?: ExactReducedSearchResult.Interrupted
        } ?: ExactReducedSearchResult.Interrupted
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
        val half = BigFraction.of(BigInteger.ONE, BigInteger.TWO)
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
            if (index < realFree.size) value else (value + half).floor().asFraction()
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

// Source integer values beyond the largest exactly representable double are a useful diagnostic for
// model conversions that would otherwise silently lose an integer unit.
private val WIDE_INTEGER_LIMIT = BigInteger.fromLong(1L shl 53)

private fun List<ExactRationalInequality>.hasWideIntegerData(): Boolean = any { row ->
    (row.rhs.den == BigInteger.ONE && row.rhs.num.abs() > WIDE_INTEGER_LIMIT) ||
        row.coefficients.any { coefficient ->
            coefficient.den == BigInteger.ONE && coefficient.num.abs() > WIDE_INTEGER_LIMIT
        }
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

private data class RowAddress(val factor: Int, val row: Int) : Comparable<RowAddress> {
    override fun compareTo(other: RowAddress): Int = compareValuesBy(this, other, RowAddress::factor, RowAddress::row)
}

private data class SearchNode(
    val branches: List<IntegerBranch> = emptyList(),
    val sourceBranches: List<SourceBoundAtom> = emptyList(),
    val retainedReduction: ExactLiraReduction.Bounded? = null,
    val reducedBranches: List<IntegerBranch> = emptyList(),
    val transformedBranches: List<IntegerLinearBranch> = emptyList(),
    val comparisonChoices: Map<RowAddress, Int> = emptyMap(),
    val disequalityDirections: Map<RowAddress, LinearOp> = emptyMap(),
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

    fun withComparison(factor: RowAddress, literal: Int): SearchNode =
        copy(comparisonChoices = comparisonChoices + (factor to literal))

    fun withDirection(factor: RowAddress, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun nextComparison(model: Problem): RowAddress? = model.factors.indices.firstOrNull { index ->
        model.factors[index].linearForm is LinearForm.Disjunction &&
            RowAddress(index, 0) !in comparisonChoices
    }?.let { RowAddress(it, 0) }

    inline fun forEachSelectedRow(model: Problem, action: (Int, Int, LinearRow) -> Unit): Boolean {
        for ((index, factor) in model.factors.withIndex()) {
            val rows = factor.linearRows
            if (factor.linearForm is LinearForm.Disjunction) {
                val selected = comparisonChoices[RowAddress(index, 0)] ?: return false
                action(index, selected, rows[selected])
            } else {
                for (rowIndex in rows.indices) action(index, rowIndex, rows[rowIndex])
            }
        }
        return true
    }

    fun nextDisequality(model: Problem, bools: IntArray): RowAddress? {
        forEachSelectedRow(model) { factor, index, row ->
            val truth = row.truthUnder(bools) ?: return@forEachSelectedRow
            if ((if (truth) row.relation else row.relation.complemented()) != LinearOp.NE) return@forEachSelectedRow
            val address = RowAddress(factor, index)
            if (address !in disequalityDirections) return address
        }
        return null
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

private data class ExactLiraDecision(val address: RowAddress, val option: Int = 0, val direction: LinearOp? = null) :
    SearchTheoryDecision

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
