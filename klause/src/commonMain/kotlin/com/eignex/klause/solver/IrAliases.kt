package com.eignex.klause.solver

/**
 * Compatibility names for the neutral constraint representation.
 *
 * New code should import these declarations from `com.eignex.klause.ir`. Keeping the aliases while
 * the engine packages migrate avoids a source-incompatible package move for library consumers.
 */
typealias Lit = com.eignex.klause.ir.Lit
typealias VarList = com.eignex.klause.ir.VarList
typealias NoVars = com.eignex.klause.ir.NoVars
typealias IntVars = com.eignex.klause.ir.IntVars
typealias SpanIntVars = com.eignex.klause.ir.SpanIntVars
typealias BoolVars = com.eignex.klause.ir.BoolVars
typealias RealVars = com.eignex.klause.ir.RealVars
typealias MixedVars = com.eignex.klause.ir.MixedVars
typealias VarRemap = com.eignex.klause.ir.VarRemap
typealias StructuralKey = com.eignex.klause.ir.StructuralKey
internal typealias StructuralKeyBuilder = com.eignex.klause.ir.StructuralKeyBuilder
internal typealias FactorKind = com.eignex.klause.ir.FactorKind
internal typealias KeySink = com.eignex.klause.ir.KeySink

internal fun materializeKey(kind: FactorKind, build: (KeySink) -> Unit): StructuralKey =
    com.eignex.klause.ir.materializeKey(kind, build)

internal fun materializeKey(kind: FactorKind, expectedWords: Int, build: (KeySink) -> Unit): StructuralKey =
    com.eignex.klause.ir.materializeKey(kind, expectedWords, build)

internal fun hashRemappedKey(kind: FactorKind, mapping: VarRemap, build: (KeySink) -> Unit): Int =
    com.eignex.klause.ir.hashRemappedKey(kind, mapping, build)
