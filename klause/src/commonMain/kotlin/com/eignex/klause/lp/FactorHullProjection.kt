package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor

/** The optional convex-hull family emitted by this factor's LP relaxation. */
internal fun Factor.lpHullFamily(): HullFamily? = when (this) {
    is ArrayMinMax -> HullFamily.ARRAY_MIN_MAX
    is Element -> HullFamily.ELEMENT
    is GlobalCardinality -> HullFamily.GCC_COUNT
    is Mdd -> HullFamily.MDD
    is NValue -> HullFamily.NVALUE
    is Product -> HullFamily.PRODUCT
    is Regular -> HullFamily.REGULAR
    is Table -> HullFamily.TABLE
    else -> null
}

/** Whether this factor's optional LP hull is enabled for the current build. */
internal fun Factor.lpHullEnabled(flags: HullFlags): Boolean = lpHullFamily()?.let(flags::enabled) ?: true

/** The LP columns and rows this factor's optional hull can add over [boxes] — `null` where the build
 *  would emit no hull at all, an open-sided column included, so the gate never sizes a family the
 *  relaxation declines. */
internal fun Factor.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? = when (this) {
    is Element -> estimateLpHull(boxes)
    is GlobalCardinality -> estimateLpHull(boxes)
    is Mdd -> estimateLpHull(boxes)
    is NValue -> estimateLpHull(boxes)
    is Regular -> estimateLpHull(boxes)
    is Table -> estimateLpHull(boxes)
    else -> null
}
