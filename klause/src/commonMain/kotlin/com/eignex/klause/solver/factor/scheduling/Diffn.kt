package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.util.IntIntMap

/**
 * `diffn(xs, ys, widths, heights)` — pairwise non-overlapping 2D rectangles. Each rectangle
 * `i` has lower-left corner `(xs(i), ys(i))` and dimensions `(widths(i), heights(i))`.
 * Two rectangles non-overlap iff they're disjoint on at least one axis.
 *
 * Sizes may be **constant or variable**. When [widthVars] / [heightVars] are non-null the
 * dimensions are themselves variables (read from the assignment, and part of [intVars] so the
 * search may resize as well as move); otherwise the constant [widths] / [heights] are used.
 * The two axes are independent — e.g. constant widths with variable heights is allowed.
 *
 * [nonStrict] = true treats zero-width / zero-height rectangles as never overlapping
 * (the `diffn_nonstrict` variant). With [nonStrict] = false (the default), even zero-
 * dimensional rectangles must satisfy the non-overlap criterion.
 */
class Diffn(
    /** Variable ids the constraint ranges over. */
    override val xs: IntArray,
    /** Second-vector variable ids. */
    override val ys: IntArray,
    override val widths: IntArray,
    override val heights: IntArray,
    override val widthVars: IntArray? = null,
    override val heightVars: IntArray? = null,
    override val nonStrict: Boolean = false,
) : Factor,
    DiffnPropagator,
    DiffnInvariant {

    override val n: Int = xs.size

    init {
        require(xs.size == ys.size) { "diffn: xs/ys size mismatch" }
        require(widthVars != null || xs.size == widths.size) { "diffn: xs/widths size mismatch" }
        require(heightVars != null || xs.size == heights.size) { "diffn: xs/heights size mismatch" }
        require(widthVars == null || widthVars.size == n) { "diffn: widthVars size mismatch" }
        require(heightVars == null || heightVars.size == n) { "diffn: heightVars size mismatch" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Diffn(
        xs.remapVars(intMap),
        ys.remapVars(intMap),
        widths,
        heights,
        widthVars?.remapVars(intMap),
        heightVars?.remapVars(intMap),
        nonStrict,
    )

    /** Position-faithful (rectangle i is fixed by index): keeps the coordinate arrays in order and
     *  folds in the constant sizes, the var-size split, and the [nonStrict] flag (#531). */
    override fun structuralKey(): String {
        val wv = widthVars?.joinToString(",").orEmpty()
        val hv = heightVars?.joinToString(",").orEmpty()
        return "diffn:$nonStrict:${widths.joinToString(",")}:${heights.joinToString(",")}:" +
            "${xs.joinToString(",")}:${ys.joinToString(",")}:$wv:$hv"
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        xs + ys + (widthVars ?: EmptyIntArray) + (heightVars ?: EmptyIntArray)

    /**
     * Advisor subscription (#623): non-overlap propagation reads only each coordinate/size variable's
     * `min`/`max` (the "must-overlap on one axis ⇒ separate on the other" reasoning is pure interval
     * arithmetic). An interior hole moves no bound, so it subscribes to [IntEvent.LB_RAISED] /
     * [IntEvent.UB_LOWERED] per variable and skips interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = IntEvent.boundEventWatches(intVars)

    /** Whether the search can resize rectangles (var dimensions present). */
    override val varSize: Boolean = widthVars != null || heightVars != null

    /** var id → the single rectangle index it belongs to, or `-1` when the same id is shared
     *  by ≥2 distinct rectangles (a degenerate model). The affected-pair delta only touches
     *  the moved rectangle, which is exact iff the moved var maps to exactly one rectangle;
     *  a shared id forces the O(n²) full-recount fallback to preserve exact semantics. */
    // Var id → its rectangle index, or -1 when the id is shared across rectangles (the moved-rect
    // fast path can't apply). IntIntMap keeps the per-move lookup unboxed; -1 doubles as the
    // absent sentinel, which the read site already treats the same as a shared id (`r < 0`).
    private val varToRect: IntIntMap = run {
        val m = HashMap<Int, Int>(n * 2)
        fun record(v: Int, i: Int) {
            val prev = m[v]
            m[v] = if (prev == null || prev == i) i else -1
        }
        for (i in 0 until n) {
            record(xs[i], i)
            record(ys[i], i)
            widthVars?.let { record(it[i], i) }
            heightVars?.let { record(it[i], i) }
        }
        val keys = m.keys.toIntArray()
        IntIntMap.build(keys, IntArray(keys.size) { m.getValue(keys[it]) }, absent = -1)
    }

    override fun varToRectOf(varId: Int): Int = varToRect[varId]
}
