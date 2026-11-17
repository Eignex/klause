package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyLinear
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableLongIntMap

// XCSP3 global-constraint family emitters.

internal fun Compiler.Builder.count(e: XmlElement) {
    val vars = listVars(e)
    val values = parseInts(e.child("values")?.textContent)
        ?: throw UnsupportedXcsp3Exception("count: only constant <values> supported")
    if (values.size != 1) throw UnsupportedXcsp3Exception("count: only a single value supported")
    val condition = requireNotNull(e.child("condition")).textContent.trim()
    val target = equalityTarget(condition)?.takeIf { it !in vars }
    val cnt = target ?: newAuxVar(0L, vars.size.toLong())
    val channels = IntArray(vars.size) { i ->
        val x = vars[i]
        val domain = domains[x]
        if (values[0] == 1 && domain.min >= 0L && domain.max <= 1L) x else eqValue01(x, values[0].toLong())
    }
    val sumCoeffs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
    val sumVars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else cnt }
    factors.add(Linear(sumCoeffs, sumVars, LinearOp.EQ, 0))
    definedVars.add(cnt)
    defineLinear(cnt, sumCoeffs.widenToLong(), sumVars, 0L)
    if (target == null) postCondition(intArrayOf(1), intArrayOf(cnt), condition)
}

internal fun Compiler.Builder.element(e: XmlElement) {
    e.child("matrix")?.let { return elementMatrix(e, it) }
    val offset = selectionOffset(e)
    requireAnySelectionRank(e)
    val idx = singleTermVar(
        e.child("index")?.textContent
            ?: throw UnsupportedXcsp3Exception("element: missing <index>"),
    )
    // A `<list>` of only integer constants is a constant table: build a constant-array [Element]
    // (arrIsVars = false) so GAC takes the incremental support-count path ([ElementConstState]).
    // Treating each constant as a singleton fixed var instead sends it down the variable-array path,
    // which scans the whole result domain for support and prunes one value at a time — quadratic when
    // the result domain is wide.
    val constArr = parseInts(requireNotNull(e.child("list")).textContent)?.widenToLong()
    val vars = if (constArr == null) listVars(e) else null
    val arr = constArr ?: requireNotNull(vars).widenToLong()
    val arrIsVars = constArr == null
    // The selected element arr[idx] is constrained either directly to a <value> (the eq form)
    // or by a <condition> `(op, operand)` on a fresh var bound to it.
    val condEl = e.child("condition")
    if (condEl != null) {
        val lo: Long
        val hi: Long
        if (constArr != null) {
            lo = constArr.min()
            hi = constArr.max()
        } else {
            val v = requireNotNull(vars)
            lo = domainMin(v)
            hi = domainMin(v) + domainSpan(v) - 1
        }
        val selected = newAuxVar(lo, hi)
        definedVars.add(selected)
        factors.add(
            Element(idx = idx, result = selected, arr = arr, arrIsVars = arrIsVars, indexOffset = offset),
        )
        postCondition(intArrayOf(1), intArrayOf(selected), condEl.textContent.trim())
        return
    }
    val value = e.child("value")?.textContent?.trim()
        ?: throw UnsupportedXcsp3Exception("element: missing <value> or <condition>")
    val result = singleTermVar(value)
    definedVars.add(result)
    factors.add(
        Element(idx = idx, result = result, arr = arr, arrIsVars = arrIsVars, indexOffset = offset),
    )
}

@Suppress("ThrowsCount") // one guard per unsupported shape (bad index, empty constant/variable matrix)
internal fun Compiler.Builder.elementMatrix(e: XmlElement, matrix: XmlElement) {
    requireAnySelectionRank(e)
    // Matrix element uses per-axis start indices (defaulting to 0), not a single startIndex.
    val rowOffset = e.attr("startRowIndex").ifBlank { "0" }.toInt()
    val colOffset = e.attr("startColIndex").ifBlank { "0" }.toInt()
    val idxTokens = requireNotNull(e.child("index")).textContent.splitWs()
    if (idxTokens.size != 2) throw UnsupportedXcsp3Exception("element: matrix needs a 2-D <index>")
    val i = singleTermVar(idxTokens[0])
    val j = singleTermVar(idxTokens[1])
    val v = singleTermVar(requireNotNull(e.child("value")).textContent)

    val constRows = constMatrixRows(matrix.textContent)
    if (constRows != null) {
        if (constRows.isEmpty()) throw UnsupportedXcsp3Exception("element: empty <matrix>")
        elementConstantMatrix(constRows, i, j, v, rowOffset, colOffset)
        return
    }
    val rows = matrixRows(matrix.textContent)
    if (rows.isEmpty()) throw UnsupportedXcsp3Exception("element: empty <matrix>")
    elementVarMatrix(rows, i, j, v, rowOffset, colOffset)
}

/** The rows of a constant integer `<matrix>` (`(1,2)(3,4)`), or null when it is not an all-constant
 *  parenthesised matrix (an array reference, or one with variable entries — handled elsewhere). */
