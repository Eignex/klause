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
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

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

    override fun canSubstituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Boolean =
        canSubstituteAffine(x, scale, offset, Cancellation.Never)

    internal fun canSubstituteAffine(x: Int, scale: Int, offset: Int, cancellation: Cancellation): Boolean {
        if (scale != 1 && scale != -1) return false
        for (i in vars.indices) {
            // Include the later validation and materialization scans in the accepted view's allowance.
            cancellation.charge(8)
            if ((i and 63) == 0 && cancellation()) return false
            if (vars[i] == x && translatedConstant(i, scale, offset) == null) return false
        }
        return !cancellation()
    }

    override fun substituteAffine(x: Int, scale: Int, offset: Int, replacement: Int): Factor? {
        if (!canSubstituteAffine(x, scale, offset, replacement)) return null
        if (x !in vars) return this
        val nextVars = vars.copyOf()
        val nextOps = ops.copyOf()
        val nextConsts = consts.copyOf()
        for (i in vars.indices) {
            if (vars[i] != x) continue
            nextVars[i] = replacement
            nextConsts[i] = requireNotNull(translatedConstant(i, scale, offset))
            if (scale < 0) nextOps[i] = when (ops[i]) {
                LinearOp.LE -> LinearOp.GE
                LinearOp.GE -> LinearOp.LE
                LinearOp.EQ -> LinearOp.EQ
                LinearOp.NE -> LinearOp.NE
            }
        }
        return ComparisonClause(nextVars, nextOps, nextConsts)
    }

    private fun translatedConstant(i: Int, scale: Int, offset: Int): Long? = try {
        mulExact(scale.toLong(), subExact(consts[i], offset.toLong()))
    } catch (_: CheckedLongOverflowException) {
        null
    }

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
