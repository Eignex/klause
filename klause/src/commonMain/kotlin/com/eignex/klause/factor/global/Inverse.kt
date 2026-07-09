package com.eignex.klause.factor.global

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntIntMap

/**
 * `inverse(f, g)` with optional offsets: `f(i) = j  ⇔  g(j - gOffset + fOffset) = i`.
 *
 * The canonical 0-based form is `f(i) = j ⇔ g(j) = i`. MiniZinc emits with index offsets
 * matching its 1-based default; the offsets are encoded into the factor so the dispatch
 * doesn't have to allocate channel vars.
 *
 * Propagation: pin-forcing channels — whenever `f(i)` becomes singleton with value `j`,
 * force `g(j')` to `i'` where the indices apply the offset; vice versa.
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

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Inverse(f.remapVars(intMap), g.remapVars(intMap), fOffset, gOffset)

    // Positional: f(i)/g(i) are channelled by index, so neither array is sorted. Encodes both
    // offsets and the ordered f / g var sequences — fine enough that two non-equivalent Inverses
    // never share a key (required for sound symmetry verification). The f/g sides are kept distinct
    // (not canonicalised against each other); at worst this misses an f↔g symmetry, never unsound.
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.INVERSE) {
        int(fOffset)
        int(gOffset)
        ints(f)
        ints(g)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = f + g

    /** var id → its 0-based index in [f], or `-1` when absent. Maps dirty-variable ids to
     *  channel rows during propagation and LS delta computation without an O(n) scan. */
    internal val fIndexOf: IntIntMap = IntIntMap.build(f, IntArray(f.size) { it }, absent = -1)

    /** var id → its 0-based index in [g], or `-1` when absent. Symmetric to [fIndexOf]. */
    internal val gIndexOf: IntIntMap = IntIntMap.build(g, IntArray(g.size) { it }, absent = -1)

    /*
     * GAC for the inverse channel. Three layers:
     *   1. range-tighten each f[i] / g[j] to the legal index span and force singletons across
     *      the channel;
     *   2. bidirectional value removal — if `i + fOffset` is absent from `dom(g[j])`, also remove
     *      `j + gOffset` from `dom(f[i])`, and symmetrically. This is the arc-consistent closure of
     *      `f[i]=j ⇔ g[j]=i`;
     *   3. Hall/matching filtering on f and on g (#541). The biconditional forces f and g to be
     *      bijections (`f[i1]=f[i2]=j ⇒ g[j]=i1=i2`), so each side is all-different; the channel AC
     *      alone reaches a mutual non-GAC fixpoint (e.g. it keeps `f2=0` because `g0=2` is unpruned
     *      and vice versa). Régin matching on f and on g punches the Hall-set values the channel
     *      misses, reusing the shared `reginFilter`.
     */

    override fun asPropagator(): Propagator = InversePropagator(
        boolVars,
        intVars,
        f,
        g,
        fOffset,
        gOffset,
        fIndexOf,
        gIndexOf,
    )

    override fun asInvariant(): Invariant = InverseInvariant(f, g, fOffset, gOffset)
}
