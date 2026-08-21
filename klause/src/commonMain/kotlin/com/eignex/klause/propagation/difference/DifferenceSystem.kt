package com.eignex.klause.propagation.difference

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.StructuralKey

/**
 * A *system* of difference constraints `x − y ≤ c` propagated jointly as a weighted digraph.
 *
 * A single reified difference row can only act once its own variables are nearly fixed. The system
 * reasons over all of them at once: the asserted rows are unsatisfiable exactly when the graph they form
 * holds a negative cycle, which is a structural test over the whole of ℤ — no finite search box, and no
 * dependence on any variable being bounded. That is what makes the difference fragment decidable, and it
 * is the deduction a row-at-a-time propagator cannot reach.
 *
 * A cycle is explained by the guards of the edges on it, so the learned clause names exactly the reified
 * rows whose conjunction is contradictory.
 *
 * This factor is **propagation-only**: it inherits the [Factor] local-search defaults. The system is
 * redundant with the [com.eignex.klause.factor.arithmetic.Linear] and
 * [com.eignex.klause.factor.arithmetic.ReifiedLinear] rows posted alongside it, which carry the same
 * semantics *with* real LS support, so those siblings stay and enforce each row for local search.
 */
internal class DifferenceSystem(
    /** Edges over integer variables, with [DifferenceFragment.ZERO] for the constant. */
    val edges: List<DifferenceEdge>,
) : Factor {

    override val intVars: IntArray

    /** The fragment is decidable over ℤ with no bounds at all; that is the point of the graph. */
    override val needsFiniteDomains: Boolean get() = false
    override val boolVars: IntArray

    init {
        require(edges.isNotEmpty()) { "DifferenceSystem needs at least one edge" }
        val ints = LinkedHashSet<Int>()
        val bools = LinkedHashSet<Int>()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) ints.add(e.source)
            if (e.target != DifferenceFragment.ZERO) ints.add(e.target)
            if (e.guard != DifferenceEdge.ALWAYS) bools.add(Lit.variable(e.guard))
        }
        intVars = ints.toIntArray()
        boolVars = bools.toIntArray()
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = DifferenceSystem(
        edges.map { e ->
            DifferenceEdge(
                source = if (e.source == DifferenceFragment.ZERO) e.source else intMap[e.source],
                target = if (e.target == DifferenceFragment.ZERO) e.target else intMap[e.target],
                bound = e.bound,
                guard = if (e.guard == DifferenceEdge.ALWAYS) {
                    e.guard
                } else {
                    Lit.make(boolMap[Lit.variable(e.guard)], Lit.isPositive(e.guard))
                },
                domainBound = e.domainBound,
            )
        },
    )

    /**
     * The system is an order-insensitive set of edges, each keyed by its endpoints, bound, and guard.
     * [DifferenceEdge.domainBound] is part of the key because the propagator treats such an edge
     * differently, so two systems that differ only there are not interchangeable.
     */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.DIFFERENCE_SYSTEM) {
        int(edges.size)
        for (e in edges.sortedWith(compareBy({ it.source }, { it.target }, { it.bound }, { it.guard }))) {
            int(e.source)
            int(e.target)
            long(e.bound)
            int(e.guard)
            int(if (e.domainBound) 1 else 0)
        }
    }

    override fun asPropagator(): Propagator = DifferenceSystemPropagator(edges)

    override fun asInvariant(): Invariant = NoInvariant
}
