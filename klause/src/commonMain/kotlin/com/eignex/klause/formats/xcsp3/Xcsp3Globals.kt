package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Lit

// XCSP3 global-constraint family emitters (allDifferent excepted) — split out of Xcsp3.kt.

internal fun Xcsp3.Builder.count(e: XmlElement) {
    val vars = listVars(e)
    val values = parseInts(e.child("values")?.textContent)
        ?: throw UnsupportedXcsp3Exception("count: only constant <values> supported")
    if (values.size != 1) throw UnsupportedXcsp3Exception("count: only a single value supported")
    val cnt = newAuxVar(0L, vars.size.toLong())
    // Reify equalities and sum their 0/1 channels into `cnt`.
    val channels = IntArray(vars.size) { i ->
        val aux = newBool()
        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(vars[i]), LinearOp.EQ, values[0]))
        val ch = newAuxVar(0L, 1L)
        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
        ch
    }
    val sumCoeffs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
    val sumVars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else cnt }
    factors.add(Linear(sumCoeffs, sumVars, LinearOp.EQ, 0))
    postCondition(intArrayOf(1), intArrayOf(cnt), requireNotNull(e.child("condition")).textContent.trim())
}

internal fun Xcsp3.Builder.element(e: XmlElement) {
    e.child("matrix")?.let { return elementMatrix(e, it) }
    val arr = listVars(e)
    val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
    val idx = singleTermVar(
        e.child("index")?.textContent
            ?: throw UnsupportedXcsp3Exception("element: missing <index>"),
    )
    // The selected element arr[idx] is constrained either directly to a <value> (the eq form)
    // or by a <condition> `(op, operand)` on a fresh var bound to it.
    val condEl = e.child("condition")
    if (condEl != null) {
        val selected = newAuxVar(domainMin(arr), domainMin(arr) + domainSpan(arr) - 1)
        factors.add(
            Element(
                idx = idx,
                result = selected,
                arr = arr.widenToLong(),
                arrIsVars = true,
                indexOffset = offset,
            ),
        )
        postCondition(intArrayOf(1), intArrayOf(selected), condEl.textContent.trim())
        return
    }
    val value = e.child("value")?.textContent?.trim()
        ?: throw UnsupportedXcsp3Exception("element: missing <value> or <condition>")
    factors.add(
        Element(
            idx = idx,
            result = singleTermVar(value),
            arr = arr.widenToLong(),
            arrIsVars = true,
            indexOffset = offset,
        ),
    )
}

/** `element` over a matrix `M`: `M[i][j] = v` with `<index> i j </index>`. A constant matrix
 *  is a 3-column [Table] over `(i, j, v)` — one tuple per cell; a matrix of variables (e.g. an
 *  `x[][]` array reference, which the constant path reads as empty) is decomposed cell-by-cell. */
@Suppress("ThrowsCount") // one guard per unsupported shape (bad index, empty constant/variable matrix)
internal fun Xcsp3.Builder.elementMatrix(e: XmlElement, matrix: XmlElement) {
    // Matrix element uses per-axis start indices (defaulting to 0), not a single startIndex.
    val rowOffset = e.attr("startRowIndex").ifBlank { "0" }.toInt()
    val colOffset = e.attr("startColIndex").ifBlank { "0" }.toInt()
    val idxTokens = requireNotNull(e.child("index")).textContent.trim()
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    if (idxTokens.size != 2) throw UnsupportedXcsp3Exception("element: matrix needs a 2-D <index>")
    val i = singleTermVar(idxTokens[0])
    val j = singleTermVar(idxTokens[1])
    val v = singleTermVar(requireNotNull(e.child("value")).textContent)

    val constRows = constMatrixRows(matrix.textContent)
    if (constRows != null) {
        if (constRows.isEmpty()) throw UnsupportedXcsp3Exception("element: empty <matrix>")
        val tuples = ArrayList<Int>(constRows.sumOf { it.size } * 3)
        for (r in constRows.indices) {
            for (c in constRows[r].indices) {
                tuples.add(r + rowOffset)
                tuples.add(c + colOffset)
                tuples.add(constRows[r][c])
            }
        }
        factors.add(Table(xs = intArrayOf(i, j, v), tuples = tuples.toIntArray().widenToLong()))
        return
    }
    val rows = matrixRows(matrix.textContent)
    if (rows.isEmpty()) throw UnsupportedXcsp3Exception("element: empty <matrix>")
    elementVarMatrix(rows, i, j, v, rowOffset, colOffset)
}

