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
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.lp.exactForm
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
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.rem

/** An exact integer/rational witness for an open QF_LIRA or QF_LIA model. */
data class ExactLiraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Arbitrary-precision integer values indexed by model integer variable id. */
    val ints: Array<BigInt>,
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
    private val disjunctionAtoms = HashMap<Int, List<DisjunctAtom>>()
    private val exactForms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(model.numRealVars) } }
    private var impliedDisjunct = false
    private val reduction = ExactLiraReductionCache(model, disjunctionAtoms, { solveContext }) {
        smtStats?.observeSourceLp(it)
    }
    private val nodesByLevel = MutableIntObjectMap<SearchNode>()
    private var node = root
    private var assignment: ExactLiraAssignment? = null
    private var outcome: ComponentCheck? = null
    private var candidate: List<BigFraction>? = null
    private var dirty = true
    private var solveContext = LpSolveContext.Production
    private var solveStop: Cancellation? = null
    private var operationAllowance: (Cancellation) -> Cancellation = { it.shorten(0.5) }
    private var operationStop = Cancellation.Never
    internal var operationBudgetExhausted = false
        private set
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
            cancellation = cancelledWhen({ operationStop.deadline() }) {
                operationStop() || context?.cancelled() == true
            },
            certificationObserver = object : LpCertificationObserver {
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
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

    internal fun useSolveStop(stop: Cancellation?) {
        check(context == null)
        solveStop = stop
    }

    internal fun useOperationAllowance(allowance: (Cancellation) -> Cancellation) {
        check(context == null)
        operationAllowance = allowance
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
        registerDisjunctions(context)
        beginOperation(context)
        return try {
            if (!system.install() || operationStop()) {
                ComponentResult.Indeterminate
            } else {
                lp.initialize(context).takeUnless { operationStop() } ?: ComponentResult.Indeterminate
            }
        } finally {
            endOperation(context)
        }
    }

    override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult {
        beginOperation(context)
        return try {
            if (operationStop()) {
                ComponentResult.Indeterminate
            } else {
                val result = lp.assertWithin(decision, context, operationStop)
                if (operationStop()) ComponentResult.Indeterminate else result
            }
        } finally {
            endOperation(context)
        }
    }
    override fun propagate(context: SearchContext): ComponentResult = lp.propagate(context)
    override fun check(context: SearchContext): ComponentCheck = lp.check(context)
    override fun nextBranch(context: SearchContext): List<SearchDecision>? {
        beginOperation(context)
        return try {
            if (operationStop()) {
                outcome = ComponentCheck.Indeterminate
                null
            } else {
                val branches = lp.nextBranch(context)
                if (operationStop()) {
                    outcome = ComponentCheck.Indeterminate
                    null
                } else {
                    branches
                }
            }
        } finally {
            endOperation(context)
        }
    }
    override fun retract(decisionLevel: Int) = lp.retract(decisionLevel)
    override fun onRestart(context: SearchContext) = lp.onRestart(context)
    override fun close() {
        closeSourceLpOwners(listOfNotNull(if (lpDelegate.isInitialized()) lp else null, reduction))
    }

    private fun registerDisjunctions(context: SearchContext) {
        for ((index, factor) in model.factors.withIndex()) {
            if (factor.linearForm !is LinearForm.Disjunction || factor.linearRows.isEmpty()) continue
            // A factor with any row that cannot be named keeps the private choice for all of its rows, so none of
            // its rows is registered: a stray atom would be a Boolean no factor reads.
            if (!factor.linearRows.all(::nameableDisjunct)) continue
            val disjuncts = ArrayList<DisjunctAtom>(factor.linearRows.size)
            for (row in factor.linearRows) disjuncts += registerDisjunct(row, context) ?: break
            if (disjuncts.size == factor.linearRows.size) disjunctionAtoms[index] = disjuncts
        }
    }

    private fun nameableDisjunct(row: LinearRow): Boolean {
        if (row.booleanVariables().isNotEmpty()) return false
        val comparison = row.exactComparison(model.numRealVars, truth = true) { false }
        return comparison.terms.isNotEmpty() && (comparison.op == LinearOp.LE || comparison.op == LinearOp.GE)
    }

    private fun registerDisjunct(row: LinearRow, context: SearchContext): DisjunctAtom? {
        if (!nameableDisjunct(row)) return null
        val comparison = row.exactComparison(model.numRealVars, truth = true) { false }
        val terms = comparison.terms.map { (column, coefficient) ->
            val source = if (column < model.numRealVars) {
                SearchRealValue(column)
            } else {
                SearchIntValue(column - model.numRealVars)
            }
            SourceBoundTerm(source, coefficient)
        }
        // An integral activity has no value strictly between consecutive integers, so the row and its
        // complement are both non-strict and share their names with source integer branching.
        val integral = !comparison.strict && comparison.terms.all { (column, coefficient) ->
            column >= model.numRealVars && coefficient.den == BIG_ONE
        }
        return when (comparison.op) {
            LinearOp.LE -> {
                val split = if (integral) {
                    SourceBoundAtom.integerSplit(context, terms, comparison.bound)
                } else {
                    SourceBoundAtom.rationalSplit(context, terms, comparison.bound, comparison.strict)
                }
                split?.let { DisjunctAtom(it.positive, it.negative) }
            }

            LinearOp.GE -> {
                val split = if (integral) {
                    val ceiling = comparison.bound.negated().floor().negate()
                    SourceBoundAtom.integerSplit(context, terms, (ceiling - BIG_ONE).asFraction())
                } else {
                    SourceBoundAtom.rationalSplit(context, terms, comparison.bound, !comparison.strict)
                }
                split?.let { DisjunctAtom(it.negative, it.positive) }
            }

            // An equality is two bounds and a disequality a union of two, so neither is one exact pair.
            LinearOp.EQ, LinearOp.NE -> null
        }
    }

    // The shared engine sees each disjunct only as an atom, so the disjunction itself is held here; a
    // learned clause carrying it could be forgotten by a database reduction. Reports whether it implied a disjunct,
    // whose row reaches this component only when the engine delivers the implication.
    private fun enforceDisjunctions(context: SearchContext): ComponentResult {
        impliedDisjunct = false
        for (disjuncts in disjunctionAtoms.values) {
            if (disjuncts.any { context.truth(it.holds) == true }) continue
            val undecided = disjuncts.filter { context.truth(it.holds) == null }
            if (undecided.size > 1) continue
            val open = undecided.singleOrNull()
            val reason = disjunctionReason(disjuncts, open, context) ?: return ComponentResult.Indeterminate
            if (open == null) {
                smtStats?.observeConflict(reason)
                outcome = ComponentCheck.Infeasible(reason)
                return ComponentResult.Conflict(reason)
            }
            val implied = context.imply(open.holds.literal, reason)
            if (implied !is ComponentResult.Consistent) return implied
            impliedDisjunct = true
        }
        return ComponentResult.Consistent
    }

    private fun disjunctionReason(
        disjuncts: List<DisjunctAtom>,
        open: DisjunctAtom?,
        context: SearchContext,
    ): SearchExplanation? = context.explainAtoms(
        SearchAtomPremise.All(
            disjuncts.filter { it !== open }.map { SearchAtomPremise.Asserted(SearchDecision.Theory(it.fails)) },
        ),
        open?.let { SearchDecision.Theory(it.holds) },
    )

    private fun disjunctionBranch(context: SearchContext): List<SearchDecision>? {
        for ((index, factor) in model.factors.withIndex()) {
            if (factor.linearForm !is LinearForm.Disjunction) continue
            val disjuncts = disjunctionAtoms[index]
            if (disjuncts == null) {
                val address = RowAddress(index, 0)
                if (address in node.comparisonChoices) continue
                return factor.linearRows.indices.map { SearchDecision.Theory(ExactLiraDecision(address, option = it)) }
            }
            if (disjuncts.any { context.truth(it.holds) == true }) continue
            val open = disjuncts.firstOrNull { context.truth(it.holds) == null }
            if (open != null) return listOf(SearchDecision.Theory(open.holds), SearchDecision.Theory(open.fails))
            val reason = disjunctionReason(disjuncts, null, context)
            outcome = if (reason == null) {
                ComponentCheck.Indeterminate
            } else {
                smtStats?.observeConflict(reason)
                ComponentCheck.Infeasible(reason)
            }
            return null
        }
        return null
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
                            operationStop,
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
                val comparison = exactForms[factorIndex][index].comparison(truth) { bools[it] == TRUE }
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
        beginOperation(context)
        return try {
            relaxWithin(context)
        } finally {
            endOperation(context)
        }
    }

    private fun beginOperation(context: SearchContext) {
        operationBudgetExhausted = false
        val parent = solveStop ?: (context as? SearchSession)?.stopToken() ?: Cancellation(context::cancelled)
        operationStop = operationAllowance(parent)
    }

    private fun endOperation(context: SearchContext) {
        if (operationStop() && !context.cancelled()) operationBudgetExhausted = true
        operationStop = Cancellation.Never
    }

    private fun relaxWithin(context: SearchContext): ComponentResult {
        if (operationStop()) return ComponentResult.Indeterminate
        if (!dirty) return ComponentResult.Consistent
        val enforced = enforceDisjunctions(context)
        if (enforced !is ComponentResult.Consistent) return enforced
        // The implied row is not asserted yet; solving now would only be repeated once it is delivered.
        if (impliedDisjunct) return ComponentResult.Consistent
        if (bools.any { it == UNASSIGNED } && arithmeticRows.none {
                it.truthUnder(bools) != null
            }
        ) {
            return ComponentResult.Consistent
        }
        val asserted = assertSource(context)
        if (!asserted || operationStop()) {
            return ComponentResult.Indeterminate
        }
        if (!context.consumeCheck()) return ComponentResult.Indeterminate
        val result = lp.solve(token = operationStop, sparsePointRecovery = true)
            ?: return ComponentResult.Indeterminate
        if (operationStop()) return ComponentResult.Indeterminate
        dirty = false
        candidate = result.exactPrimal?.take(model.numRealVars + model.numIntVars)
        if (result.verdict == LpVerdict.INFEASIBLE) {
            val explanation = lp.explainConflict(result.conflictSupport, context)
            smtStats?.observeConflict(explanation)
            outcome = ComponentCheck.Infeasible(explanation)
            return ComponentResult.Conflict(explanation)
        }
        val complete = bools.none { it == UNASSIGNED } && node.selectsEveryDisjunction(model, disjunctionAtoms) &&
            node.nextDisequality(model, disjunctionAtoms, bools) == null
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
        if (operationStop() || !context.consumeCheck()) {
            outcome = ComponentCheck.Indeterminate
            return null
        }
        disjunctionBranch(context)?.let { return it }
        if (outcome != null) return null
        node.nextDisequality(model, disjunctionAtoms, bools)?.let { address ->
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
            operationStop,
            smtStats,
        )
        if (reduced == ExactLiraReduction.Infeasible) {
            outcome = ComponentCheck.Infeasible()
            smtStats?.observeConflict(null)
            return null
        }
        if (reduced !is ExactLiraReduction.Bounded) {
            if (!operationStop() && (context as? SearchSession)?.canCommitOpenTheoryDecision() != false) {
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
        when (val bounded = reduced.solver.solve(node, node.searchedBranches(disjunctionAtoms), operationStop)) {
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
                outcome = if (assignment != null && !operationStop()) {
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
        val token = cancelledWhen({ operationStop.deadline() }) { operationStop() || context?.cancelled() == true }
        val rows = reduction.sourceRows(bools, node, token) ?: return null
        if (!point.satisfiesSourceRows(rows, token)) return null
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
        cancellation: Cancellation,
        observer: SmtStatsSink?,
    ): ExactLiraReduction {
        val mark = observer?.beginReduction()
        val key = ExactLiraReductionKey(
            bools.toList(),
            node.branches.sortedBy { it.variable },
            node.comparisonChoices.entries.sortedBy { it.key }.map { it.key to it.value },
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
    val sourceBranches: Set<SourceBoundAtom>,
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

    fun solve(node: SearchNode, searched: List<SourceBoundAtom>, cancellation: Cancellation): ExactReducedSearchResult {
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
    data class Split(val node: SearchNode, val integer: Int, val floor: BigInt) : ExactReducedSearchResult
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
private val WIDE_INTEGER_LIMIT = bigIntOf(1L shl 53)

private fun List<ExactRationalInequality>.hasWideIntegerData(): Boolean = any { row ->
    (row.rhs.den == BIG_ONE && row.rhs.num.abs() > WIDE_INTEGER_LIMIT) ||
        row.coefficients.any { coefficient ->
            coefficient.den == BIG_ONE && coefficient.num.abs() > WIDE_INTEGER_LIMIT
        }
}

private data class IntegerBranch(val variable: Int, val lower: BigInt? = null, val upper: BigInt? = null)

private data class IntegerLinearBranch(
    val variables: IntArray,
    val coefficients: Array<BigInt>,
    val lower: BigInt? = null,
    val upper: BigInt? = null,
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
            lower = maxOfNullable(branches[existing].lower, branch.lower),
            upper = minOfNullable(branches[existing].upper, branch.upper),
        )
        return copy(branches = branches.toMutableList().also { it[existing] = merged })
    }

    fun withReducedBranch(branch: IntegerBranch): SearchNode {
        val existing = reducedBranches.indexOfFirst { it.variable == branch.variable }
        if (existing < 0) return copy(reducedBranches = reducedBranches + branch)
        val merged = reducedBranches[existing].copy(
            lower = maxOfNullable(reducedBranches[existing].lower, branch.lower),
            upper = minOfNullable(reducedBranches[existing].upper, branch.upper),
        )
        return copy(reducedBranches = reducedBranches.toMutableList().also { it[existing] = merged })
    }

    fun withTransformedBranch(branch: IntegerLinearBranch): SearchNode =
        copy(transformedBranches = transformedBranches + branch)

    fun withTransformedSplit(branch: IntegerLinearBranch, lower: BigInt? = null, upper: BigInt? = null): SearchNode =
        copy(
            transformedBranches = transformedBranches.map { existing ->
                if (!existing.sameShape(branch)) {
                    existing
                } else {
                    existing.copy(
                        lower = maxOfNullable(existing.lower, lower),
                        upper = minOfNullable(existing.upper, upper),
                    )
                }
            },
        )

    fun withComparison(factor: RowAddress, literal: Int): SearchNode =
        copy(comparisonChoices = comparisonChoices + (factor to literal))

    fun withDirection(factor: RowAddress, direction: LinearOp): SearchNode =
        copy(disequalityDirections = disequalityDirections + (factor to direction))

    fun selectsEveryDisjunction(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>): Boolean =
        forEachSelectedRow(model, disjunctionAtoms) { _, _, _ -> }

    // A disjunction named by registered atoms states its chosen row through sourceBranches.
    inline fun forEachSelectedRow(
        model: Problem,
        disjunctionAtoms: Map<Int, List<DisjunctAtom>>,
        action: (Int, Int, LinearRow) -> Unit,
    ): Boolean {
        for ((index, factor) in model.factors.withIndex()) {
            val rows = factor.linearRows
            val disjuncts = disjunctionAtoms[index]
            if (disjuncts != null) {
                if (disjuncts.none { it.row in sourceBranches }) return false
            } else if (factor.linearForm is LinearForm.Disjunction) {
                val selected = comparisonChoices[RowAddress(index, 0)] ?: return false
                action(index, selected, rows[selected])
            } else {
                for (rowIndex in rows.indices) action(index, rowIndex, rows[rowIndex])
            }
        }
        return true
    }

    fun nextDisequality(model: Problem, disjunctionAtoms: Map<Int, List<DisjunctAtom>>, bools: IntArray): RowAddress? {
        forEachSelectedRow(model, disjunctionAtoms) { factor, index, row ->
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

// Every bounded integer folded into the node's branches as [SearchNode.withBranch] would, in one pass: folding them
// one at a time scans and copies the branch list per bound, quadratic in the bounds a large root publishes.
private fun SearchNode.withPublishedBounds(
    numIntVars: Int,
    lowerBound: (Int) -> Long?,
    upperBound: (Int) -> Long?,
): SearchNode {
    var merged: MutableList<IntegerBranch>? = null
    var positions: MutableIntIntMap? = null
    for (integer in 0 until numIntVars) {
        val lower = lowerBound(integer)?.let(::bigIntOf)
        val upper = upperBound(integer)?.let(::bigIntOf)
        if (lower == null && upper == null) continue
        val list = merged ?: branches.toMutableList().also { merged = it }
        val index = positions ?: MutableIntIntMap(list.size * 2).also { map ->
            list.forEachIndexed { at, branch -> map.put(branch.variable, at) }
            positions = map
        }
        val at = index.getOrDefault(integer, -1)
        if (at < 0) {
            index.put(integer, list.size)
            list += IntegerBranch(integer, lower, upper)
        } else {
            val existing = list[at]
            list[at] = existing.copy(
                lower = maxOfNullable(existing.lower, lower),
                upper = minOfNullable(existing.upper, upper),
            )
        }
    }
    return merged?.let { copy(branches = it) } ?: this
}

// holds states one row of a comparison disjunction and fails its exact complement.
private class DisjunctAtom(val holds: RegisteredTheoryDecision, val fails: RegisteredTheoryDecision) {
    val row = holds.payload as SourceBoundAtom
    val complement = fails.payload as SourceBoundAtom
}

// The source branches search made. A decided disjunct restates a model row the size limits already count.
private fun SearchNode.searchedBranches(disjunctionAtoms: Map<Int, List<DisjunctAtom>>): List<SourceBoundAtom> {
    if (disjunctionAtoms.isEmpty()) return sourceBranches
    val restated = HashSet<SourceBoundAtom>()
    for (disjuncts in disjunctionAtoms.values) {
        for (disjunct in disjuncts) {
            restated += disjunct.row
            restated += disjunct.complement
        }
    }
    return sourceBranches.filter { it !in restated }
}

private fun SearchContext.truth(decision: RegisteredTheoryDecision): Boolean? =
    boolValue(decision.literal ushr 1)?.let { it == (decision.literal and 1 == 0) }

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

private fun BigFraction.isInteger(): Boolean = den == BIG_ONE

private fun BigFraction.floor(): BigInt {
    val quotient = num / den
    return if (num < BIG_ZERO && num % den != BIG_ZERO) quotient - BIG_ONE else quotient
}

private fun maxOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else maxOf(a, b)

private fun minOfNullable(a: BigInt?, b: BigInt?): BigInt? = if (a == null || b == null) a ?: b else minOf(a, b)