internal fun Compiler.Builder.constMatrixRows(text: String): List<IntArray>? {
    if ('(' !in text) return null
    val rows = ArrayList<IntArray>()
    val cur = IntArrayList()
    forEachTuple(
        text,
        cell = { cur.add(it.toIntOrNull() ?: return null) },
        endRow = {
            rows.add(cur.toIntArray())
            cur.clear()
        },
    )
    return rows
}

// Separate axis bounds prevent an out-of-range column from aliasing a valid flattened cell.
internal fun Compiler.Builder.elementVarMatrix(
    rows: List<IntArray>,
    i: Int,
    j: Int,
    v: Int,
    rowOff: Int,
    colOff: Int,
) {
    val nCols = rows[0].size
    require(nCols > 0 && rows.all { it.size == nCols }) { "element: empty or ragged <matrix>" }
    val cells = rows.size.toLong() * nCols
    require(cells <= Int.MAX_VALUE) { "element: matrix is too large" }
    if (domains[i].min < rowOff) factors.add(Linear(longArrayOf(1L), intArrayOf(i), LinearOp.GE, rowOff.toLong()))
    if (domains[i].max > rowOff.toLong() + rows.size - 1L) {
        factors.add(Linear(longArrayOf(1L), intArrayOf(i), LinearOp.LE, rowOff.toLong() + rows.size - 1L))
    }
    if (domains[j].min < colOff) factors.add(Linear(longArrayOf(1L), intArrayOf(j), LinearOp.GE, colOff.toLong()))
    if (domains[j].max > colOff.toLong() + nCols - 1L) {
        factors.add(Linear(longArrayOf(1L), intArrayOf(j), LinearOp.LE, colOff.toLong() + nCols - 1L))
    }
    val index = newAuxVar(0L, cells - 1L)
    definedVars.add(index)
    definedVars.add(v)
    factors.add(
        Linear(
            longArrayOf(nCols.toLong(), 1L, -1L), intArrayOf(i, j, index),
            LinearOp.EQ, nCols.toLong() * rowOff + colOff,
        ),
    )
    factors.add(
        Element(
            idx = index, result = v,
            arr = LongArray(cells.toInt()) { rows[it / nCols][it % nCols].toLong() },
            arrIsVars = true, indexOffset = 0,
        ),
    )
}

internal fun Compiler.Builder.channel(e: XmlElement) {
    val lists = e.children.filter { it.tag == "list" }
    val valueEl = e.child("value")
    if (valueEl != null) {
        require(lists.size == 1) { "channel: <value> needs exactly one <list>" }
        val start = lists[0].attr("startIndex").ifBlank { "0" }.toInt()
        return channelValue(refList(lists[0].textContent), singleTermVar(valueEl.textContent), start)
    }
    when (lists.size) {
        1 -> {
            val f = refList(lists[0].textContent)
            val start = lists[0].attr("startIndex").ifBlank { "0" }.toInt()
            factors.add(Inverse(f = f, g = f, fOffset = start, gOffset = start))
        }

        2 -> {
            val f = refList(lists[0].textContent)
            val g = refList(lists[1].textContent)
            val fStart = lists[0].attr("startIndex").ifBlank { "0" }.toInt()
            val gStart = lists[1].attr("startIndex").ifBlank { "0" }.toInt()
            // Equal lengths are a bijection (Inverse, Semantics 31: f[i]=j ⟺ g[j]=i). With
            // |X| < |Y| the spec (Semantics 32) is a one-way implication only.
            when {
                f.size == g.size -> factors.add(Inverse(f = f, g = g, fOffset = fStart, gOffset = gStart))
                f.size < g.size -> channelPartial(f, g, fStart, gStart)
                else -> throw UnsupportedXcsp3Exception("channel: first list longer violates |X|<|Y|")
            }
        }

        else -> throw UnsupportedXcsp3Exception("channel: only 1- or 2-list forms supported")
    }
}

/** `channel` of a 0/1 list [xs] to a value [v] (XCSP3 Semantics 33): exactly one `xs[i]` is 1, and it
 *  is the one at `v = i + start`. */
internal fun Compiler.Builder.channelValue(xs: IntArray, v: Int, start: Int) {
    for (i in xs.indices) {
        val selected = reifyLinear(intArrayOf(1), intArrayOf(v), LinearOp.EQ, i + start)
        val one = reifyLinear(intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, 1)
        factors.add(Clause(intArrayOf(Lit.negate(selected), one)))
        factors.add(Clause(intArrayOf(selected, Lit.negate(one))))
    }
    factors.add(Linear(IntArray(xs.size) { 1 }, xs, LinearOp.EQ, 1))
}

/** A two-list `channel` with `|X| < |Y|` (XCSP3 Semantics 32): the one-way implication
 *  `∀i,j: x[i]=j ⟹ y[j]=i`. The reverse does NOT hold — entries `y[j]` for `j` never taken
 *  by any `x[i]` are unconstrained — so unlike the equal-length case this is not a bijection. */
internal fun Compiler.Builder.channelPartial(x: IntArray, y: IntArray, xStart: Int, yStart: Int) {
    for (i in x.indices) {
        for (j in y.indices) {
            val xij = reifyLinear(longArrayOf(1L), intArrayOf(x[i]), LinearOp.EQ, j.toLong() + yStart)
            val yji = reifyLinear(longArrayOf(1L), intArrayOf(y[j]), LinearOp.EQ, i.toLong() + xStart)
            factors.add(Clause(intArrayOf(Lit.negate(xij), yji))) // x[i]=j ⟹ y[j]=i
        }
    }
}