/** The rows of a constant integer `<matrix>` (`(1,2)(3,4)`), or null when it is not an all-constant
 *  parenthesised matrix (an array reference, or one with variable entries — handled elsewhere). */
internal fun Xcsp3.Builder.constMatrixRows(text: String): List<IntArray>? {
    val t = text.trim()
    if ('(' !in t) return null
    val rows = ArrayList<IntArray>()
    for (m in Regex("""\(([^)]*)\)""").findAll(t)) {
        val cells = m.groupValues[1].split(",")
        val ints = IntArray(cells.size)
        for (k in cells.indices) ints[k] = cells[k].trim().toIntOrNull() ?: return null
        rows.add(ints)
    }
    return rows
}

/** `element` over a matrix of variables: `M[i][j] = v`, decomposed as `(i=r) ∧ (j=c) ⟹ v = M[r][c]`
 *  per cell, with the index pinned into the matrix's range so an out-of-range selection cannot
 *  leave `v` unconstrained. */
internal fun Xcsp3.Builder.elementVarMatrix(rows: List<IntArray>, i: Int, j: Int, v: Int, rowOff: Int, colOff: Int) {
    val nCols = rows[0].size
    require(rows.all { it.size == nCols }) { "element: ragged <matrix>" }
    if (rows.size.toLong() * nCols > negTableCap) {
        throw UnsupportedXcsp3Exception("element: matrix decomposition exceeds cap")
    }
    // The index must select a real cell (Element semantics require a valid index).
    factors.add(Linear(intArrayOf(1), intArrayOf(i), LinearOp.GE, rowOff))
    factors.add(Linear(intArrayOf(1), intArrayOf(i), LinearOp.LE, rowOff + rows.size - 1))
    factors.add(Linear(intArrayOf(1), intArrayOf(j), LinearOp.GE, colOff))
    factors.add(Linear(intArrayOf(1), intArrayOf(j), LinearOp.LE, colOff + nCols - 1))
    for (r in rows.indices) {
        val iEq = reifyLinear(intArrayOf(1), intArrayOf(i), LinearOp.EQ, r + rowOff)
        for (c in rows[r].indices) {
            val jEq = reifyLinear(intArrayOf(1), intArrayOf(j), LinearOp.EQ, c + colOff)
            val vEq = reifyLinear(intArrayOf(1, -1), intArrayOf(v, rows[r][c]), LinearOp.EQ, 0)
            factors.add(Clause(intArrayOf(Lit.negate(iEq), Lit.negate(jEq), vEq))) // (i=r)∧(j=c) ⟹ v=M[r][c]
        }
    }
}

internal fun Xcsp3.Builder.channel(e: XmlElement) {
    val lists = e.children.filter { it.tag == "list" }
    when (lists.size) {
        1 -> {
            val f = refList(lists[0].textContent).toIntArray()
            factors.add(Inverse(f = f, g = f))
        }

        2 -> {
            val f = refList(lists[0].textContent).toIntArray()
            val g = refList(lists[1].textContent).toIntArray()
            // Equal lengths are a bijection (Inverse, Semantics 31: f[i]=j ⟺ g[j]=i). With
            // |X| < |Y| the spec (Semantics 32) is a one-way implication only.
            when {
                f.size == g.size -> factors.add(Inverse(f = f, g = g))
                f.size < g.size -> channelPartial(f, g)
                else -> throw UnsupportedXcsp3Exception("channel: first list longer violates |X|<|Y|")
            }
        }

        else -> throw UnsupportedXcsp3Exception("channel: only 1- or 2-list forms supported")
    }
}

/** A two-list `channel` with `|X| < |Y|` (XCSP3 Semantics 32): the one-way implication
 *  `∀i,j: x[i]=j ⟹ y[j]=i`. The reverse does NOT hold — entries `y[j]` for `j` never taken
 *  by any `x[i]` are unconstrained — so unlike the equal-length case this is not a bijection. */
