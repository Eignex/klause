package com.eignex.klause.factor.bool

import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.Propagator

/**
 * A *system* of parity (XOR) constraints propagated jointly by Gauss-Jordan elimination over
 * GF(2) (Soos, Nohl and Castelluccia, "Extending SAT Solvers to Cryptographic Problems", SAT 2009,
 * https://doi.org/10.1007/978-3-642-02777-2_24). Each constraint is `XOR(vars) == rhs`.
 *
 * Unlike a single [Xor], elimination combines equations, so it detects an inconsistency or forces a
 * variable as soon as the linear system implies it. Each [Propagator.propagate] explains a conflict or
 * pin by the odd-occurrence assigned support of the derived row.
 *
 * Propagation-only: its local-search projection is inert, since the per-row [Xor] factors posted
 * alongside it carry the same parity with local-search support.
 */
class GaussianXor internal constructor(
    /** The individual parity constraints forming this Gaussian system. */
    val constraints: List<Xor>,
    internal val affineSeed: AffineXorSeed?,
) : Factor {

    /** Creates a jointly propagated system from its parity rows. */
    constructor(constraints: List<Xor>) : this(constraints, null)

    override fun remap(mapping: VarRemap): Factor = GaussianXor(constraints.map { it.remap(mapping) as Xor })

    /** Order-insensitive: the constraints are encoded as a sorted multiset. */
    // No allocation-free KeySink hash: the key sorts nested sub-constraints by their own (remapped)
    // structuralKey, which the linear sink walk can't reproduce.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.GAUSSIAN_XOR) {
        int(constraints.size)
        for (c in constraints.sortedBy { it.structuralKey() }) {
            int(c.targetParity)
            sortedInts(c.literals)
        }
    }

    /** Union of all variables across the constraints, in stable order. */
    override val variables: VarList

    init {
        require(constraints.isNotEmpty()) { "GaussianXor needs at least one constraint" }
        variables = if (affineSeed != null) BoolVars(affineSeed.variables) else {
            val order = LinkedHashSet<Int>()
            for (c in constraints) for (lit in c.literals) order.add(Lit.variable(lit))
            BoolVars(order.toIntArray())
        }
    }
}