internal fun Compiler.Builder.cumulative(e: XmlElement) {
    val starts = refList(requireNotNull(e.child("origins")).textContent)
    val (durations, durationVars) = taskDims(requireNotNull(e.child("lengths")).textContent)
    val (resources, resourceVars) = taskDims(requireNotNull(e.child("heights")).textContent)
    require(durations.size == starts.size && resources.size == starts.size) {
        "cumulative: <origins>/<lengths>/<heights> length mismatch"
    }
    val condEl = e.child("condition")
        ?: throw UnsupportedXcsp3Exception("cumulative: unsupported form (no single <condition>)")
    val (op, cap, capVar) = sumCondition(condEl.textContent.trim())
    if (op != LinearOp.LE) {
        throw UnsupportedXcsp3Exception("cumulative: only (le, capacity) conditions supported")
    }
    factors.add(
        Cumulative(
            starts = starts,
            durations = durations,
            resources = resources,
            capacity = if (capVar == null) cap else domains[capVar].max,
            durationVars = durationVars,
            resourceVars = resourceVars,
            capacityVar = capVar ?: -1,
        ),
    )
    // <ends> binds each task's end variable to start + duration (constant or variable).
    e.child("ends")?.let { endsEl ->
        val ends = refList(endsEl.textContent)
        require(ends.size == starts.size) { "cumulative: <ends>/<origins> length mismatch" }
        for (i in starts.indices) {
            if (durationVars.isEmpty()) {
                // start + duration(const) = end  ⟺  start − end = −duration
                val vars = intArrayOf(starts[i], ends[i])
                factors.add(Linear(longArrayOf(1, -1), vars, LinearOp.EQ, -durations[i]))
            } else {
                // start + duration(var) − end = 0
                val vars = intArrayOf(starts[i], durationVars[i], ends[i])
                factors.add(Linear(intArrayOf(1, 1, -1), vars, LinearOp.EQ, 0))
            }
        }
    }
}

/** Resolve a cumulative dimension list (`<lengths>`/`<heights>`) to (constants-or-upper-bounds,
 *  variable ids). Constant when every entry is an integer; otherwise each entry is a variable and
 *  the constant array holds its domain upper bound (used by [Cumulative] for horizon sizing). */
internal fun Compiler.Builder.taskDims(text: String): Pair<LongArray, IntArray> {
    parseInts(text)?.let { return it.widenToLong() to IntArray(0) }
    val vars = refList(text)
    return LongArray(vars.size) { domains[vars[it]].max } to vars
}

internal fun Compiler.Builder.circuit(e: XmlElement) {
    val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
    if (offset != 0) throw UnsupportedXcsp3Exception("circuit: only startIndex=0 supported")
    val succ = refList(listText(e))
    // XCSP3 `circuit` (Semantics 46) is subcircuit semantics: nodes with succ(i) = i are
    // excluded (self-looping); the rest form a single cycle. It additionally requires a
    // circuit of size > 1. [Circuit] captures the cycle-over-included-nodes part but also
    // admits the empty (all-excluded) assignment, so pin the number of participating nodes
    // (those with succ(i) ≠ i): to <size> when given, else to "at least one" — which, with
    // Subcircuit's rejection of a lone included node, yields size ≥ 2.
    factors.add(Circuit(succ = succ, subcircuit = true))
    val included = IntArray(succ.size) { reifyLinear(intArrayOf(1), intArrayOf(succ[it]), LinearOp.NE, it) }
    val sizeEl = e.child("size")
    if (sizeEl != null) {
        val sizeVar = singleTermVar(sizeEl.textContent.trim())
        val chans = IntArray(included.size) { litTo01(included[it]) }
        val coeffs = IntArray(chans.size + 1) { if (it < chans.size) 1 else -1 }
        factors.add(Linear(coeffs, chans + sizeVar, LinearOp.EQ, 0))
    } else {
        factors.add(Clause(included))
    }
}

internal fun Compiler.Builder.lex(e: XmlElement) {
    val (strict, swap) = lexOp(e)
    val matrixEl = e.children.firstOrNull { it.tag == "matrix" }
    if (matrixEl != null) {
        // lex-matrix / lex2 (Semantics 99): both rows and columns are lexicographically ordered.
        val rows = matrixRows(matrixEl.textContent)
        if (rows.size < 2) throw UnsupportedXcsp3Exception("lex: matrix needs at least two rows")
        val width = rows[0].size
        require(rows.all { it.size == width }) { "lex: ragged <matrix>" }
        postLexChain(rows, strict, swap)
        val cols = List(width) { j -> IntArray(rows.size) { i -> rows[i][j] } }
        postLexChain(cols, strict, swap)
        return
    }
    val lists = e.children.filter { it.tag == "list" }.map { refList(it.textContent) }
    if (lists.size < 2) throw UnsupportedXcsp3Exception("lex: needs at least two lists")
    postLexChain(lists, strict, swap)
}

