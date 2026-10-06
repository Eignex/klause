package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.propagation.propagate

/**
 * What local search needs from a model: its factors, the domain each integer column moves over, and the pins
 * root reasoning establishes.
 *
 * A finite model reaches local search through [of], which reads the root-propagated domains and deductions of a
 * [BakedProblem]. A model with an open integer side has no finite domain to bake and reaches it through [open]:
 * the columns move over domains the caller chose, nothing is pinned at the root, and nothing local search
 * concludes refutes the model.
 */
class LocalSearchModel private constructor(
    /** The model searched: its factors and its column counts. */
    val problem: Problem,
    /** The domain each integer column moves over, in column order. Read only. */
    val domains: Array<IntDomain>,
    private val rootPins: (Assumptions) -> Assumptions?,
    /** Whether an assignment refuted by [pinsUnder] refutes the model, so local search may report it
     *  infeasible. False when the domains were not declared by the model. */
    val refutesModel: Boolean,
) {
    /**
     * The pins [assumptions] imply together with the root's own, or `null` when root reasoning refutes them. A
     * `null` is a proof only under [refutesModel].
     */
    fun pinsUnder(assumptions: Assumptions): Assumptions? = rootPins(assumptions)

    /** The two ways a model reaches local search. */
    companion object {
        /** The finite model [problem], over its root-propagated domains and under its root deductions. */
        fun of(problem: BakedProblem): LocalSearchModel = LocalSearchModel(
            problem = problem,
            domains = problem.rootIntDomainsInPlace,
            rootPins = { assumptions -> problem.rootPins(assumptions) },
            refutesModel = true,
        )

        /**
         * A model whose integer columns may be open, searched over [domains]: each column's declared range with
         * every open side replaced by an endpoint the caller chose. Those endpoints bound where local search
         * looks, not what the model admits, so nothing here refutes it and no root reasoning pins a column.
         */
        fun open(problem: Problem, domains: Array<IntDomain>): LocalSearchModel {
            require(domains.size == problem.numIntVars) {
                "${domains.size} search domains for ${problem.numIntVars} integer columns"
            }
            return LocalSearchModel(problem, domains, rootPins = { it }, refutesModel = false)
        }
    }
}

/**
 * Fold the bake-time propagation result and [assumptions] into the pins the search sees, or `null` when
 * propagation refutes them.
 */
private fun BakedProblem.rootPins(assumptions: Assumptions): Assumptions? {
    val root = baked
    if (root is PropagationResult.Unsat) return null
    root as PropagationResult.Implied
    if (assumptions.isEmpty) return if (root.isEmpty) Assumptions.None else root.toAssumptions()
    return when (val r = propagate(assumptions)) {
        is PropagationResult.Unsat -> null
        is PropagationResult.Implied -> assumptions.mergedWith(r.toAssumptions())
    }
}
