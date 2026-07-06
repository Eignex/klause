package com.eignex.klause.util

/** Shared singleton empty `IntArray`. Factors with no variables in one of the two var spaces
 *  (purely-Boolean ones leave `Factor.intVars` empty; purely-integer ones leave `Factor.boolVars`
 *  empty), and any other zero-length int slot, wire this in instead of allocating a per-use empty array. */
internal val EmptyIntArray: IntArray = IntArray(0)

/** Shared singleton empty `LongArray`, for scratch slots that some code paths leave unused (e.g. the
 *  wide-only term-contribution snapshot in `propagateLinearBounds`) so the common path binds this
 *  instead of allocating. */
internal val EmptyLongArray: LongArray = LongArray(0)

/** Shared singleton empty `BooleanArray`, for the empty-occupancy / no-flags slots that would
 *  otherwise allocate a fresh zero-length array per use. */
internal val EmptyBooleanArray: BooleanArray = BooleanArray(0)
