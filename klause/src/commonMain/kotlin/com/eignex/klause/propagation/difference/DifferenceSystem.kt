package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.arithmetic.difference.DifferenceFragment
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap

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
 * This factor is **propagation-only**: its local-search projection is deliberately inert. The system is
 * redundant with the [com.eignex.klause.factor.arithmetic.Linear] and
 * [com.eignex.klause.factor.arithmetic.ReifiedLinear] rows posted alongside it, which carry the same
 * semantics *with* real LS support, so those siblings stay and enforce each row for local search.
 */
internal class DifferenceSystem(
    // Endpoints are integer columns or private graph vertices; DifferenceFragment.ZERO is the constant.
    val edges: List<DifferenceEdge>,
    // Private arithmetic vertices do not participate in finite integer renumbering.
    val privateVertices: Boolean = false,
    // Native equality atoms belong to the receiving propagation state.
    val indexGuards: Map<Int, DifferenceIndexGuard> = emptyMap(),
) : Factor {

    override val variables: VarList

    init {
        require(edges.isNotEmpty()) { "DifferenceSystem needs at least one edge" }
        require(indexGuards.keys.all { it in edges.indices && edges[it].guard == DifferenceEdge.ALWAYS })
        require(indexGuards.values.all { it.variable >= 0 })
        val ints = LinkedHashSet<Int>()
        val bools = LinkedHashSet<Int>()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) ints.add(e.source)
            if (e.target != DifferenceFragment.ZERO) ints.add(e.target)
            if (e.guard != DifferenceEdge.ALWAYS) bools.add(Lit.variable(e.guard))
        }
        for (guard in indexGuards.values) ints.add(guard.variable)
        variables = MixedVars(
            boundInts = if (privateVertices) indexGuards.values.map { it.variable }.distinct().toIntArray()
                else ints.toIntArray(),
            boolVars = bools.toIntArray(),
        )
    }

    override fun remap(mapping: VarRemap): Factor = DifferenceSystem(
        edges.map { e ->
            DifferenceEdge(
                source = if (privateVertices || e.source == DifferenceFragment.ZERO) e.source
                    else mapping.int(e.source),
                target = if (privateVertices || e.target == DifferenceFragment.ZERO) e.target
                    else mapping.int(e.target),
                bound = e.bound,
                guard = if (e.guard == DifferenceEdge.ALWAYS) {
                    e.guard
                } else {
                    Lit.make(mapping.bool(Lit.variable(e.guard)), Lit.isPositive(e.guard))
                },
                domainBound = e.domainBound,
            )
        },
        privateVertices = privateVertices,
        indexGuards = indexGuards.mapValues { (_, guard) ->
            DifferenceIndexGuard(mapping.int(guard.variable), guard.value)
        },
    )

    /**
     * The system is an order-insensitive set of edges, each keyed by its endpoints, bound, and guard.
     * [DifferenceEdge.domainBound] is part of the key because the propagator treats such an edge
     * differently, so two systems that differ only there are not interchangeable.
     */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.DIFFERENCE_SYSTEM) {
        bool(privateVertices)
        int(edges.size)
        for ((index, e) in edges.withIndex().sortedWith(
            compareBy({ it.value.source }, { it.value.target }, { it.value.bound }, { it.value.guard },
                { indexGuards[it.index]?.variable ?: -1 }, { indexGuards[it.index]?.value ?: 0L }),
        )) {
            int(e.source)
            int(e.target)
            long(e.bound)
            int(e.guard)
            int(if (e.domainBound) 1 else 0)
            val selector = indexGuards[index]
            int(selector?.variable ?: -1)
            long(selector?.value ?: 0L)
        }
    }
}

internal data class DifferenceIndexGuard(val variable: Int, val value: Long)