internal fun Xcsp3.Builder.channelPartial(x: IntArray, y: IntArray) {
    if (x.size.toLong() * y.size > negTableCap) {
        throw UnsupportedXcsp3Exception("channel: ${x.size}x${y.size} decomposition exceeds cap")
    }
    for (i in x.indices) {
        for (j in y.indices) {
            val xij = reifyLinear(intArrayOf(1), intArrayOf(x[i]), LinearOp.EQ, j) // x[i] = j
            val yji = reifyLinear(intArrayOf(1), intArrayOf(y[j]), LinearOp.EQ, i) // y[j] = i
            factors.add(Clause(intArrayOf(Lit.negate(xij), yji))) // x[i]=j ⟹ y[j]=i
        }
    }
}

internal fun Xcsp3.Builder.regular(e: XmlElement) {
    val finals = requireNotNull(e.child("final")).textContent.trim()
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    buildRegular(
        listVars(e),
        requireNotNull(e.child("transitions")).textContent,
        requireNotNull(e.child("start")).textContent.trim(),
        finals,
    )
}

/** A multi-valued decision diagram is a layered automaton: the root is the node that is never a
 *  transition destination; the accepting nodes are the sinks (never a source). */
internal fun Xcsp3.Builder.mdd(e: XmlElement) {
    val transitions = requireNotNull(e.child("transitions")).textContent
    val srcs = HashSet<String>()
    val dsts = HashSet<String>()
    for (m in Xcsp3.Builder.TRANSITION.findAll(transitions)) {
        srcs.add(m.groupValues[1])
        dsts.add(m.groupValues[3])
    }
    val start = if ("root" in srcs) {
        "root"
    } else {
        (srcs - dsts).firstOrNull()
            ?: throw UnsupportedXcsp3Exception("mdd: no root node")
    }
    buildRegular(listVars(e), transitions, start, (dsts - srcs).toList())
}

