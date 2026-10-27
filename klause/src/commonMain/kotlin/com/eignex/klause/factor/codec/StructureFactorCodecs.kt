package com.eignex.klause.factor.codec

import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import kotlinx.serialization.Serializable

/**
 * Tasks with constant [durations] and [resources]; a task whose duration, demand or the capacity is a
 * column also names it in [durationVars], [resourceVars] or [capacityVar].
 */
@Serializable
internal class CumulativePayload(
    val starts: IntArray,
    val durations: LongArray,
    val resources: LongArray,
    val capacity: Long,
    val presents: IntArray? = null,
    val durationVars: IntArray? = null,
    val resourceVars: IntArray? = null,
    val capacityVar: Int? = null,
)

@Serializable
internal class DiffnPayload(
    val xs: IntArray,
    val ys: IntArray,
    val widths: LongArray,
    val heights: LongArray,
    val widthVars: IntArray? = null,
    val heightVars: IntArray? = null,
    val nonStrict: Boolean = false,
)

/** `result = array(index - indexOffset)` over constant [values] or column [vars]; exactly one is present. */
@Serializable
internal class ElementPayload(
    val index: Int,
    val result: Int,
    val values: LongArray? = null,
    val vars: IntArray? = null,
    val indexOffset: Int = 1,
)

/** [ElementPayload] with binary64 constants or real column [vars]. */
@Serializable
internal class RealElementPayload(
    val index: Int,
    val result: Int,
    val values: DoubleArray? = null,
    val vars: IntArray? = null,
    val indexOffset: Int = 1,
)

/** Allowed rows; [upper], when present, turns each cell into the range `tuples..upper`. */
@Serializable
internal class TablePayload(val vars: IntArray, val tuples: List<LongArray>, val upper: List<LongArray>? = null)

@Serializable
internal class RegularPayload(
    val seq: IntArray,
    val states: Int,
    val alphabet: Int,
    val transitions: LongArray,
    val initial: Int,
    val accepting: IntArray,
)

@Serializable
internal class MddPayload(
    val seq: IntArray,
    val statesPerLayer: IntArray,
    val layerStarts: IntArray,
    val transitions: LongArray,
    val initial: Int,
    val accepting: IntArray,
    val recordStride: Int,
    val cost: Int? = null,
)

/** One generator: the image of every integer column and of every Boolean column. */
@Serializable
internal class SymmetryGeneratorPayload(val ints: IntArray, val bools: IntArray)

@Serializable
internal class SymmetryHandlingPayload(val generators: List<SymmetryGeneratorPayload>)

private fun rows(flat: LongArray, arity: Int): List<LongArray> =
    List(flat.size / arity) { row -> flat.copyOfRange(row * arity, (row + 1) * arity) }

private fun flatten(rows: List<LongArray>, arity: Int): LongArray {
    require(rows.all { it.size == arity }) { "every row must have one cell per variable" }
    val flat = LongArray(rows.size * arity)
    rows.forEachIndexed { row, cells -> cells.copyInto(flat, row * arity) }
    return flat
}

private fun ElementPayload.toElement(): Element {
    require((values == null) != (vars == null)) { "state exactly one of 'values' and 'vars'" }
    return if (vars != null) {
        Element(index, result, LongArray(vars.size) { vars[it].toLong() }, true, indexOffset)
    } else {
        Element(index, result, requireNotNull(values), false, indexOffset)
    }
}

private fun RealElementPayload.toElement(): RealElement {
    require((values == null) != (vars == null)) { "state exactly one of 'values' and 'vars'" }
    return if (vars != null) {
        RealElement(index, result, vars, indexOffset)
    } else {
        RealElement(index, result, requireNotNull(values), indexOffset)
    }
}

internal fun structureFactorCodecs(): List<FactorCodec<*, *>> = listOf(
    factorCodec<Cumulative, CumulativePayload>(
        "cumulative",
        {
            CumulativePayload(
                it.starts, it.durations, it.resources, it.capacity,
                it.presents.takeIf(IntArray::isNotEmpty),
                it.durationVars.takeIf(IntArray::isNotEmpty),
                it.resourceVars.takeIf(IntArray::isNotEmpty),
                it.capacityVar.takeIf { v -> v >= 0 },
            )
        },
        {
            Cumulative(
                it.starts, it.durations, it.resources, it.capacity,
                it.presents ?: IntArray(0),
                it.durationVars ?: IntArray(0),
                it.resourceVars ?: IntArray(0),
                it.capacityVar ?: -1,
            )
        },
    ),
    factorCodec<Diffn, DiffnPayload>(
        "diffn",
        { DiffnPayload(it.xs, it.ys, it.widths, it.heights, it.widthVars, it.heightVars, it.nonStrict) },
        { Diffn(it.xs, it.ys, it.widths, it.heights, it.widthVars, it.heightVars, it.nonStrict) },
    ),
    factorCodec<Element, ElementPayload>(
        "element",
        { e ->
            if (e.arrIsVars) {
                val vars = IntArray(e.arr.size) { e.arr[it].toInt() }
                ElementPayload(e.idx, e.result, vars = vars, indexOffset = e.indexOffset)
            } else {
                ElementPayload(e.idx, e.result, values = e.arr, indexOffset = e.indexOffset)
            }
        },
        { it.toElement() },
    ),
    factorCodec<RealElement, RealElementPayload>(
        "realElement",
        { e ->
            if (e.arrIsVars) {
                RealElementPayload(e.idx, e.result, vars = e.entries, indexOffset = e.indexOffset)
            } else {
                RealElementPayload(e.idx, e.result, values = e.constants, indexOffset = e.indexOffset)
            }
        },
        { it.toElement() },
    ),
    factorCodec<Table, TablePayload>(
        "table",
        { t -> TablePayload(t.xs, rows(t.tuples, t.arity), t.hi?.let { rows(it, t.arity) }) },
        { p -> Table(p.vars, flatten(p.tuples, p.vars.size), p.upper?.let { flatten(it, p.vars.size) }) },
    ),
    factorCodec<Regular, RegularPayload>(
        "regular",
        { RegularPayload(it.seq, it.numStates, it.alphabetSize, it.transitions, it.q0, it.accepting) },
        { Regular(it.seq, it.states, it.alphabet, it.transitions, it.initial, it.accepting) },
    ),
    factorCodec<Mdd, MddPayload>(
        "mdd",
        {
            MddPayload(
                it.seq, it.numStatesPerLayer, it.layerStarts, it.transitions, it.initial, it.accepting,
                it.recordStride, it.cost.takeIf { cost -> cost >= 0 },
            )
        },
        {
            Mdd(
                it.seq, it.statesPerLayer, it.layerStarts, it.transitions, it.initial, it.accepting,
                it.recordStride, it.cost ?: -1,
            )
        },
    ),
    factorCodec<SymmetryHandling, SymmetryHandlingPayload>(
        "symmetryHandling",
        { s -> SymmetryHandlingPayload(s.generators.map { (ints, bools) -> SymmetryGeneratorPayload(ints, bools) }) },
        { p -> SymmetryHandling(p.generators.map { it.ints to it.bools }) },
    ),
)
