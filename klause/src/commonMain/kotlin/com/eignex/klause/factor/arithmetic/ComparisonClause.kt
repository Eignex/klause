package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/**
 * Disjunction `⋁ᵢ (vars(i) ⟨ops(i)⟩ consts(i))` of single-variable comparisons against constants.
 * Parallel arrays [vars], [ops] and [consts] align by index. The propagator evaluates literals
 * directly from live domains without auxiliary Booleans or channeling clauses.
 */
class ComparisonClause(val vars: IntArray, val ops: Array<LinearOp>, val consts: LongArray) : Factor {

    init {
        require(vars.isNotEmpty()) { "comparison clause must have at least one literal" }
        require(vars.size == ops.size && vars.size == consts.size) { "literal arrays must be parallel" }
    }

    override val linearForm: LinearForm = LinearForm.Disjunction(
        vars.indices.map { i ->
            LinearRow.ofInts(intArrayOf(vars[i]), longArrayOf(1L), ops[i], consts[i])
        },
    )
    override val variables: VarList = MixedVars(boundInts = vars.distinct().toIntArray(), boolVars = IntArray(0))

    override fun remap(mapping: VarRemap): Factor = ComparisonClause(mapping.ints(vars), ops, consts)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.COMPARISON_CLAUSE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.COMPARISON_CLAUSE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        // Positional per-literal payload: a rewrite through remap keeps the literal order, so the
        // materialised key and the remapped hash stay in lockstep without a canonical sort.
        for (i in vars.indices) {
            sink.intVar(vars[i])
            sink.enum(ops[i])
            sink.long(consts[i])
        }
    }
}