internal fun Xcsp3.Builder.buildRegular(seqVars: IntArray, transitions: String, start: String, finals: List<String>) {
    if (seqVars.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: empty sequence list")
    data class Tr(val src: String, val sym: Int, val dst: String)
    val trs = Xcsp3.Builder.TRANSITION.findAll(transitions)
        .map { Tr(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.toList()
    if (trs.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: no transitions")

    val stateIdx = LinkedHashMap<String, Int>()
    fun stateOf(st: String) = stateIdx.getOrPut(st) { stateIdx.size + 1 }
    stateOf(start)
    trs.forEach {
        stateOf(it.src)
        stateOf(it.dst)
    }
    val q = stateIdx.size

    val minSym = trs.minOf { it.sym }
    val maxSym = trs.maxOf { it.sym }
    val offset = 1 - minSym
    val s = maxSym - minSym + 1
    val table = IntArray(q * s) // 0 = dead state
    for (t in trs) table[(stateOf(t.src) - 1) * s + (t.sym + offset - 1)] = stateOf(t.dst)
    val accepting = finals.map { stateOf(it) }.toIntArray()

    val seq = if (offset == 0) {
        seqVars
    } else {
        IntArray(seqVars.size) { i ->
            val sv = seqVars[i]
            val d = domains[sv]
            val c = newAuxVar(d.min + offset, d.max + offset)
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(c, sv), LinearOp.EQ, offset)) // c - sv = offset
            c
        }
    }
    factors.add(
        Regular(
            seq = seq,
            numStates = q,
            alphabetSize = s,
            transitions = table.widenToLong(),
            q0 = stateOf(start),
            accepting = accepting,
        ),
    )
}

internal fun Xcsp3.Builder.cumulative(e: XmlElement) {
    val starts = refList(requireNotNull(e.child("origins")).textContent).toIntArray()
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
            capacity = if (capVar == null) cap.toLong() else domains[capVar].max,
            durationVars = durationVars,
            resourceVars = resourceVars,
            capacityVar = capVar ?: -1,
        ),
    )
    // <ends> binds each task's end variable to start + duration (constant or variable).
    e.child("ends")?.let { endsEl ->
        val ends = refList(endsEl.textContent).toIntArray()
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
internal fun Xcsp3.Builder.taskDims(text: String): Pair<LongArray, IntArray> {
    parseInts(text)?.let { return it.widenToLong() to IntArray(0) }
    val vars = refList(text).toIntArray()
    return LongArray(vars.size) { domains[vars[it]].max } to vars
}

internal fun Xcsp3.Builder.circuit(e: XmlElement) {
    val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
    if (offset != 0) throw UnsupportedXcsp3Exception("circuit: only startIndex=0 supported")
    val succ = refList(listText(e)).toIntArray()
    // XCSP3 `circuit` (Semantics 46) is subcircuit semantics: nodes with succ(i) = i are
    // excluded (self-looping); the rest form a single cycle. It additionally requires a
    // circuit of size > 1. [Subcircuit] captures the cycle-over-included-nodes part but also
    // admits the empty (all-excluded) assignment, so pin the number of participating nodes
    // (those with succ(i) ≠ i): to <size> when given, else to "at least one" — which, with
    // Subcircuit's rejection of a lone included node, yields size ≥ 2.
    factors.add(Subcircuit(succ = succ))
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

internal fun Xcsp3.Builder.lex(e: XmlElement) {
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
    val lists = e.children.filter { it.tag == "list" }.map { refList(it.textContent).toIntArray() }
    if (lists.size < 2) throw UnsupportedXcsp3Exception("lex: needs at least two lists")
    postLexChain(lists, strict, swap)
}

/** Parse a lex `<operator>` into (strict, swap): `gt`/`ge` swap the pair so `a ⊙ b` becomes the
 *  equivalent `b </≤ a`. */
internal fun Xcsp3.Builder.lexOp(e: XmlElement): Pair<Boolean, Boolean> {
    val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "lt" }
    return when (opText) {
        "lt" -> true to false
        "le" -> false to false
        "gt" -> true to true
        "ge" -> false to true
        else -> throw UnsupportedXcsp3Exception("lex operator '$opText'")
    }
}

/** Post `vectors[i] ⊙ vectors[i+1]` for consecutive vectors as [LexLess] factors. */
internal fun Xcsp3.Builder.postLexChain(vectors: List<IntArray>, strict: Boolean, swap: Boolean) {
    for (i in 0 until vectors.size - 1) {
        val a = vectors[i]
        val b = vectors[i + 1]
        if (swap) factors.add(LexLess(b, a, strict)) else factors.add(LexLess(a, b, strict))
    }
}

/** Rows of a `<matrix>`: explicit `(a,b)(c,d)` tuples, or a compact 2-D array reference such
 *  as `x[][]` reshaped from the cells' trailing `[row][col]` indices. */
internal fun Xcsp3.Builder.matrixRows(text: String): List<IntArray> {
    val t = text.trim()
    if ('(' in t) {
        return Regex("""\(([^)]*)\)""").findAll(t)
            .map { m -> m.groupValues[1].split(",").map { singleTermVar(it.trim()) }.toIntArray() }
            .toList()
    }
    val idxRe = Regex("""\[(\d+)]""")
    val cells = t.split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { expandNames(it) }
    val byRow = HashMap<Int, MutableList<Pair<Int, Int>>>()
    for (name in cells) {
        val idx = idxRe.findAll(name).map { it.groupValues[1].toInt() }.toList()
        require(idx.size >= 2) { "lex: <matrix> cell '$name' is not 2-D" }
        byRow.getOrPut(idx[idx.size - 2]) { ArrayList() }.add(idx[idx.size - 1] to ref(name))
    }
    return byRow.keys.sorted().map { r ->
        byRow.getValue(r).sortedBy { it.first }.map { it.second }.toIntArray()
    }
}

/** Fix each listed variable to the corresponding value. */
internal fun Xcsp3.Builder.instantiation(e: XmlElement) {
    val vars = listVars(e)
    val vals = parseInts(e.child("values")?.textContent)
        ?: throw UnsupportedXcsp3Exception("instantiation: non-constant <values>")
    require(vars.size == vals.size) { "instantiation: <list>/<values> length mismatch" }
    vars.forEachIndexed { i, v -> factors.add(Linear(intArrayOf(1), intArrayOf(v), LinearOp.EQ, vals[i])) }
}

/** Chain relation over consecutive list entries: `vars[i] ⟨op⟩ vars[i+1]`, or with `<lengths>`,
 *  `vars[i] + length[i] ⟨op⟩ vars[i+1]` (one length per gap; constants or variables). */
internal fun Xcsp3.Builder.ordered(e: XmlElement) {
    val vars = listVars(e)
    val opText = (e.child("operator")?.textContent?.trim() ?: e.attr("operator")).ifBlank { "le" }
    val (op, delta) = relOp(opText) ?: throw UnsupportedXcsp3Exception("ordered operator '$opText'")
    val lengthsEl = e.child("lengths")
    if (lengthsEl == null) {
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
        val lenVars = refList(lengthsEl.textContent).toIntArray()
        require(lenVars.size == vars.size - 1) { "ordered: <lengths> size != list size - 1" }
        for (i in 0 until vars.size - 1) {
            // vars[i] + length[i] − vars[i+1] ⟨op⟩ delta
            factors.add(Linear(intArrayOf(1, 1, -1), intArrayOf(vars[i], lenVars[i], vars[i + 1]), op, delta))
        }
    }
}

/** All listed variables take the same value. */
internal fun Xcsp3.Builder.allEqual(e: XmlElement) {
    // <except> weakens the constraint (listed values are exempt); dropping it would be unsound.
    if (e.child("except") != null) throw UnsupportedXcsp3Exception("allEqual with <except>")
    val vars = refList(listText(e)).toIntArray()
    for (i in 0 until vars.size - 1) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), LinearOp.EQ, 0))
    }
}

