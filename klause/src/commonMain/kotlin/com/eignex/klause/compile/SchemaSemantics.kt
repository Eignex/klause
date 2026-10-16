package com.eignex.klause.compile

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.KlauseConfig
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

internal fun schemaSemantics(config: KlauseConfig, overrides: SemanticOverrides): EffectiveSourceSemantics {
    val legacy = schemaSemantics.copy(
        floats = FloatSemantics.Grid(config.floatBuckets, config.floatScale),
        pinAbsentOptionalValues = config.pinAbsentOptVars,
    )
    val effective = legacy.resolve(overrides).effective
    val grid = effective.floats as? FloatSemantics.Grid
    require(grid == null || grid.buckets >= 2) { "schema float grids require at least two buckets" }
    require(grid == null || grid.linearScale == DEFAULT_FLOAT_SCALE) {
        "schema float-linear lowering requires the default scale"
    }
    return EffectiveSourceSemantics(schemaSemantics, effective)
}
