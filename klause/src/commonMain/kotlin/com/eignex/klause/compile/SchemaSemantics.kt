package com.eignex.klause.compile

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics

/** Schema declaration defaults, independent of resource and execution policy. */
val schemaSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.SCHEMA,
    numericAuthority = NumericAuthority.BINARY64_LITERALS,
    floats = FloatSemantics.Grid(DEFAULT_FLOAT_BUCKETS, DEFAULT_FLOAT_SCALE),
    pinAbsentOptionalValues = true,
)

internal fun schemaSemantics(overrides: SemanticOverrides): EffectiveSourceSemantics {
    val resolved = schemaSemantics.resolve(overrides)
    val effective = resolved.effective
    val grid = effective.floats as? FloatSemantics.Grid
    require(grid == null || grid.buckets >= 2) { "schema float grids require at least two buckets" }
    if (grid != null) {
        val scale = grid.linearScale.toDouble()
        require(scale < -Long.MIN_VALUE.toDouble() && scale.toLong() == grid.linearScale) {
            "schema float-linear scale cannot be represented exactly in binary64"
        }
    }
    return resolved
}
