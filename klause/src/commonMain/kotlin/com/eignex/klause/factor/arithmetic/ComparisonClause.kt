package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.MixedVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

/**
 * A disjunction of single-variable comparison literals: `⋁ᵢ (vars(i) ⟨ops(i)⟩ consts(i))`, holding
 * iff at least one literal holds. The direct representation of an XCSP3 `<intension>` disjunction /
 * implication of comparisons against constants (`imp(gt(x,a), lt(y,b))` is `(x ≤ a) ∨ (y ≤ b−1)`),
 * lowered as one factor instead of a fresh reifying indicator plus a [ReifiedLinear] per comparison.
 *
 * Each literal is a comparison of one integer variable to a constant; the propagator evaluates each
 * literal's truth straight from the live domains, so no auxiliary Boolean, order atom, or channeling
 * clause is introduced. Parallel arrays [vars] / [ops] / [consts] pair index-for-index.
 */
class ComparisonClause(val vars: IntArray, val ops: Array<LinearOp>, val consts: LongArray) : Factor {

    init {
        require(vars.isNotEmpty()) { "comparison clause must have at least one literal" }
        require(vars.size == ops.size && vars.size == consts.size) { "literal arrays must be parallel" }
    }

    override val variables: VarList = MixedVars(boundInts = vars.distinct().toIntArray(), boolVars = IntArray(0))

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ComparisonClause(vars.remapVars(intMap), ops, consts)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.COMPARISON_CLAUSE, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.COMPARISON_CLAUSE, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        // Positional per-literal payload: a rewrite through remap keeps the literal order, so the
        // materialised key and the remapped hash stay in lockstep without a canonical sort.
        for (i in vars.indices) {
            sink.intVar(vars[i])
            sink.enum(ops[i])
            sink.long(consts[i])
        }
    }

    override fun asPropagator(): Propagator = ComparisonClausePropagator(vars, ops, consts)

    override fun asInvariant(): Invariant = ComparisonClauseInvariant(vars, ops, consts)
}
