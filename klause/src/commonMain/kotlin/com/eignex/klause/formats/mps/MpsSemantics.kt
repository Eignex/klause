package com.eignex.klause.formats.mps

import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf

/** Decimal authority, unranged declaration bounds and independently configurable source feasibility slack. */
val mpsSemantics: SourceSemantics = SourceSemantics(
    format = SourceFormat.MPS,
    numericAuthority = NumericAuthority.DECIMAL_LITERALS,
    undeclaredIntegers = UndeclaredIntegerBounds(0, 1),
    undeclaredReals = UndeclaredRealBounds(SemanticRational.ZERO, null),
    feasibilityTolerance = SourceTolerance(
        absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
        relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000L)),
    ),
)