/** `minimum`/`maximum` of a list constrained by a condition, via [ArrayMinMax] + the condition. */
internal fun Xcsp3.Builder.minMax(e: XmlElement, max: Boolean) {
    // Entries may be plain variables or expressions (e.g. `sub(y,37)`), each resolved to an int var.
    val vars = requireNotNull(e.child("list")).textContent.trim()
        .split(Regex("\\s+")).filter { it.isNotBlank() }
        .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
    val m = newAuxVar(domainMin(vars), domainMin(vars) + domainSpan(vars) - 1)
    factors.add(ArrayMinMax(result = m, xs = vars, max = max))
    postCondition(intArrayOf(1), intArrayOf(m), requireNotNull(e.child("condition")).textContent.trim())
}

/** Global cardinality: each value in `<values>` occurs a fixed count, an interval, or a
 *  variable count (`<occurs>`) among the listed variables. */
internal fun Xcsp3.Builder.cardinality(e: XmlElement) {
    val vars = listVars(e)
    val valuesEl = requireNotNull(e.child("values"))
    val values = (
        parseInts(valuesEl.textContent)
            ?: throw UnsupportedXcsp3Exception("cardinality: non-constant <values>")
        ).widenToLong()
    // closed="true" additionally forbids any variable taking a value outside <values>.
    val closed = valuesEl.attr("closed").equals("true", ignoreCase = true)
    val occursText = requireNotNull(e.child("occurs")).textContent.trim()
    val occTokens = occursText.split(Regex("\\s+")).filter { it.isNotBlank() }
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
            val occVars = refList(occursText).toIntArray()
            require(occVars.size == values.size) { "cardinality: <values>/<occurs> length mismatch" }
            factors.add(GlobalCardinality(xs = vars, cover = values, countVars = occVars, closed = closed))
        }
    }
}

/** Reify `x == value` onto a fresh 0/1 int var. */
internal fun Xcsp3.Builder.eqValue01(x: Int, value: Long): Int {
    val eq = newBool()
    factors.add(ReifiedLinear(eq, longArrayOf(1), intArrayOf(x), LinearOp.EQ, value))
    val ch = newAuxVar(0L, 1L)
    factors.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(ch), LinearOp.EQ, 1))
    return ch
}

