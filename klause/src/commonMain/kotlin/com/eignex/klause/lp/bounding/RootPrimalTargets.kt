package com.eignex.klause.lp.bounding

internal class RootPrimalTargets(
    val intColumns: IntArray,
    val booleanColumns: IntArray,
    val coordinates: DoubleArray,
    val knownColumns: BooleanArray?,
) {
    var valueOrderOffered: Boolean = false
}
