package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit

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
 * Each [propagate] substitutes the current partial assignment, reduces the residual system to
 * row-echelon form, and pins every variable the system forces (rows that collapse to a single
 * variable). Conflicts and forced pins are explained sharply: every row carries a reason
 * bitset of the assigned variables feeding it, xor-combined through each elimination step,
 * so even-occurrence variables cancel and a derived row's reason is exactly its
 * odd-occurrence assigned support — the minimal sufficient set (#174).
 *
 * This factor is **propagation-only**: it inherits the [Factor] local-search defaults
 * (always-satisfied, zero deltas). The Gaussian system is redundant with the per-row [Xor]
 * factors posted alongside it, which carry the same parity semantics *with* real LS support,
 * so LS enforces each parity row via those siblings.
 */
class GaussianXor(override val constraints: List<Xor>) :
    Factor,
    GaussianXorPropagator,
    GaussianXorInvariant {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        GaussianXor(constraints.map { it.remap(boolMap, intMap) as Xor })

    /** Union of all variables across the constraints, in stable order; column index = position. */
    override val boolVars: IntArray
    override val intVars: IntArray = EmptyIntArray

    override val colOfVar: HashMap<Int, Int>
    override val words: Int
    override val rowMask: Array<LongArray>
    override val rowRhs: IntArray

    init {
        require(constraints.isNotEmpty()) { "GaussianXor needs at least one constraint" }
        val order = LinkedHashSet<Int>()
        for (c in constraints) for (lit in c.literals) order.add(Lit.variable(lit))
        boolVars = order.toIntArray()
        colOfVar = HashMap(boolVars.size * 2)
        for (i in boolVars.indices) colOfVar[boolVars[i]] = i
        words = (boolVars.size + 63) ushr 6

        rowMask = Array(constraints.size) { LongArray(words) }
        rowRhs = IntArray(constraints.size)
        for (r in constraints.indices) {
            val c = constraints[r]
            val occ = HashMap<Int, Int>()
            var negParity = 0
            for (lit in c.literals) {
                val v = Lit.variable(lit)
                occ[v] = (occ[v] ?: 0) + 1
                if (!Lit.isPositive(lit)) negParity = negParity xor 1
            }
            for ((v, count) in occ) {
                if (count and 1 == 1) {
                    val col = colOfVar.getValue(v)
                    rowMask[r][col ushr 6] = rowMask[r][col ushr 6] or (1L shl (col and 63))
                }
            }
            rowRhs[r] = c.targetParity xor negParity
        }
    }
}