/** `binPacking`: item `i` goes to bin `list[i]`; each bin's total item size meets the condition. */
@Suppress("ThrowsCount") // one guard per unsupported shape across the loads/limits/condition forms
internal fun Xcsp3.Builder.binPacking(e: XmlElement) {
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
            val loadVars = loadsEl.textContent.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .flatMap { tok -> expandNames(tok).map { termVar(it) } }.toIntArray()
            if (loadVars.size.toLong() * items.size > negTableCap) {
                throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
            }
            for (b in loadVars.indices) {
                val ind = IntArray(items.size) { i -> eqValue01(items[i], (b + offset).toLong()) }
                factors.add(Linear(sizes + -1, ind + loadVars[b], LinearOp.EQ, 0))
            }
        }

        // `<limits>`: each bin `b` has its own constant capacity — `Σ size[i]·[list[i]=b] ≤ limits[b]`.
        e.child("limits") != null -> {
            val limits = parseInts(e.child("limits")?.textContent)
                ?: throw UnsupportedXcsp3Exception("binPacking: non-constant <limits>")
            if (limits.size.toLong() * items.size > negTableCap) {
                throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
            }
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
            if ((hiBin - loBin + 1) * items.size > negTableCap) {
                throw UnsupportedXcsp3Exception("binPacking: decomposition exceeds cap")
            }
            for (b in loBin..hiBin) {
                val ind = IntArray(items.size) { i -> eqValue01(items[i], b) }
                postCondition(sizes.copyOf(), ind, condText)
            }
        }

        else -> throw UnsupportedXcsp3Exception("binPacking: neither <condition> nor <loads>")
    }
}

