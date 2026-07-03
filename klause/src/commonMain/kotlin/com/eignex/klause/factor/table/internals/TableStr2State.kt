package com.eignex.klause.factor.table.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt

/** STR2 sparse-set propagation state for the Table constraint. */
internal class TableStr2State(val validTuples: IntArray, numValidInit: Int, state: PropagationState) {
    var started: Boolean = false
    private val numValidCell = RevInt(state, numValidInit)
    var numValid: Int
        get() = numValidCell.value
        set(value) = numValidCell.set(value)
}
