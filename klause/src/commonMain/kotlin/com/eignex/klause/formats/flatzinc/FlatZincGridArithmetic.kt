package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.GridCoordinate

internal fun FlatZincCompiler.gridArithmeticCoordinate(ref: FloatRef, auxiliary: String): GridCoordinate {
    val coordinate = when (ref) {
        is FloatRef.Var -> ref.bk
        is FloatRef.Const -> resolveFloatElement(FznExpr.FloatLit(ref.value),
            "${auxiliary}_${floatVars.size}", lpOnly = false)
    }
    if (coordinate.lpOnly) unsupportedHere("grid arithmetic cannot mix exact and grid coordinates")
    if (!coordinate.lo.isFinite() || !coordinate.hi.isFinite() || !(coordinate.hi - coordinate.lo).isFinite()) {
        unsupportedHere("grid arithmetic requires a finite binary64 range")
    }
    return GridCoordinate(coordinate.varId, coordinate.lo, coordinate.hi, coordinate.buckets)
}
