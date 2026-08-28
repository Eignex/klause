package com.eignex.klause.theory.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.arithmetic.difference.Potentials
import com.eignex.klause.arithmetic.difference.ShortestPaths
import com.eignex.klause.arithmetic.difference.potentialSample
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ProblemSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.differenceFragmentOf
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchIntValue
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.solver.search.TheoryComponent

/** Incremental difference-logic search component. */
class DifferenceSearchComponent private constructor(
    private val model: ProblemSpec,
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

    constructor(
        model: ProblemSpec,
        modelIntVars: IntArray = IntArray(model.numIntVars) { it },
    ) : this(model, modelIntVars, intArrayOf())

    override fun initialize(context: SearchContext): ComponentResult = when (val bounds = publishRootBounds(context)) {
        ComponentResult.Consistent -> propagate(context)
        else -> bounds
    }

    override fun propagate(context: SearchContext): ComponentResult {
        val fragment = fragment(context) ?: return ComponentResult.Consistent
        val active = BooleanArray(fragment.edges.size) { edge ->
            val guard = fragment.edges[edge].guard
            guard == DifferenceEdge.ALWAYS || context.boolValue(Lit.variable(guard)) == Lit.isPositive(guard)
        }
        val cycle = fragment.graph().negativeCycle(active, context::cancelled)
            ?: return ComponentResult.Consistent
        return ComponentResult.Conflict(cycleExplanation(fragment, cycle))
    }

    override fun check(context: SearchContext): ComponentCheck {
        if (!context.consumeCheck() || context.cancelled()) return ComponentCheck.Indeterminate
        val bools = BooleanArray(model.numBoolVars) { variable ->
            context.boolValue(variable) ?: return ComponentCheck.Indeterminate
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
        assignment = Sample(bools, values)
        return ComponentCheck.Feasible
    }

    override fun retract(decisionLevel: Int) {
        assignment = null
    }

    override fun contributeModel(model: SearchModel, context: SearchContext) {
        assignment?.let { assignment ->
            for (variable in modelIntVars) model.put(SearchIntValue(variable), assignment.ints[variable])
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

        fun withRootBounds(
            model: ProblemSpec,
            modelIntVars: IntArray,
            rootBoundVars: IntArray,
        ): DifferenceSearchComponent = DifferenceSearchComponent(model, modelIntVars, rootBoundVars)
    }

    private data class RootBoundPlan(val variables: IntArray)
}
