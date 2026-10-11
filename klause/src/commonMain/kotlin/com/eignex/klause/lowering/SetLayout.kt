package com.eignex.klause.lowering

import com.eignex.klause.util.binarySearchInt

internal class SetLayout(val universe: IntArray, val indicatorBoolIds: IntArray) {
    init {
        require(universe.size == indicatorBoolIds.size) { "SetLayout parallel arrays must have equal length" }
    }
    val size: Int get() = universe.size
    fun indexOf(value: Int): Int = universe.binarySearchInt(value).let { if (it < 0) -1 else it }
}
