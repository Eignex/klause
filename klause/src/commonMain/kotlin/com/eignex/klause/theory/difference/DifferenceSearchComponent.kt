package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.arithmetic.difference.ShortestPaths
import com.eignex.klause.arithmetic.difference.checkedDifferenceSum
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.selectedFactorProjections
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.differenceFragmentOf
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExactIntValue
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.solver.search.contribute
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

/** Incremental difference-logic search component. */
class DifferenceSearchComponent private constructor(
    private val model: Problem,
    private val modelIntVars: IntArray,
    private val rootBoundVars: IntArray,
) : TheoryComponent {
    private val base = differenceFragmentOf(model.factors, model.numIntVars, model.intBounds)
    private val rootBoundPlan: RootBoundPlan? = run {
        if (rootBoundVars.isEmpty()) return@run null
        val requested = BooleanArray(model.numIntVars)
        for (variable in rootBoundVars) if (variable in requested.indices) requested[variable] = true
        val connected = BooleanArray(model.numIntVars)
        var alwaysEdges = 0L
        base?.edges?.forEach { edge ->
            if (edge.guard == DifferenceEdge.ALWAYS) {
                alwaysEdges++
            }
            if (edge.guard == DifferenceEdge.ALWAYS && !edge.domainBound) {
                if (edge.source in connected.indices && requested[edge.source]) connected[edge.source] = true
                if (edge.target in connected.indices && requested[edge.target]) connected[edge.target] = true
            }
        }
        val variables = rootBoundVars.filter { it in connected.indices && connected[it] }.toIntArray()
        if (variables.isEmpty()) return@run null
        val fragment = base ?: return@run null
        val edgeLimit = alwaysEdges + fragment.nodes.size * 2L
        if (edgeLimit > MAX_ROOT_BOUND_RELAXATIONS / 2L) return@run null
        val perNodeLimit = edgeLimit * 2L
        if (fragment.numNodes > MAX_ROOT_BOUND_RELAXATIONS / perNodeLimit) return@run null
        RootBoundPlan(variables)
    }
    private var assignment: Sample? = null
    private var hintEdges: Map<Int, List<DifferenceEdge>>? = null
    private var hintPoint: HintPoint? = null
    private var potentialSeed: PotentialSeed? = null
    private var guardPropagation: DifferenceGuardPropagation? = null
    private var guardPreparationComplete = false

    constructor(
        model: Problem,
        modelIntVars: IntArray = IntArray(model.numIntVars) { it },
    ) : this(model, model.selectedFactorProjections(), modelIntVars)

    /** Open one combined graph from selected, completely enforced source relations. */
    constructor(
        model: Problem,
        projections: PreparedFactorProjections,
        modelIntVars: IntArray = IntArray(model.numIntVars) { it },
    ) : this(requireNotNull(projections.linearTheoryModel(EncodingTarget.DIFFERENCE)) {
        "unsupported selected difference encoding"
    }.also { projections.requireSource(model) }, modelIntVars, intArrayOf())

    override fun initialize(context: SearchContext): ComponentResult {
        potentialSeed = null
        hintPoint = null
        guardPropagation?.retract()
        return when (val bounds = publishRootBounds(context)) {
            ComponentResult.Consistent -> propagate(context)
            else -> bounds
        }
    }

    override fun propagate(context: SearchContext): ComponentResult {
        hintPoint = null
        var interrupted = false
        fun stopped(): Boolean {
            if (!interrupted) interrupted = context.cancelled() || context.executionControls.exhausted
            return interrupted
        }
        fun abandon(): ComponentResult {
            potentialSeed = null
            return ComponentResult.Indeterminate
        }
        if (stopped()) return abandon()
        val fragment = fragment(context) ?: return ComponentResult.Consistent
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || context.boolValue(Lit.variable(guard)) == Lit.isPositive(guard)
        }
        val graph = fragment.graph()
        if (!guardPreparationComplete && model.numBoolVars > 0) {
            val owner = context.executionControls.cancellation
            val preparationStop = object : Cancellation {
                override fun isCancelled(): Boolean = stopped()
                override fun deadline() = owner.deadline()
                override fun workMeter() = owner.workMeter()
            }
            guardPropagation = base?.let {
                DifferenceGuardPropagation.create(it, model.numBoolVars, preparationStop)
            }
            if (stopped()) return abandon()
            guardPreparationComplete = true
        }
        var point: LongArray? = null
        var cycle: IntArray? = null
        potentialSeed?.let { seed ->
            val stop = context.executionControls.cancellation
            stop.charge(fragment.nodes.size.toLong())
            if (!stopped() && seed.nodes.contentEquals(fragment.nodes)) {
                stop.charge(fragment.numNodes.toLong())
                if (!stopped()) {
                    when (val outcome = graph.potentials(active, ::stopped, seed.values, { cycle = it })) {
                        is Potentials.Found -> point = outcome.values
                        Potentials.Infeasible -> Unit
                        Potentials.Abandoned -> potentialSeed = null
                    }
                }
            } else {
                potentialSeed = null
            }
        }
        if (stopped()) return abandon()
        cycle = cycle?.takeIf { validCycle(fragment, active, it, context, ::stopped) }
        if (point == null && cycle == null && !stopped()) {
            potentialSeed = null
            cycle = graph.negativeCycleWithPotential(active, ::stopped) { point = it }
            cycle = cycle?.takeIf { validCycle(fragment, active, it, context, ::stopped) }
        }
        if (stopped()) return abandon()
        cycle?.let {
            val explanation = cycleExplanation(fragment, it)
            if (stopped()) return abandon()
            return ComponentResult.Conflict(explanation)
        }
        val completePoint = point ?: return ComponentResult.Consistent
        potentialSeed = PotentialSeed(fragment.nodes, completePoint)
        val result = guardPropagation?.propagate(fragment, completePoint, context) ?: ComponentResult.Consistent
        if (result is ComponentResult.Indeterminate || stopped()) return abandon()
        if (hintEdges != null && result is ComponentResult.Consistent) {
            hintPoint = HintPoint(fragment, completePoint, context)
        }
        return result
    }

    override fun check(context: SearchContext): ComponentCheck {
        if (!context.consumeCheck() || context.cancelled()) return ComponentCheck.Indeterminate
        val bools = BooleanArray(model.numBoolVars) { variable ->
            context.boolValue(variable) ?: return ComponentCheck.Indeterminate
        }
        when (model.checkDifferenceBooleans(bools, Cancellation { context.cancelled() })) {
            false -> return ComponentCheck.Infeasible()
            null -> return ComponentCheck.Indeterminate
            true -> Unit
        }
        val fragment = fragment(context)
        val values = if (fragment == null) {
            unconstrainedValues(context)
        } else {
            // A spent budget is not a refutation: the check says it does not know, and the engine keeps
            // whatever verdict it already had rather than reading abandonment as infeasibility.
            when (val outcome = fragment.potentialSample(model.numIntVars, bools, context::cancelled)) {
                is Potentials.Found -> outcome.values
                Potentials.Infeasible -> return ComponentCheck.Infeasible()
                Potentials.Abandoned -> return ComponentCheck.Indeterminate
            }
        }
        if (values.indices.any { variable -> model.intDomainOrNull(variable)?.let {
                values[variable] !in it
            } == true }) return ComponentCheck.Indeterminate
        assignment = Sample(bools, values)
        return ComponentCheck.Feasible
    }

    override fun retract(decisionLevel: Int) {
        assignment = null
        hintPoint = null
        guardPropagation?.retract()
    }

    internal fun potentialHints(
        stop: Cancellation,
        maxEdges: Int = MAX_HINT_EDGES,
    ): Pair<IntArray, SearchCandidateHints>? {
        val fragment = base ?: return null
        if (fragment.edges.size > maxEdges || stop()) return null
        val groups = LinkedHashMap<Int, MutableList<DifferenceEdge>>()
        for (edge in fragment.edges) {
            stop.charge(1)
            if (stop()) return null
            if (edge.guard == DifferenceEdge.ALWAYS) continue
            val variable = Lit.variable(edge.guard)
            if (variable !in 0 until model.numBoolVars) return null
            groups.getOrPut(variable) { ArrayList(2) }.add(edge)
        }
        if (groups.isEmpty()) return null
        stop.charge(groups.size.toLong())
        if (stop()) return null
        val variables = groups.keys.toIntArray()
        hintEdges = groups
        return variables to SearchCandidateHints(::preferredBool)
    }

    internal fun releasePotentialHints() {
        hintEdges = null
        hintPoint = null
        guardPropagation = null
        guardPreparationComplete = false
    }

    internal fun releaseSearchState() {
        potentialSeed = null
        assignment = null
        releasePotentialHints()
    }

    internal fun potentialHintAvailable(): Boolean = hintPoint?.let {
        it.context.decisionLevel == it.level && !it.context.executionControls.exhausted
    } == true

    internal fun potentialHintBool(variable: Int): Boolean? {
        val point = hintPoint ?: return null
        if (!potentialHintAvailable()) return null
        return point.context.boolValue(variable)
    }

    private fun preferredBool(variable: Int): Boolean? {
        val point = hintPoint ?: return null
        if (point.context.decisionLevel != point.level) return null
        val edges = hintEdges?.get(variable) ?: return null
        var positive = true
        var negative = true
        var hasPositive = false
        var hasNegative = false
        for (edge in edges) {
            val context = point.context
            context.executionControls.cancellation.charge(1)
            if (context.cancelled() || context.executionControls.exhausted) {
                context.requestCancellationPoll()
                return null
            }
            val from = point.values[point.fragment.nodeOf(edge.source)]
            val to = point.values[point.fragment.nodeOf(edge.target)]
            val bound = from + edge.bound
            if (((from xor bound) and (edge.bound xor bound)) < 0L) return null
            if (Lit.isPositive(edge.guard)) {
                hasPositive = true
                positive = positive && to <= bound
            } else {
                hasNegative = true
                negative = negative && to <= bound
            }
        }
        return if (!hasPositive || !hasNegative || positive == negative) null else positive
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { assignment ->
            for (variable in modelIntVars) {
                val value = assignment.intValue(variable)
                model.put(SearchIntValue(variable), value)
                model.contribute(SearchExactIntValue(SourceIntId(variable)), bigIntOf(value))
            }
            model.put(this, assignment)
        }
    }

    private fun fragment(context: SearchContext): DifferenceFragment? {
        val original = base ?: return null
        val edges = ArrayList<DifferenceEdge>(original.edges.size + model.numIntVars * 2)
        edges.addAll(original.edges)
        for (variable in 0 until model.numIntVars) {
            context.intUpperBound(variable)?.let { edges += DifferenceEdge(DifferenceFragment.ZERO, variable, it) }
            context.intLowerBound(variable)?.takeUnless { it == Long.MIN_VALUE }?.let {
                edges += DifferenceEdge(variable, DifferenceFragment.ZERO, -it)
            }
        }
        return DifferenceFragment(edges)
    }

    /**
     * Publish consequences of always-active rows and the current root bounds.
     *
     * Every antecedent is fixed for this root lifetime: guarded rows are excluded, and root facts can
     * only be replaced through `resetRootFacts`, which is followed by component initialization. The
     * consequences therefore live at level zero, survive ordinary backtracking, and need no
     * retractable Boolean explanation.
     */
    private fun publishRootBounds(context: SearchContext): ComponentResult {
        val plan = rootBoundPlan ?: return ComponentResult.Consistent
        if (context.decisionLevel != 0) return ComponentResult.Consistent
        val original = base ?: return ComponentResult.Consistent
        val edges = original.edges.filterTo(ArrayList()) { it.guard == DifferenceEdge.ALWAYS }
        for (variable in original.nodes) {
            context.intUpperBound(variable)?.let { edges += DifferenceEdge(DifferenceFragment.ZERO, variable, it) }
            context.intLowerBound(variable)?.takeUnless { it == Long.MIN_VALUE }?.let {
                edges += DifferenceEdge(variable, DifferenceFragment.ZERO, -it)
            }
        }
        val fragment = DifferenceFragment(edges)
        val graph = fragment.graph()
        val uppers = graph.shortestPaths(fragment.zeroNode, cancelled = context::cancelled)
        if (uppers !is ShortestPaths.Found) return rootBoundOutcome(uppers)
        val reverse = graph.shortestPaths(fragment.zeroNode, reversed = true, cancelled = context::cancelled)
        if (reverse !is ShortestPaths.Found) return rootBoundOutcome(reverse)
        for (variable in plan.variables) {
            val node = fragment.nodeOf(variable)
            if (node < 0) continue
            val derivedUpper = uppers.values[node].takeIf { uppers.reachable[node] }
            val reverseDistance = reverse.values[node].takeIf { reverse.reachable[node] }
            val derivedLower = reverseDistance?.takeUnless { it == Long.MIN_VALUE }?.let { -it }
            val priorLower = context.intLowerBound(variable)
            val priorUpper = context.intUpperBound(variable)
            val lower = derivedLower?.let { priorLower?.let { prior -> maxOf(prior, it) } ?: it } ?: priorLower
            val upper = derivedUpper?.let { priorUpper?.let { prior -> minOf(prior, it) } ?: it } ?: priorUpper
            if (lower != null && upper != null && lower > upper) return ComponentResult.Conflict()
            val lowerChanged = lower != null && lower != priorLower
            val upperChanged = upper != null && upper != priorUpper
            val publication = when {
                !lowerChanged && !upperChanged -> continue
                lower != null && lower == upper -> context.publish(SearchDecision.IntEqual(variable, lower))
                lowerChanged -> context.publish(SearchDecision.IntAtLeast(variable, lower))
                else -> context.publish(SearchDecision.IntAtMost(variable, checkNotNull(upper)))
            }
            if (publication !is ComponentResult.Consistent) return publication
            if (lowerChanged && upperChanged && lower != upper) {
                val second = context.publish(SearchDecision.IntAtMost(variable, upper))
                if (second !is ComponentResult.Consistent) return second
            }
        }
        return ComponentResult.Consistent
    }

    private fun rootBoundOutcome(paths: ShortestPaths): ComponentResult = when (paths) {
        is ShortestPaths.Found -> ComponentResult.Consistent
        ShortestPaths.Infeasible -> ComponentResult.Conflict()
        ShortestPaths.Abandoned -> ComponentResult.Consistent
    }

    private fun cycleExplanation(fragment: DifferenceFragment, cycle: IntArray): SearchExplanation? {
        val guards = cycle.map { fragment.edges[it].guard }
        if (guards.any { it == DifferenceEdge.ALWAYS }) return null
        return SearchExplanation(guards.distinct().map(Lit::negate).toIntArray())
    }

    private fun validCycle(
        fragment: DifferenceFragment,
        active: BooleanArray,
        cycle: IntArray,
        context: SearchContext,
        stopped: () -> Boolean,
    ): Boolean {
        if (cycle.isEmpty()) return false
        var sum = 0L
        for (index in cycle.indices) {
            context.executionControls.cancellation.charge(1)
            if (stopped()) return false
            val edgeIndex = cycle[index]
            val next = cycle[(index + 1) % cycle.size]
            if (edgeIndex !in fragment.edges.indices || next !in fragment.edges.indices || !active[edgeIndex]) {
                return false
            }
            val edge = fragment.edges[edgeIndex]
            if (edge.source != fragment.edges[next].target) return false
            sum = checkedDifferenceSum(sum, edge.bound) ?: return false
        }
        return sum < 0L
    }

    private fun unconstrainedValues(context: SearchContext): LongArray = LongArray(model.numIntVars) { variable ->
        context.intLowerBound(variable) ?: when {
            model.intBounds.hasLower(variable) -> model.intBounds.lower(variable)
            context.intUpperBound(variable) != null -> context.intUpperBound(variable)!!
            model.intBounds.hasUpper(variable) -> model.intBounds.upper(variable)
            else -> 0L
        }
    }

    internal companion object {
        // Root cooperation is optional; keep its worst-case relaxation work small and deterministic.
        private const val MAX_ROOT_BOUND_RELAXATIONS = 65_536L
        // Hint incidences have a bounded private index; declining it leaves complete graph coverage intact.
        private const val MAX_HINT_EDGES = 65_536

        fun withRootBounds(
            model: Problem,
            modelIntVars: IntArray,
            rootBoundVars: IntArray,
        ): DifferenceSearchComponent = DifferenceSearchComponent(model, modelIntVars, rootBoundVars)
    }

    private data class RootBoundPlan(val variables: IntArray)

    private class PotentialSeed(val nodes: IntArray, val values: LongArray)

    private class HintPoint(val fragment: DifferenceFragment, val values: LongArray, val context: SearchContext) {
        val level = context.decisionLevel
    }
}
