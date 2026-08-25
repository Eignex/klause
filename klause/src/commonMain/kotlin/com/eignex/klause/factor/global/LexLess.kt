package com.eignex.klause.factor.global

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.SpanIntVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

/**
 * `lex_less(xs, ys)` / `lex_lesseq(xs, ys)` — lexicographic ordering on equal-length int
 * vectors. [strict] = `true` for strict less-than, `false` for less-or-equal.
 *
 *  - Strict: `xs <ₗₑₓ ys`  iff  there exists `k` with `xs`k` < ys`k`` and `xs`i` = ys`i``
 *    for all `i < k`.
 *  - Non-strict: `xs ≤ₗₑₓ ys`  iff  the strict version holds *or* `xs`i` = ys`i`` for all `i`.
 *
 * If `xs.size != ys.size` the shorter array is treated as a prefix: a proper prefix
 * compares strictly less than the longer one (MiniZinc semantics).
 *
 * LS recomputes the relation on each query.
 */
class LexLess(
    /** Left vector variable ids. */
    val xs: IntArray,
    /** Right vector variable ids, parallel to [xs]. */
    val ys: IntArray,
    /** When true the relation is strict (`xs < ys`); otherwise `xs ≤ ys`. */
    val strict: Boolean,
) : Factor {

    override fun remap(mapping: VarRemap): Factor = LexLess(mapping.ints(xs), mapping.ints(ys), strict)

    /** Lexicographic order is position-faithful and asymmetric, so [xs] and [ys] are both positional
     *  and kept in their roles; [strict] distinguishes `<` from `≤`. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.LEX_LESS, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.LEX_LESS, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.bool(strict)
        sink.intVars(xs)
        sink.intVars(ys)
    }

    override val variables: VarList = SpanIntVars(xs + ys)

    override fun asPropagator(): Propagator = LexLessPropagator(boolVars, intVars, xs, ys, strict)

    override fun asInvariant(): Invariant = LexLessInvariant(xs, ys, strict)
}
