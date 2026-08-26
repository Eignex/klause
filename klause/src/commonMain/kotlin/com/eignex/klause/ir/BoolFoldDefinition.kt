package com.eignex.klause.ir

/**
 * An immutable boolean definition `out ↔ ⋀ lits` (or `⋁` when [isAnd] is false).
 *
 * Lowering produces these definitions for auxiliaries such as Tseitin product indicators. Engines
 * may use them to avoid searching values that are determined by their inputs.
 */
class BoolFoldDefinition(
    /** The defined boolean variable id. */
    val out: Int,
    /** [Lit]-encoded member literals; negated members are valid. */
    val lits: IntArray,
    /** `true` for conjunction, `false` for disjunction. */
    val isAnd: Boolean,
)
