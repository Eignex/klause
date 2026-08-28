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
import com.eignex.klause.ir.IntDomain

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

/** The LP columns and rows this factor's optional hull can add under [domains]. */
internal fun Factor.estimateLpHull(domains: Array<IntDomain>): LpSizeEstimate? = when (this) {
    is Element -> estimateLpHull(domains)
    is GlobalCardinality -> estimateLpHull(domains)
    is Mdd -> estimateLpHull(domains)
    is NValue -> estimateLpHull(domains)
    is Regular -> estimateLpHull(domains)
    is Table -> estimateLpHull()
    else -> null
}
