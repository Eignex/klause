package com.eignex.klause.compile

/**
 * Compiler-side layout for a set variable. Mirrors FlatZinc's `SetVarLayout`: one bool
 * per universe element, indexed in parallel arrays.
 *
 *  - `universe[i]` is the integer value of the i'th universe element. For nominal-set
 *    vars the value is the label's index in [Lowering.setLabelOrder] — the actual
 *    label string lives there.
 *  - `indicatorBoolIds[i]` is the klause-side Bool var id that's true iff `universe[i]`
 *    is currently a member of the set.
 *
 * Anything that walks a [com.eignex.klause.model.SetExpr] eventually produces one of these,
 * either by looking up an existing set var via [Lowering.materializeSet] or by
 * synthesising aux indicators for `union` / `intersect` / `diff` / set literals.
 */
internal class SetLayout(val universe: IntArray, val indicatorBoolIds: IntArray) {
    init {
        require(universe.size == indicatorBoolIds.size) {
            "SetLayout parallel arrays must have equal length"
        }
    }
    val size: Int get() = universe.size

    /** Index of [value] in this layout's universe, or -1 if not present. Universe is
     *  sorted ascending so a binary search is correct. */
    fun indexOf(value: Int): Int = universe.toList().binarySearch(value).let { if (it < 0) -1 else it }
}
