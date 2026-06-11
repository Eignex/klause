package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Lit

/**
 * Helpers for [com.eignex.klause.solver.Factor.remap] implementations. `map[oldId] = newId`.
 *
 * Use [remapVars] for arrays of raw variable ids and [remapLits] for arrays of MiniSAT-encoded
 * literals (the variable id is remapped, the polarity preserved). Constants — coefficients,
 * domain offsets, DFA/table data, sentinels — must be passed through untouched, never through a map.
 */
internal fun IntArray.remapVars(map: IntArray): IntArray = IntArray(size) { map[this[it]] }

/** Rewrite each literal's variable id through [map], keeping its polarity. */
internal fun IntArray.remapLits(map: IntArray): IntArray =
    IntArray(size) { Lit.make(map[Lit.variable(this[it])], Lit.isPositive(this[it])) }
