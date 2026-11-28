package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/**
 * `value_precede(s, t, xs)`: value [t] may appear in [xs] only at a position after value [s]
 * has appeared, or never.
 *
 * Propagation is GAC in one O(n) scan (Y. C. Law and J. H. M. Lee, "Global Constraints for Integer and Set
 * Value Precedence", CP 2004, https://doi.org/10.1007/978-3-540-30201-8_28): [t] is pruned up to the first
 * position that can still be [s], and when exactly one position before the earliest forced [t] can be [s],
 * it is fixed to [s].
 */
class ValuePrecede(val s: Long, val t: Long, val xs: IntArray) : Factor {

    override fun remap(mapping: VarRemap): Factor = ValuePrecede(s, t, mapping.ints(xs))

    // Positional: the sequence order decides "before", so xs is not sorted.
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.VALUE_PRECEDE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.VALUE_PRECEDE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.long(s)
        sink.long(t)
        sink.intVars(xs)
    }

    override fun remapValues(valueMap: (Long) -> Long): Factor = ValuePrecede(valueMap(s), valueMap(t), xs)

    override val variables: VarList = SpanIntVars(xs)
}