/** Parse a lex `<operator>` into (strict, swap): `gt`/`ge` swap the pair so `a ⊙ b` becomes the
 *  equivalent `b </≤ a`. */
internal fun Compiler.Builder.lexOp(e: XmlElement): Pair<Boolean, Boolean> {
    val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "lt" }
    return when (opText) {
        "lt" -> true to false
        "le" -> false to false
        "gt" -> true to true
        "ge" -> false to true
        else -> throw UnsupportedXcsp3Exception("lex operator '$opText'")
    }
}

/** Post `vectors(i) ⊙ vectors(i+1)` for consecutive vectors as [LexLess] factors. */
internal fun Compiler.Builder.postLexChain(vectors: List<IntArray>, strict: Boolean, swap: Boolean) {
    for (i in 0 until vectors.size - 1) {
        val a = vectors[i]
        val b = vectors[i + 1]
        if (swap) factors.add(LexLess(b, a, strict)) else factors.add(LexLess(a, b, strict))
    }
}

/** Rows of a `<matrix>`: explicit `(a,b)(c,d)` tuples, or a 2-D array reference (`x[][]`,
 *  `g[1..8][8..15]`). The reference is reshaped from its two index axes — an empty bracket spans the
 *  array's declared dimension ([Compiler.Builder.arrayDims]), a `lo..hi` bracket that inclusive range —
 *  and each cell is referenced directly by index, so no structure is recovered from generated names. */
internal fun Compiler.Builder.matrixRows(text: String): List<IntArray> {
    val t = text.trim()
    if ('(' in t) {
        val rows = ArrayList<IntArray>()
        val cur = IntArrayList()
        forEachTuple(
            t,
            cell = { cur.add(singleTermVar(it)) },
            endRow = {
                rows.add(cur.toIntArray())
                cur.clear()
            },
        )
        return rows
    }
    val open = t.indexOf('[')
    require(open > 0) { "<matrix> '$t' is neither tuples nor an array reference" }
    val base = t.substring(0, open)
    val specs = ArrayList<String>()
    var i = open
    while (i < t.length && t[i] == '[') {
        val close = t.indexOf(']', i)
        require(close > i) { "<matrix> reference '$t' has an unclosed bracket" }
        specs.add(t.substring(i + 1, close).trim())
        i = close + 1
    }
    require(specs.size == 2 && t.substring(i).isBlank()) {
        "<matrix> reference '$t' must be a single 2-D array reference"
    }
    val dims = arrayDims[base]
    val rowIdx = matrixAxis(base, specs[0], dims?.getOrNull(0))
    val colIdx = matrixAxis(base, specs[1], dims?.getOrNull(1))
    return rowIdx.map { r -> IntArray(colIdx.size) { j -> ref("$base[$r][${colIdx[j]}]") } }
}

/** Indices selected by one `<matrix>` bracket [spec]: `lo..hi` is that inclusive range, a bare
 *  integer the single index, and an empty spec spans the whole declared dimension [dimSize]. */
private fun matrixAxis(base: String, spec: String, dimSize: Int?): IntArray = when {
    spec.isEmpty() -> {
        val n = dimSize ?: throw UnsupportedXcsp3Exception("<matrix>: array '$base' has no declared shape")
        IntArray(n) { it }
    }

    ".." in spec -> {
        val separator = spec.indexOf("..")
        require(spec.indexOf("..", separator + 2) < 0) { "<matrix> index range '$spec'" }
        val from = spec.substring(0, separator).trim().toInt()
        val to = spec.substring(separator + 2).trim().toInt()
        require(from <= to) { "<matrix> index range '$spec'" }
        IntArray(to - from + 1) { from + it }
    }

    else -> intArrayOf(spec.toInt())
}

/** Fix each listed variable to the corresponding value. */
internal fun Compiler.Builder.instantiation(e: XmlElement) {
    val vars = listVars(e)
    val vals = parseInts(e.child("values")?.textContent)
        ?: throw UnsupportedXcsp3Exception("instantiation: non-constant <values>")
    require(vars.size == vals.size) { "instantiation: <list>/<values> length mismatch" }
    vars.forEachIndexed { i, v -> factors.add(Linear(intArrayOf(1), intArrayOf(v), LinearOp.EQ, vals[i])) }
}

/**
 * The [Increasing] chain an `ordered` relation states, or null when the relation is not a chain.
 *
 * `relOp` encodes the row as `x[i] − x[i+1] ⟨op⟩ delta`, so `le`/`lt` ascend and `ge`/`gt` descend —
 * the descending pair is the ascending chain over the reversed sequence. `eq` and `ne` are not chains.
 */
private fun orderedChain(vars: IntArray, op: LinearOp, delta: Int): Increasing? {
    if (vars.size < 2) return null
    return when {
        op == LinearOp.LE && delta == 0 -> Increasing(vars, strict = false)
        op == LinearOp.LE && delta == -1 -> Increasing(vars, strict = true)
        op == LinearOp.GE && delta == 0 -> Increasing(vars.reversedCopy(), strict = false)
        op == LinearOp.GE && delta == 1 -> Increasing(vars.reversedCopy(), strict = true)
        else -> null
    }
}

