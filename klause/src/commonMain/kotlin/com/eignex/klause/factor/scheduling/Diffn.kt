package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.MutableIntIntMap

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
    val xs: IntArray,
    /** Second-vector variable ids. */
    val ys: IntArray,
    val widths: LongArray,
    val heights: LongArray,
    val widthVars: IntArray? = null,
    val heightVars: IntArray? = null,
    val nonStrict: Boolean = false,
) : Factor {

    /** Number of rectangles. */
    val n: Int = xs.size

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
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.DIFFN) {
        bool(nonStrict)
        longs(widths)
        longs(heights)
        ints(xs)
        ints(ys)
        ints(widthVars ?: EmptyIntArray)
        ints(heightVars ?: EmptyIntArray)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray =
        xs + ys + (widthVars ?: EmptyIntArray) + (heightVars ?: EmptyIntArray)

    /** Whether the search can resize rectangles (var dimensions present). */
    val varSize: Boolean = widthVars != null || heightVars != null

    /** var id → the single rectangle index it belongs to, or `-1` when the same id is shared
     *  by ≥2 distinct rectangles (a degenerate model). The affected-pair delta only touches
     *  the moved rectangle, which is exact iff the moved var maps to exactly one rectangle;
     *  a shared id forces the O(n²) full-recount fallback to preserve exact semantics. */
    // Var id → its rectangle index, or -1 when the id is shared across rectangles (the moved-rect
    // fast path can't apply). IntIntMap keeps the per-move lookup unboxed; -1 doubles as the
    // absent sentinel, which the read site already treats the same as a shared id (`r < 0`).
    private val varToRect: IntIntMap = run {
        val m = MutableIntIntMap(n * 2)
        fun record(v: Int, i: Int) {
            val prev = m.getOrDefault(v, Int.MIN_VALUE)
            m.put(v, if (prev == Int.MIN_VALUE || prev == i) i else -1)
        }
        for (i in 0 until n) {
            record(xs[i], i)
            record(ys[i], i)
            widthVars?.let { record(it[i], i) }
            heightVars?.let { record(it[i], i) }
        }
        val keys = IntArrayList(m.size)
        val values = IntArrayList(m.size)
        m.forEach { k, rect ->
            keys.add(k)
            values.add(rect)
        }
        IntIntMap.build(keys.toIntArray(), values.toIntArray(), absent = -1)
    }

    /** Index of the rectangle that owns [varId] (as an x, y, width, or height variable), or `-1`. */
    fun varToRectOf(varId: Int): Int = varToRect[varId]

    override fun asPropagator(): Propagator = DiffnPropagator(
        intVars = intVars,
        xs = xs,
        ys = ys,
        widths = widths,
        heights = heights,
        widthVars = widthVars,
        heightVars = heightVars,
        nonStrict = nonStrict,
        n = n,
        varSize = varSize,
    )

    override fun asInvariant(): Invariant = DiffnInvariant(
        xs = xs,
        ys = ys,
        widths = widths,
        heights = heights,
        widthVars = widthVars,
        heightVars = heightVars,
        nonStrict = nonStrict,
        n = n,
        varToRectOf = ::varToRectOf,
    )
}
