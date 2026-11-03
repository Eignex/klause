package com.eignex.klause.lp.bounding

internal class RootPrimalTargets(
    val intColumns: IntArray,
    val booleanColumns: IntArray,
    val coordinates: DoubleArray,
    val knownColumns: BooleanArray?,
    val auxiliary: Boolean = false,
) {
    var valueOrderOffered: Boolean = false
    var replayed: Boolean = false
    var replayOffered: Boolean = false
    var componentOrderAttempted: Boolean = false
    var components: RootComponentOrder? = null
}

internal fun rootComponentValueBytes(variables: Int): Long =
    128L + variables.toLong() * (Long.SIZE_BYTES + 1L)

internal class RootComponentValues(variables: Int) {
    val values = LongArray(variables)
    val known = BooleanArray(variables)
    var offered: Boolean = false
    var restarted: Boolean = false
    var restartOffered: Boolean = false
    val retainedBytes: Long get() = rootComponentValueBytes(values.size)
}

internal class RootComponentRounding(val order: RootComponentOrder) {
    var group: Int = 0
    var level: Int = 0
    var rounding: LpRounding = LpRounding.NEAREST

    fun reset() {
        group = 0
        level = 0
        rounding = LpRounding.NEAREST
    }
}

internal class RootComponentOrder(val offsets: IntArray, val members: IntArray, val groups: Int) {
    var offered: Boolean = false
    val retainedBytes: Long get() = 128L + (offsets.size.toLong() + members.size) * Int.SIZE_BYTES
}