private fun IntArray.reversedCopy(): IntArray = IntArray(size) { this[size - 1 - it] }

/** Chain relation over consecutive list entries: `vars(i) ⟨op⟩ vars(i+1)`, or with `<lengths>`,
 *  `vars(i) + length(i) ⟨op⟩ vars(i+1)` (one length per gap; constants or variables). */
internal fun Compiler.Builder.ordered(e: XmlElement) {
    val vars = listVars(e)
    val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "le" }
    val (op, delta) = relOp(opText) ?: throw UnsupportedXcsp3Exception("ordered operator '$opText'")
    val lengthsEl = e.child("lengths")
    if (lengthsEl == null) {
        // The source names a chain, so post the chain: [Increasing] reads prefix minima and suffix
        // maxima across the whole sequence, where the pairwise rows each see two variables and cannot
        // reach past their neighbour. A descending operator is the same chain read backwards.
        val chain = orderedChain(vars, op, delta)
        if (chain != null) {
            factors.add(chain)
            return
        }
        for (i in 0 until vars.size - 1) {
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), op, delta))
        }
        return
    }
    val constLens = parseInts(lengthsEl.textContent)
    if (constLens != null) {
        require(constLens.size == vars.size - 1) { "ordered: <lengths> size != list size - 1" }
        for (i in 0 until vars.size - 1) {
            // vars[i] + length[i] ⟨op⟩ vars[i+1] ≡ vars[i] − vars[i+1] ⟨op⟩ delta − length[i]
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), op, delta - constLens[i]))
        }
    } else {
        val lenVars = refList(lengthsEl.textContent)
        require(lenVars.size == vars.size - 1) { "ordered: <lengths> size != list size - 1" }
        for (i in 0 until vars.size - 1) {
            // vars[i] + length[i] − vars[i+1] ⟨op⟩ delta
            factors.add(Linear(intArrayOf(1, 1, -1), intArrayOf(vars[i], lenVars[i], vars[i + 1]), op, delta))
        }
    }
}

/** All listed variables take the same value. */
internal fun Compiler.Builder.allEqual(e: XmlElement) {
    // <except> weakens the constraint (listed values are exempt); dropping it would be unsound.
    if (e.child("except") != null) throw UnsupportedXcsp3Exception("allEqual with <except>")
    val vars = refList(listText(e))
    for (i in 0 until vars.size - 1) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), LinearOp.EQ, 0))
    }
}

internal fun Compiler.Builder.minMax(e: XmlElement, max: Boolean) {
    // Entries may be plain variables or expressions (e.g. `sub(y,37)`), each resolved to an int var.
    val vars = requireNotNull(e.child("list")).textContent.splitWs()
        .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
    requireAnySelectionRank(e)
    val index = e.child("index")
    val condition = e.child("condition")?.textContent?.trim()
    if (condition == null && index == null) throw UnsupportedXcsp3Exception("extremum: missing <condition> or <index>")
    // `(eq, v)` makes v the extremum itself; posting onto it avoids an alias row that presolve could
    // collapse onto the auxiliary and thereby drop the result's definition.
    val target = condition?.let { equalityTarget(it) }?.takeIf { it !in vars }
    val m = target ?: newAuxVar(domainMin(vars), domainMin(vars) + domainSpan(vars) - 1)
    factors.add(ArrayMinMax(result = m, xs = vars, max = max))
    definedVars.add(m)
    defineExtreme(m, vars, max)
    if (index != null) {
        factors.add(Element(singleTermVar(index.textContent), m, vars.widenToLong(),
            arrIsVars = true, indexOffset = selectionOffset(e)))
    }
    if (condition != null && target == null) postCondition(intArrayOf(1), intArrayOf(m), condition)
}

/** The variable an `(eq, v)` condition equates its expression to, or null for any other condition. */
internal fun Compiler.Builder.equalityTarget(condText: String): Int? {
    val (opTok, rhs) = splitCondition(condText)
    return if (opTok == "eq" && rhs.toLongOrNull() == null) ref(rhs) else null
}

/** Global cardinality: each value in `<values>` occurs a fixed count, an interval, or a
 *  variable count (`<occurs>`) among the listed variables. */
