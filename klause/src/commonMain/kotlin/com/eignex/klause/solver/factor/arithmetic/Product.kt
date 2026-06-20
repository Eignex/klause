package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `a * b = result`. Operates on signed integer domains (any min/max). The bit-blaster lowers
 * via an unsigned shift-and-add multiplier on absolute values, then conditionally negates the
 * product based on the operand sign bits.
 *
 * No payload: the product is recomputed in O(1) from the current assignment on each query.
 */
class Product(
    /** First factor variable id. */
    override val a: Int,
    /** Second factor variable id. */
    override val b: Int,
    /** Result variable id (`result = a * b`). */
    override val result: Int,
) : Factor,
    ProductPropagator,
    ProductInvariant {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Product(intMap[a], intMap[b], intMap[result])

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = intArrayOf(a, b, result)

    /**
     * Advisor subscription (#623): `propagate` derives everything from the corner products and
     * corner divisions of the `[min, max]` intervals of `a`, `b`, and `result` — it reads only
     * `min`/`max` (the zero-exclusion step also tests `0 in min..max`, an interval check, and by
     * design only acts on endpoints). An interior hole can change none of those, so the factor
     * subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] on each variable and skips interior
     * `VALUE_REMOVED` wakes. Deduplicated so an aliased operand (e.g. `a == b` for a square) is
     * subscribed once.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)
}
