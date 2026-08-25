package com.eignex.klause.ir

import com.eignex.klause.util.EmptyIntArray

/**
 * The variables one factor reads, and what it needs from them.
 *
 * A factor holds indices, never domains: the same factor instance is shared across the bake, so it
 * outlives any phase in which a column's value set is known. What the *kind* states is the demand —
 * [IntVars] reasons over bounds, [SpanIntVars] must enumerate values — so the requirement lives in the
 * declaration instead of in a boolean the factor may never have considered.
 *
 * Each kind hands back the raw arrays it already holds. Composition is resolved once at construction
 * ([MixedVars]), never per access, so an occurrence scan over a large model allocates nothing.
 */
sealed interface VarList {
    /** Integer columns this factor reads, whatever it needs from them. */
    val ints: IntArray get() = EmptyIntArray

    /** Integer columns whose values this factor must be able to enumerate. */
    val spanInts: IntArray get() = EmptyIntArray

    /** Boolean columns this factor reads, as raw variable ids — not `Lit`-encoded. */
    val boolVars: IntArray get() = EmptyIntArray

    /** Real columns this factor reads. */
    val reals: IntArray get() = EmptyIntArray
}

/** No variables at all. */
data object NoVars : VarList

/** Integer columns read through their bounds. */
class IntVars(override val ints: IntArray) : VarList

/**
 * Integer columns whose values the factor enumerates — a table, a value graph, an all-different.
 * A model that cannot enumerate one of these columns cannot carry this factor.
 */
class SpanIntVars(override val ints: IntArray) : VarList {
    override val spanInts: IntArray get() = ints
}

/** Boolean columns, as raw variable ids. */
class BoolVars(override val boolVars: IntArray) : VarList

/** Real columns. */
class RealVars(override val reals: IntArray) : VarList

/**
 * Several kinds in one factor, so a role that enumerates and a role that only needs bounds can sit in
 * the same constraint — an `Element` indexes its selector while merely bounding its result.
 *
 * The combined integer array is built once here rather than on each read.
 */
class MixedVars(
    spanInts: IntArray = EmptyIntArray,
    boundInts: IntArray = EmptyIntArray,
    override val boolVars: IntArray = EmptyIntArray,
    override val reals: IntArray = EmptyIntArray,
) : VarList {
    override val spanInts: IntArray = spanInts
    override val ints: IntArray = if (spanInts.isEmpty()) {
        boundInts
    } else if (boundInts.isEmpty()) {
        spanInts
    } else {
        spanInts + boundInts
    }
}