internal fun Compiler.Builder.cardinality(e: XmlElement) {
    val vars = listVars(e)
    val valuesEl = requireNotNull(e.child("values"))
    val values = (
        parseInts(valuesEl.textContent)
            ?: throw UnsupportedXcsp3Exception("cardinality: non-constant <values>")
        ).widenToLong()
    // closed="true" additionally forbids any variable taking a value outside <values>.
    val closed = valuesEl.attr("closed").equals("true", ignoreCase = true)
    val occursText = requireNotNull(e.child("occurs")).textContent.trim()
    val occTokens = occursText.splitWs()
    val exact = parseInts(occursText)
    when {
        // Interval occurrences `lo..hi`.
        occTokens.any { ".." in it } -> {
            require(occTokens.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
            val lo = IntArray(occTokens.size) { occTokens[it].substringBefore("..").toInt() }
            val hi = IntArray(occTokens.size) { occTokens[it].substringAfter("..").toInt() }
            factors.add(
                GlobalCardinality(xs = vars, cover = values, countLow = lo, countHigh = hi, closed = closed),
            )
        }

        // Exact constant occurrences.
        exact != null -> {
            require(exact.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
            factors.add(
                GlobalCardinality(
                    xs = vars,
                    cover = values,
                    countLow = exact,
                    countHigh = exact,
                    closed = closed,
                ),
            )
        }

        // Variable occurrences (a `<list>`/array reference, possibly a wildcard like `g[]`).
        else -> {
            val occVars = refList(occursText)
            require(occVars.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
            factors.add(GlobalCardinality(xs = vars, cover = values, countVars = occVars, closed = closed))
        }
    }
}

/** Reify `x == value` onto a 0/1 int var shared by every occurrence of the pair. */
internal fun Compiler.Builder.eqValue01(x: Int, value: Long): Int {
    val byValue = valueIndicators.getOrPut(x) { MutableLongIntMap() }
    val existing = byValue.getOrDefault(value, -1)
    if (existing >= 0) return existing.also { classes.define(it, factors.size) }
    val start = factors.size
    val eq = newBool()
    factors.add(ReifiedLinear(eq, longArrayOf(1), intArrayOf(x), LinearOp.EQ, value))
    val literal = Lit.make(eq, true)
    val indicator = litTo01(literal)
    // Typed like FlatZinc's `int_eq_reif`/`bool2int`, so local search maintains the indicator from its variable
    // without depending on the factor-shape inference recognising the reified rows.
    definitionHints.add(DefinitionHint.Comparison(
        eq, longArrayOf(1), arrayOf(DefinitionHint.Operand.Variable(x)), value, LinearOp.EQ,
    ))
    definitionHints.add(DefinitionHint.BooleanValue(indicator, literal))
    classes.define(indicator, start)
    return indicator.also { byValue.put(value, it) }
}

/** `binPacking`: item `i` goes to bin `list(i)`; each bin's total item size meets the condition. */
@Suppress("ThrowsCount") // one guard per unsupported shape across the loads/limits/condition forms
internal fun Compiler.Builder.binPacking(e: XmlElement) {
    val items = listVars(e)
    val sizes = parseInts(e.child("sizes")?.textContent)
        ?: throw UnsupportedXcsp3Exception("binPacking: non-constant <sizes>")
    require(sizes.size == items.size) { "binPacking: <list>/<sizes> length mismatch" }
    val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
    val loadsEl = e.child("loads")
    val condEl = e.child("condition")
    when {
        // `<loads>`: each bin's total size equals its load variable — `Σ size[i]·[list[i]=b] = loads[b]`.
        loadsEl != null -> {
            // Load entries may be plain variables or expressions (e.g. `sub(y,37)`).
            val loadNames = loadsEl.textContent.splitWs().flatMap { expandNames(it) }
            val loadVars = IntArray(loadNames.size) { termVar(loadNames[it]) }
            val coefficients = LongArray(sizes.size + 1) { if (it < sizes.size) sizes[it].toLong() else -1L }
            for (b in loadVars.indices) {
                val ind = IntArray(items.size) { i -> eqValue01(items[i], (b + offset).toLong()) }
                val terms = ind + loadVars[b]
                factors.add(Linear(sizes + -1, terms, LinearOp.EQ, 0))
                // A materialized load expression already has its own definer; only a declared load is oriented.
                if (varIds[loadNames[b]] == loadVars[b]) defineLinear(loadVars[b], coefficients, terms, 0L)
            }
        }

        // `<limits>`: each bin `b` has its own constant capacity — `Σ size[i]·[list[i]=b] ≤ limits[b]`.
        e.child("limits") != null -> {
            val limits = parseInts(e.child("limits")?.textContent)
                ?: throw UnsupportedXcsp3Exception("binPacking: non-constant <limits>")
            for (b in limits.indices) {
                val ind = IntArray(items.size) { i -> eqValue01(items[i], (b + offset).toLong()) }
                factors.add(Linear(sizes.copyOf(), ind, LinearOp.LE, limits[b]))
            }
        }

        // `<condition>`: each bin's total size meets a shared capacity condition. The spec
        // quantifies only over used bins; applying the condition to every bin value in range
        // is a strengthening that is sound only for `le`/`lt` (an empty bin's total 0 always
        // satisfies `≤ k` for a non-negative capacity). Other operators would force empty bins
        // to meet a lower bound, so they are rejected rather than mis-encoded.
        condEl != null -> {
            val condText = condEl.textContent.trim()
            val (op, _) = condition(condText)
            if (op != LinearOp.LE) {
                throw UnsupportedXcsp3Exception("binPacking: only a (le/lt, k) <condition> is supported")
            }
            val loBin = items.minOf { domains[it].min }
            val hiBin = items.maxOf { domains[it].max }
            for (b in loBin..hiBin) {
                val ind = IntArray(items.size) { i -> eqValue01(items[i], b) }
                postCondition(sizes.copyOf(), ind, condText)
            }
        }

        else -> throw UnsupportedXcsp3Exception("binPacking: neither <condition> nor <loads>")
    }
}

/** `knapsack`: a weighted-sum condition on item weights and one on item profits. */
internal fun Compiler.Builder.knapsack(e: XmlElement) {
    val items = listVars(e)
    val weights = parseInts(e.child("weights")?.textContent)
        ?: throw UnsupportedXcsp3Exception("knapsack: non-constant <weights>")
    val profits = parseInts(e.child("profits")?.textContent)
        ?: throw UnsupportedXcsp3Exception("knapsack: non-constant <profits>")
    require(weights.size == items.size && profits.size == items.size) { "knapsack: length mismatch" }
    val conditions = e.children.filter { it.tag == "condition" }
    require(conditions.size == 2) { "knapsack: expected weight and profit conditions" }
    postCondition(weights, items, conditions[0].textContent.trim())
    postCondition(profits, items, conditions[1].textContent.trim())
}

/** `nValues`: the number of distinct values taken across the list — excluding any `<except>`
 *  values — meets the condition. */
internal fun Compiler.Builder.nValues(e: XmlElement) {
    val vars = listVars(e)
    val condText = requireNotNull(e.child("condition")).textContent.trim()
    val except = e.child("except")?.let {
        (parseInts(it.textContent) ?: throw UnsupportedXcsp3Exception("nValues: non-constant <except>")).toSet()
    }.orEmpty()
    val cnt = distinctCountVar(vars, except)
    postCondition(intArrayOf(1), intArrayOf(cnt), condText)
}

internal fun Compiler.Builder.distinctCountVar(vars: IntArray, except: Set<Int> = emptySet()): Int {
    val cnt = newAuxVar(0L, vars.size.toLong())
    if (vars.isNotEmpty()) {
        factors.add(NValue(n = cnt, xs = vars, exceptSet = except.map { it.toLong() }.toLongArray()))
    }
    return cnt
}

/** Value precedence: for each consecutive pair `(s, t)` in `<values>`, value `s` must first
 *  occur before `t` — `t` at position `j` requires some `s` at an earlier position, and `t`
 *  cannot occupy position 0. With no `<values>`, the chain runs over the sorted union of the
 *  variables' domain values. */
internal fun Compiler.Builder.precedence(e: XmlElement) {
    if (e.attr("covered").equals("true", ignoreCase = true)) {
        throw UnsupportedXcsp3Exception("precedence: covered form")
    }
    // The list may be a <list> child or, in the symmetry-breaking shorthand, direct content.
    val vars = refList(listText(e))
    if (vars.isEmpty()) return
    val values = parseInts(e.child("values")?.textContent)?.let { parsed ->
        LongArray(parsed.size) { parsed[it].toLong() }
    } ?: vars.flatMap { domainValues(it) }.distinct().sorted().toLongArray()
    if (values.size < 2) return
    // The source names the precedence, so post it: [ValuePrecede] scans the sequence for the first
    // occurrence of either value, where the clause encoding needs a reified equality per (position,
    // value) and a clause per position — quadratic in the sequence, and each clause sees one position.
    for (i in 0 until values.size - 1) {
        factors.add(ValuePrecede(values[i], values[i + 1], vars))
    }
}

/** 1-D no-overlap (disjunctive): tasks with constant durations share a unit resource, so at
 *  most one runs at a time — a [Cumulative] with unit heights and capacity 1. */
@Suppress("ThrowsCount") // one guard per unsupported noOverlap shape
internal fun Compiler.Builder.noOverlap(e: XmlElement) {
    val originsText = requireNotNull(e.child("origins")).textContent
    if ('(' in originsText) return noOverlapMulti(e, originsText)
    val starts = refList(originsText)
    val durations = parseInts(e.child("lengths")?.textContent)
        ?: throw UnsupportedXcsp3Exception("noOverlap: non-constant <lengths>")
    require(durations.size == starts.size) { "noOverlap: <origins>/<lengths> length mismatch" }
    // The Cumulative encoding lets a zero-length task sit anywhere (it consumes no resource),
    // which is exactly zeroIgnored="true" (the default). zeroIgnored="false" forbids placing a
    // zero-length task overlapping others, which this encoding cannot express — reject it.
    if (e.attr("zeroIgnored").equals("false", ignoreCase = true) && durations.any { it == 0 }) {
        throw UnsupportedXcsp3Exception("noOverlap: zeroIgnored=false with a zero-length task")
    }
    factors.add(
        Cumulative(
            starts = starts,
            durations = durations.widenToLong(),
            resources = LongArray(starts.size) { 1L },
            capacity = 1L,
        ),
    )
}

/** k-dimensional no-overlap (diffn, Semantics 37): for each pair of boxes there is at least one
 *  dimension in which one box lies entirely before the other. Origins are variables, lengths
 *  may be constants or variables. `zeroIgnored="false"` (zero-width boxes may not be placed) is
 *  not expressible here and is rejected. */
internal fun Compiler.Builder.noOverlapMulti(e: XmlElement, originsText: String) {
    if (e.attr("zeroIgnored").equals("false", ignoreCase = true)) {
        throw UnsupportedXcsp3Exception("noOverlap: zeroIgnored=false not supported for boxes")
    }
    val origins = tupleRows(originsText) { ref(it) }
    val lengths = tupleRows(requireNotNull(e.child("lengths")).textContent) { singleTermVar(it) }
    require(origins.size == lengths.size) { "noOverlap: <origins>/<lengths> box count mismatch" }
    if (origins.isEmpty()) return
    val nDim = origins[0].size
    require(origins.all { it.size == nDim } && lengths.all { it.size == nDim }) {
        "noOverlap: inconsistent box dimensionality"
    }
    if (nDim == 2) {
        // The source names rectangles, so post [Diffn]: it reasons over both axes together, where the
        // clause form reifies four separations per box pair and each clause sees only that pair.
        // `zeroIgnored="false"` is rejected above, so the remaining semantics is Diffn's [nonStrict].
        factors.add(
            Diffn(
                xs = IntArray(origins.size) { origins[it][0] },
                ys = IntArray(origins.size) { origins[it][1] },
                widths = emptyLongArray(),
                heights = emptyLongArray(),
                widthVars = IntArray(lengths.size) { lengths[it][0] },
                heightVars = IntArray(lengths.size) { lengths[it][1] },
                nonStrict = true,
            ),
        )
        return
    }
    // Past two dimensions there is no k-D global to post, so the pairwise separation stands.
    for (i in origins.indices) {
        for (j in i + 1 until origins.size) {
            val seps = ArrayList<Int>(2 * nDim)
            for (k in 0 until nDim) {
                // box i entirely before box j in dim k: x[i,k] + len[i,k] ≤ x[j,k]
                seps.add(reifyLe3(origins[i][k], lengths[i][k], origins[j][k]))
                seps.add(reifyLe3(origins[j][k], lengths[j][k], origins[i][k]))
            }
            factors.add(Clause(seps.toIntArray()))
        }
    }
}

/** Reify `a + b ≤ c` (a,b,c variable ids) onto a literal. */
internal fun Compiler.Builder.reifyLe3(a: Int, b: Int, c: Int): Int =
    reifyLinear(intArrayOf(1, 1, -1), intArrayOf(a, b, c), LinearOp.LE, 0)

/** Parse `(t,t,…)(t,t,…)…` tuple rows, resolving each entry with [resolve]. */
internal fun Compiler.Builder.tupleRows(text: String, resolve: (String) -> Int): List<IntArray> {
    val rows = ArrayList<IntArray>()
    val cur = IntArrayList()
    forEachTuple(
        text,
        cell = { cur.add(resolve(it)) },
        endRow = {
            rows.add(cur.toIntArray())
            cur.clear()
        },
    )
    return rows
}

@Suppress("ThrowsCount") // one guard per malformed/unsupported shape (matrix, except, empty list)
internal fun Compiler.Builder.allDifferent(e: XmlElement) {
    // `<matrix>` form: values must be pairwise distinct on each row *and* on each column (not one
    // all-different over the whole matrix — that would demand more distinct values than the shared
    // domain holds and spuriously fail, e.g. a Sudoku grid). Rows and columns each get their own.
    e.child("matrix")?.let { m ->
        if (e.child("except") != null) throw UnsupportedXcsp3Exception("allDifferent: <matrix> with <except>")
        val rows = matrixRows(m.textContent)
        if (rows.isEmpty()) throw UnsupportedXcsp3Exception("allDifferent: empty <matrix>")
        val width = rows[0].size
        require(rows.all { it.size == width }) { "allDifferent: ragged <matrix>" }
        for (row in rows) postAllDifferent(row)
        for (j in 0 until width) postAllDifferent(IntArray(rows.size) { i -> rows[i][j] })
        return
    }
    val lists = e.children.filter { it.tag == "list" }
    if (lists.size > 1) {
        if (e.child("except") != null) throw UnsupportedXcsp3Exception("allDifferent: <list>s with <except>")
        if (listOf("reifiedBy", "hreifiedFrom", "hreifiedTo").any { e.attr(it).isNotEmpty() }) {
            throw UnsupportedXcsp3Exception("allDifferent: reified <list>s")
        }
        return allDifferentLists(lists.map { refList(it.textContent) })
    }
    val vars = refList(listText(e))
    if (vars.isEmpty()) throw UnsupportedXcsp3Exception("allDifferent: empty list")
    // <except> weakens the constraint: variables taking an exempt value may repeat.
    e.child("except")?.let { exceptEl ->
        val except = parseInts(exceptEl.textContent)
            ?: throw UnsupportedXcsp3Exception("allDifferent: non-constant <except>")
        if (except.isNotEmpty()) return allDifferentExcept(vars, except)
    }
    postAllDifferent(vars)
}

internal fun Compiler.Builder.postAllDifferent(vars: IntArray) {
    if (vars.size >= 2) factors.add(AllDifferent(vars))
}

/** `allDifferent` over several `<list>`s: the lists, read as tuples, are pairwise distinct — each pair
 *  differs at some position. The variables themselves may repeat values within and across lists. */
internal fun Compiler.Builder.allDifferentLists(lists: List<IntArray>) {
    factors.add(AllDifferentTuples(lists))
}

internal fun Compiler.Builder.allDifferentExcept(vars: IntArray, except: IntArray) {
    if (vars.size >= 2) factors.add(AllDifferent(vars, exceptSet = except.widenToLong()))
}
