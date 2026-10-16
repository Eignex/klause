package com.eignex.klause.formats.mps

import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf

/** Decimal source authority and independently configurable absolute and relative feasibility slack. */
val mpsSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.MPS,
    numericAuthority = NumericAuthority.DECIMAL_LITERALS,
    feasibilityTolerance = SourceTolerance(
        absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
        relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
    ),
)
