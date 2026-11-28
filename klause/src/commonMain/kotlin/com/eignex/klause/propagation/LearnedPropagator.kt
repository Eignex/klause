package com.eignex.klause.propagation

import com.eignex.util.arrays.emptyIntArray

// A stored learned constraint exposes its variable footprint, which conflict-factor extraction walks to trace
// causation. Naming the contract lets the learned database hold several constraint kinds over one set of policy
// columns.
internal interface LearnedPropagator : Propagator {
    val boolVars: IntArray

    val intVars: IntArray
}

internal object RetiredLearnedPropagator : LearnedPropagator {
    override val boolVars: IntArray get() = emptyIntArray()
    override val intVars: IntArray get() = emptyIntArray()
}
