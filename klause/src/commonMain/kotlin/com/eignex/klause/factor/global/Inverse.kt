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
import com.eignex.util.collections.IntIntMap

/**
 * `inverse(f, g)` with optional offsets: over 0-based positions, `f(i) = j  ⇔  g(j - gOffset) = i + fOffset`.
 *
 * Propagation tightens both sides to the index span, closes the channel under value removal, and runs
 * matching filtering on each side, since both are bijections and the channel closure alone can stall
 * short of GAC.
 */
class Inverse(
    /** Forward mapping variable ids: `f(i)` is the image of `i`. */
    val f: IntArray,
    /** Inverse mapping variable ids: `g(j)` is the preimage of `j`. */
    val g: IntArray,
    /** Offset of the [f] index set: `g(j)` takes values `i + fOffset`. */
    val fOffset: Int = 0,
    /** Offset of the [g] index set: `f(i)` takes values `j + gOffset`. */
    val gOffset: Int = 0,
) : Factor {

    init {
        require(f.size == g.size) { "inverse: f and g must have equal length" }
        require(f.isNotEmpty()) { "inverse: empty arrays" }
    }

    override fun remap(mapping: VarRemap): Factor = Inverse(mapping.ints(f), mapping.ints(g), fOffset, gOffset)

    // Positional. f and g are not canonicalised against each other, which can only miss an f↔g symmetry.
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.INVERSE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.INVERSE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(fOffset)
        sink.int(gOffset)
        sink.intVars(f)
        sink.intVars(g)
    }

    override val variables: VarList = SpanIntVars(f + g)

    internal val fIndexOf: IntIntMap = IntIntMap.build(f, IntArray(f.size) { it }, absent = -1)

    internal val gIndexOf: IntIntMap = IntIntMap.build(g, IntArray(g.size) { it }, absent = -1)
}
