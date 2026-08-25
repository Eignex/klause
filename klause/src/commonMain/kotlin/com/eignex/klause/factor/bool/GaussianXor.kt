package com.eignex.klause.factor.bool

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.LitVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList

/**
 * A *system* of parity (XOR) constraints propagated jointly by Gauss-Jordan elimination over
 * GF(2). Each constraint is `XOR(vars) == rhs`; the factor owns all of them as one matrix.
 *
 * Unlike a single [Xor] factor — which can only force a variable once a constraint has exactly
 * one unassigned variable left — Gaussian elimination *combines* equations, so it detects an
 * inconsistency (`0 = 1`) or forces a variable as soon as the linear system implies it. That is
 * what makes XOR-hash model counting / sampling tractable: without it, the parity subspace has
 * no short clausal refutations and the search thrashes on every infeasible branch (klause has no
 * clausal Gaussian reasoning otherwise). With it, enumerating a hashed cell visits essentially
 * only its real solutions.
 *
 * Each [Propagator.propagate] substitutes the current partial assignment, reduces the residual system to
 * row-echelon form, and pins every variable the system forces (rows that collapse to a single
 * variable). Conflicts and forced pins are explained sharply: every row carries a reason
 * bitset of the assigned variables feeding it, xor-combined through each elimination step,
 * so even-occurrence variables cancel and a derived row's reason is exactly its
 * odd-occurrence assigned support — the minimal sufficient set.
 *
 * This factor is **propagation-only**: it inherits the [Factor] local-search defaults
 * (always-satisfied, zero deltas). The Gaussian system is redundant with the per-row [Xor]
 * factors posted alongside it, which carry the same parity semantics *with* real LS support,
 * so LS enforces each parity row via those siblings.
 */
class GaussianXor(
    /** The individual parity constraints forming this Gaussian system. */
    val constraints: List<Xor>,
) : Factor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        GaussianXor(constraints.map { it.remap(boolMap, intMap) as Xor })

    /** A system of parity equations is order-insensitive, so the constraints are encoded as a sorted
     *  multiset — each as its target parity and its literal set. */
    // Not migrated to the KeySink allocation-free hash: the key sorts nested sub-constraints by their
    // own (remapped) structuralKey, which the linear sink walk can't reproduce.
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
        val order = LinkedHashSet<Int>()
        for (c in constraints) for (lit in c.literals) order.add(Lit.variable(lit))
        variables = LitVars(order.toIntArray())
    }

    override fun asPropagator(): Propagator = GaussianXorPropagator(constraints, boolVars)

    override fun asInvariant(): Invariant = NoInvariant
}
