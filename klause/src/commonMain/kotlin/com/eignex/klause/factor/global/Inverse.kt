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
import com.eignex.klause.util.IntIntMap

/**
 * `inverse(f, g)` with optional offsets: over 0-based positions, `f(i) = j  ⇔  g(j - gOffset) = i + fOffset`.
 *
 * The canonical 0-based form is `f(i) = j ⇔ g(j) = i`. MiniZinc emits with index offsets
 * matching its 1-based default; the offsets are encoded into the factor so the dispatch
 * doesn't have to allocate channel vars.
 *
 * Propagation is GAC for the channel in three layers:
 *  1. range-tighten each `f(i)` / `g(j)` to the legal index span and force singletons across the channel;
 *  2. bidirectional value removal — if `i + fOffset` is absent from `dom(g(j))`, also remove `j + gOffset`
 *     from `dom(f(i))`, and symmetrically: the arc-consistent closure of `f(i) = j ⇔ g(j) = i`;
 *  3. matching filtering on f and on g. The biconditional forces both to be bijections
 *     (`f(i1) = f(i2) = j ⇒ g(j) = i1 = i2`), so each side is all-different, and the channel closure alone
 *     can stall at a mutual non-GAC fixpoint (it keeps `f(2) = 0` because `g(0) = 2` is unpruned, and vice
 *     versa). Hall-set filtering on each side removes the values the channel misses.
 */
class Inverse(
    /** Forward mapping variable ids: `f(i)` is the image of `i`. */
    val f: IntArray,
    /** Inverse mapping variable ids: `g(j)` is the preimage of `j`. */
    val g: IntArray,
    /** Index offset for the [f] domain. */
    val fOffset: Int = 0,
    /** Index offset for the [g] domain. */
    val gOffset: Int = 0,
) : Factor {

    init {
        require(f.size == g.size) { "inverse: f and g must have equal length" }
        require(f.isNotEmpty()) { "inverse: empty arrays" }
    }

    override fun remap(mapping: VarRemap): Factor = Inverse(mapping.ints(f), mapping.ints(g), fOffset, gOffset)

    // Positional: f(i)/g(i) are channelled by index, so neither array is sorted. Encodes both
    // offsets and the ordered f / g var sequences — fine enough that two non-equivalent Inverses
    // never share a key (required for sound symmetry verification). The f/g sides are kept distinct
    // (not canonicalised against each other); at worst this misses an f↔g symmetry, never unsound.
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.INVERSE, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.INVERSE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(fOffset)
        sink.int(gOffset)
        sink.intVars(f)
        sink.intVars(g)
    }

    override val variables: VarList = SpanIntVars(f + g)

    /** var id → its 0-based index in [f], or `-1` when absent. Maps dirty-variable ids to
     *  channel rows during propagation and LS delta computation without an O(n) scan. */
    internal val fIndexOf: IntIntMap = IntIntMap.build(f, IntArray(f.size) { it }, absent = -1)

    /** var id → its 0-based index in [g], or `-1` when absent. Symmetric to [fIndexOf]. */
    internal val gIndexOf: IntIntMap = IntIntMap.build(g, IntArray(g.size) { it }, absent = -1)
}