/** `knapsack`: a weighted-sum condition on item weights and one on item profits. */
internal fun Xcsp3.Builder.knapsack(e: XmlElement) {
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
internal fun Xcsp3.Builder.nValues(e: XmlElement) {
    val except = (parseInts(e.child("except")?.textContent)?.toSet()).orEmpty()
    val cnt = distinctCountVar(listVars(e), except)
    postCondition(intArrayOf(1), intArrayOf(cnt), requireNotNull(e.child("condition")).textContent.trim())
}

/** A fresh int var equal to the count of distinct values taken across [vars], decomposed as
 *  `Σ used[v]` where `used[v] = 1` iff some variable equals `v`. Values in [except] are not
 *  counted (XCSP3 `nValues` with `<except>`). */
internal fun Xcsp3.Builder.distinctCountVar(vars: IntArray, except: Set<Int> = emptySet()): Int {
    val loV = vars.minOf { domains[it].min }
    val hiV = vars.maxOf { domains[it].max }
    if ((hiV - loV + 1) * vars.size > negTableCap) {
        throw UnsupportedXcsp3Exception("nValues: value range too large to decompose")
    }
    val used = ArrayList<Int>()
    for (v in loV..hiV) {
        val vi = v.toInt()
        if (vi in except) continue
        val eqLits = vars.map { reifyLinear(intArrayOf(1), intArrayOf(it), LinearOp.EQ, vi) }
        used.add(litTo01(tseitinOr(eqLits)))
    }
    val cnt = newAuxVar(0L, used.size.toLong())
    val coeffs = IntArray(used.size + 1) { if (it < used.size) 1 else -1 }
    factors.add(Linear(coeffs, (used + cnt).toIntArray(), LinearOp.EQ, 0))
    return cnt
}

/** Value precedence: for each consecutive pair `(s, t)` in `<values>`, value `s` must first
 *  occur before `t` — `t` at position `j` requires some `s` at an earlier position, and `t`
 *  cannot occupy position 0. With no `<values>`, the chain runs over the sorted union of the
 *  variables' domain values. */
internal fun Xcsp3.Builder.precedence(e: XmlElement) {
    if (e.attr("covered").equals("true", ignoreCase = true)) {
        throw UnsupportedXcsp3Exception("precedence: covered form")
    }
    // The list may be a <list> child or, in the symmetry-breaking shorthand, direct content.
    val vars = refList(listText(e)).toIntArray()
    if (vars.isEmpty()) return
    val values = parseInts(e.child("values")?.textContent)
        ?: vars.flatMap { domainValues(it) }.distinct().sorted().toIntArray()
    if (values.size < 2) return
    if (values.size.toLong() * vars.size * vars.size > negTableCap) {
        throw UnsupportedXcsp3Exception("precedence: too large to decompose")
    }
    val eqLits = HashMap<Long, Int>()
    fun eqLit(j: Int, v: Int) = eqLits.getOrPut(j.toLong() shl 32 or (v.toLong() and 0xffffffffL)) {
        reifyLinear(intArrayOf(1), intArrayOf(vars[j]), LinearOp.EQ, v)
    }
    for (i in 0 until values.size - 1) {
        val s = values[i]
        val t = values[i + 1]
        factors.add(Clause(intArrayOf(Lit.negate(eqLit(0, t))))) // t may not occupy position 0
        for (j in 1 until vars.size) {
            // x[j] = t ⇒ some earlier variable equals s
            val clause = IntArray(j + 1) { if (it == 0) Lit.negate(eqLit(j, t)) else eqLit(it - 1, s) }
            factors.add(Clause(clause))
        }
    }
}

/** 1-D no-overlap (disjunctive): tasks with constant durations share a unit resource, so at
 *  most one runs at a time — a [Cumulative] with unit heights and capacity 1. */
@Suppress("ThrowsCount") // one guard per unsupported noOverlap shape
internal fun Xcsp3.Builder.noOverlap(e: XmlElement) {
    val originsText = requireNotNull(e.child("origins")).textContent
    if ('(' in originsText) return noOverlapMulti(e, originsText)
    val starts = refList(originsText).toIntArray()
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
internal fun Xcsp3.Builder.noOverlapMulti(e: XmlElement, originsText: String) {
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
    if (origins.size.toLong() * origins.size * nDim > negTableCap) {
        throw UnsupportedXcsp3Exception("noOverlap: decomposition exceeds cap")
    }
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
internal fun Xcsp3.Builder.reifyLe3(a: Int, b: Int, c: Int): Int =
    reifyLinear(intArrayOf(1, 1, -1), intArrayOf(a, b, c), LinearOp.LE, 0)

/** Parse `(t,t,…)(t,t,…)…` tuple rows, resolving each entry with [resolve]. */
internal fun Xcsp3.Builder.tupleRows(text: String, resolve: (String) -> Int): List<IntArray> =
    Regex("""\(([^)]*)\)""").findAll(text.trim())
        .map { m -> m.groupValues[1].split(",").map { resolve(it.trim()) }.toIntArray() }
        .toList()

internal fun Xcsp3.Builder.allDifferent(e: XmlElement) {
    val vars = refList(listText(e)).toIntArray()
    if (vars.isEmpty()) throw UnsupportedXcsp3Exception("allDifferent: empty list")
    // <except> weakens the constraint: variables taking an exempt value may repeat.
    e.child("except")?.let { exceptEl ->
        val except = parseInts(exceptEl.textContent)
            ?: throw UnsupportedXcsp3Exception("allDifferent: non-constant <except>")
        if (except.isNotEmpty()) return allDifferentExcept(vars, except)
    }
    // A value span beyond Int range would truncate AllDifferent's Int-sized value-indexed
    // scratch; decompose to pairwise != (sound at any magnitude — <except> was rejected above).
    if (domainSpan(vars) > Int.MAX_VALUE.toLong()) {
        for (a in vars.indices) {
            for (b in a + 1 until vars.size) {
                factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[a], vars[b]), LinearOp.NE, 0))
            }
        }
        return
    }
    factors.add(
        AllDifferent(
            vars = vars,
            domainMin = domainMin(vars),
            domainSize = domainSpan(vars).toInt(),
        ),
    )
}

/** `allDifferent` with `<except>`: variables must be pairwise distinct unless they take an exempt
 *  value. Decomposed as `x[i] = x[j] ⟹ x[i] ∈ except` per pair — two equal variables share a value,
 *  so it suffices to require that common value be exempt (one membership guard, symmetric). */
internal fun Xcsp3.Builder.allDifferentExcept(vars: IntArray, except: IntArray) {
    if (vars.size.toLong() * vars.size * except.size > negTableCap) {
        throw UnsupportedXcsp3Exception("allDifferent: except decomposition exceeds cap")
    }
    // x[i] ∈ except, reused across every pair sharing i.
    val inExcept = IntArray(vars.size) { i ->
        tseitinOr(except.map { reifyLinear(intArrayOf(1), intArrayOf(vars[i]), LinearOp.EQ, it) })
    }
    for (a in vars.indices) {
        for (b in a + 1 until vars.size) {
            val eq = reifyLinear(intArrayOf(1, -1), intArrayOf(vars[a], vars[b]), LinearOp.EQ, 0)
            factors.add(Clause(intArrayOf(Lit.negate(eq), inExcept[a]))) // x[a]=x[b] ⟹ x[a] ∈ except
        }
    }
}
