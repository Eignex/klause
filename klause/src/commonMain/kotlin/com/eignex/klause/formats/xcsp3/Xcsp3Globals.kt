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
import com.eignex.klause.formats.LayeredMddData
import com.eignex.klause.formats.packLayeredMdd
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

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
    val idxTokens = requireNotNull(e.child("index")).textContent.splitWs()
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
            val f = refList(lists[0].textContent)
            factors.add(Inverse(f = f, g = f))
        }

        2 -> {
            val f = refList(lists[0].textContent)
            val g = refList(lists[1].textContent)
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

/** Dense interner assigning state names int ids `1..n` in first-occurrence order. Keyed on char ranges
 *  of the transitions text so a repeated state name — the common case in a large MDD — costs neither a
 *  `String` allocation nor a per-transition object; a name is materialized only the first time it is
 *  seen. Hashing uses its own range hash (not [String.hashCode], which is not guaranteed identical
 *  across Kotlin targets) so [intern] and [idOf] agree on every platform. */
private class StateInterner(sizeHint: Int) {
    private var slotKey = arrayOfNulls<String>(tableSize(sizeHint))
    private var slotId = IntArray(slotKey.size)
    private var mask = slotKey.size - 1
    private var count = 0

    val size get() = count

    /** Intern the range `text[from, to)` (already trimmed by the caller), returning its id. */
    fun intern(text: String, from: Int, to: Int): Int {
        var slot = hash(text, from, to) and mask
        while (true) {
            val k = slotKey[slot] ?: return insert(slot, text.substring(from, to))
            if (rangeEquals(k, text, from, to)) return slotId[slot]
            slot = (slot + 1) and mask
        }
    }

    /** Intern a whole name (a `<start>`/`<final>` state, which may be absent from the transitions). */
    fun internWhole(name: String): Int = intern(name, 0, name.length)

    /** Existing id of [name], or -1 if never interned. */
    fun idOf(name: String): Int {
        var slot = hash(name, 0, name.length) and mask
        while (true) {
            val k = slotKey[slot] ?: return -1
            if (k == name) return slotId[slot]
            slot = (slot + 1) and mask
        }
    }

    private fun insert(slot: Int, name: String): Int {
        slotKey[slot] = name
        val id = ++count
        slotId[slot] = id
        if (count * 3 >= slotKey.size * 2) grow()
        return id
    }

    private fun grow() {
        val oldKey = slotKey
        val oldId = slotId
        slotKey = arrayOfNulls(oldKey.size shl 1)
        slotId = IntArray(slotKey.size)
        mask = slotKey.size - 1
        for (j in oldKey.indices) {
            val k = oldKey[j] ?: continue
            var slot = hash(k, 0, k.length) and mask
            while (slotKey[slot] != null) slot = (slot + 1) and mask
            slotKey[slot] = k
            slotId[slot] = oldId[j]
        }
    }

    private companion object {
        fun tableSize(sizeHint: Int): Int {
            var cap = 16
            val want = sizeHint + (sizeHint shr 1) + 1 // ~1.5x headroom to keep the load factor low
            while (cap in 1 until want) cap = cap shl 1
            return cap
        }

        fun hash(s: String, from: Int, to: Int): Int {
            var h = 0
            for (i in from until to) h = 31 * h + s[i].code
            return h
        }

        fun rangeEquals(k: String, s: String, from: Int, to: Int): Boolean {
            if (k.length != to - from) return false
            for (i in k.indices) if (k[i] != s[from + i]) return false
            return true
        }
    }
}

/** Src/symbol/dst columns of a regular/mdd transition list, states interned to dense ids `1..[numStates]`. */
private class InternedTransitions(
    val numStates: Int,
    val idOf: (String) -> Int,
    val srcIds: IntArray,
    val symbols: IntArray,
    val dstIds: IntArray,
)

private fun trimStart(s: String, from: Int, to: Int): Int {
    var a = from
    while (a < to && s[a].isWhitespace()) a++
    return a
}

private fun trimEnd(s: String, from: Int, to: Int): Int {
    var b = to
    while (b > from && s[b - 1].isWhitespace()) b--
    return b
}

private fun parseSym(s: String, from: Int, to: Int): Int {
    var a = from
    val neg = a < to && s[a] == '-'
    if (neg || (a < to && s[a] == '+')) a++
    require(a < to) { "empty symbol in transition" }
    var v = 0
    while (a < to) {
        val c = s[a]
        require(c in '0'..'9') { "non-digit '$c' in transition symbol" }
        v = v * 10 + (c - '0')
        a++
    }
    return if (neg) -v else v
}

/** Parse `(src,sym,dst)(...)…` in a single scan, interning states to ids as it goes. Neither a
 *  per-transition object nor a `String` per repeated state is allocated — the ingestion time and heap
 *  footprint of a multi-million-transition MDD (WordDesign2) are dominated by exactly those. Whitespace
 *  around the fields is tolerated as the grammar allows; the fields themselves carry no separators. */
private fun internTransitions(text: String, firstState: String? = null): InternedTransitions {
    val n = text.length
    val interner = StateInterner(n / 8) // ~1 state per short transition tuple, an over-estimate
    if (firstState != null) interner.internWhole(firstState) // pin its id to 1 (matches the old numbering)
    val srcIds = IntArrayList()
    val symbols = IntArrayList()
    val dstIds = IntArrayList()
    var i = 0
    while (true) {
        while (i < n && text[i] != '(') i++
        if (i >= n) break
        i++
        val srcEnd = text.indexOf(',', i)
        val symEnd = text.indexOf(',', srcEnd + 1)
        val dstEnd = text.indexOf(')', symEnd + 1)
        require(srcEnd in 0 until symEnd && dstEnd > symEnd) { "malformed transition near $i" }
        srcIds.add(interner.intern(text, trimStart(text, i, srcEnd), trimEnd(text, i, srcEnd)))
        symbols.add(parseSym(text, trimStart(text, srcEnd + 1, symEnd), trimEnd(text, srcEnd + 1, symEnd)))
        dstIds.add(interner.intern(text, trimStart(text, symEnd + 1, dstEnd), trimEnd(text, symEnd + 1, dstEnd)))
        i = dstEnd + 1
    }
    return InternedTransitions(
        interner.size,
        interner::idOf,
        srcIds.toIntArray(),
        symbols.toIntArray(),
        dstIds.toIntArray(),
    )
}

/** A regular/mdd automaton built from a `<transitions>` list, independent of the sequence it constrains:
 *  the symbol [offset] (`1 - minSym`) shifting symbols to the 1-based [transitions] table, plus [q0] and
 *  [accepting]. Cached and shared across the rows of a `<group>` that instantiate the same template. */
internal class RegularAutomaton(
    val numStates: Int,
    val alphabetSize: Int,
    val offset: Int,
    val transitions: LongArray,
    val q0: Int,
    val accepting: IntArray,
)

/** Return the automaton for [text], reusing the last one when [text] is the same object — the case for
 *  every row of a group over one template. [compute] runs only on a miss; a throw is not cached. */
// Referential equality is the intent: group rows share one `<transitions>` String object, and distinct
// constraints hold distinct objects, so `===` reuses within a group and never conflates unrelated text.
@Suppress("AvoidReferentialEquality")
private inline fun Xcsp3.Builder.automatonFor(text: String, compute: () -> RegularAutomaton): RegularAutomaton {
    cachedAutomaton?.let { if (text === cachedAutomatonText) return it }
    val built = compute()
    cachedAutomatonText = text
    cachedAutomaton = built
    return built
}

/** Assemble the transition table from interned transitions (symbols shifted to 1-based columns). */
private fun buildAutomaton(trs: InternedTransitions, numStates: Int, q0: Int, accepting: IntArray): RegularAutomaton {
    var minSym = trs.symbols[0]
    var maxSym = trs.symbols[0]
    for (k in 1 until trs.symbols.size) {
        val v = trs.symbols[k]
        if (v < minSym) minSym = v
        if (v > maxSym) maxSym = v
    }
    val offset = 1 - minSym
    val s = maxSym - minSym + 1
    val table = LongArray(numStates * s) // 0 = dead state
    for (k in trs.symbols.indices) {
        table[(trs.srcIds[k] - 1) * s + (trs.symbols[k] + offset - 1)] = trs.dstIds[k].toLong()
    }
    return RegularAutomaton(numStates, s, offset, table, q0, accepting)
}

internal fun Xcsp3.Builder.regular(e: XmlElement) {
    val text = requireNotNull(e.child("transitions")).textContent
    val automaton = automatonFor(text) {
        // The start state is interned first (id 1), then the transitions, then any final absent from
        // them — the original insertion order, so the built automaton (hence the search) matches.
        val start = requireNotNull(e.child("start")).textContent.trim()
        val trs = internTransitions(text, start)
        if (trs.symbols.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: no transitions")
        val finals = requireNotNull(e.child("final")).textContent.splitWs()
        val extra = HashMap<String, Int>()
        fun resolve(name: String): Int {
            val id = trs.idOf(name)
            if (id != -1) return id
            return extra.getOrPut(name) { trs.numStates + extra.size + 1 }
        }
        val q0 = resolve(start)
        val accepting = IntArray(finals.size) { resolve(finals[it]) }
        buildAutomaton(trs, trs.numStates + extra.size, q0, accepting)
    }
    emitRegular(listVars(e), automaton)
}

/** Either the layered form of an `<mdd>` (when the diagram is cleanly layered) or null, signalling the
 *  [Regular] fallback. Cached by transitions-text identity like [RegularAutomaton]. */
internal class MddResult(val layered: LayeredMddData?)

/** Return the mdd lowering for [text], reusing the last one when [text] is the same object (a group). */
@Suppress("AvoidReferentialEquality")
private inline fun Xcsp3.Builder.mddResultFor(text: String, compute: () -> MddResult): MddResult {
    cachedMddResult?.let { if (text === cachedMddText) return it }
    val built = compute()
    cachedMddText = text
    cachedMddResult = built
    return built
}

/** A multi-valued decision diagram is a layered automaton. It is lowered onto the native layered `Mdd`
 *  factor when the diagram is cleanly layered — every state at one depth from the root, all sinks at the
 *  final depth — and falls back to a flattened [Regular] automaton otherwise. */
internal fun Xcsp3.Builder.mdd(e: XmlElement) {
    val text = requireNotNull(e.child("transitions")).textContent
    val seq = listVars(e)
    val layered = mddResultFor(text) {
        val trs = internTransitions(text)
        if (trs.symbols.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: no transitions")
        MddResult(layerMdd(trs))
    }.layered
    if (layered != null && layered.nLayers == seq.size) {
        factors.add(layered.toMdd(seq))
        return
    }
    val automaton = automatonFor(text) { buildMddAutomaton(internTransitions(text)) }
    emitRegular(seq, automaton)
}

/** The mdd root: the state named "root" if it is a source, else the unique source that is never a
 *  destination; -1 when there is none. */
private fun mddRoot(trs: InternedTransitions, isSrc: BooleanArray, isDst: BooleanArray): Int {
    val rootId = trs.idOf("root")
    if (rootId != -1 && isSrc[rootId]) return rootId
    for (id in 1..trs.numStates) if (isSrc[id] && !isDst[id]) return id
    return -1
}

/** Flattened-DFA fallback: a [Regular] automaton over the mdd's states (the sinks are the accepting set). */
private fun buildMddAutomaton(trs: InternedTransitions): RegularAutomaton {
    val q = trs.numStates
    val isSrc = BooleanArray(q + 1)
    val isDst = BooleanArray(q + 1)
    for (k in trs.srcIds.indices) {
        isSrc[trs.srcIds[k]] = true
        isDst[trs.dstIds[k]] = true
    }
    val q0 = mddRoot(trs, isSrc, isDst)
    if (q0 == -1) throw UnsupportedXcsp3Exception("mdd: no root node")
    val accepting = IntArrayList()
    for (id in 1..q) if (isDst[id] && !isSrc[id]) accepting.add(id)
    return buildAutomaton(trs, q, q0, accepting.toIntArray())
}

/** Try to lower the interned transitions onto a layered `Mdd` (states dense per layer). Returns null when
 *  the diagram is not cleanly layered — no root, a state reachable at two depths, an unreachable state, or
 *  a sink before the final layer — so the caller uses the [Regular] fallback. */
private fun layerMdd(trs: InternedTransitions): LayeredMddData? {
    val q = trs.numStates
    val nEdges = trs.srcIds.size
    val isSrc = BooleanArray(q + 1)
    val isDst = BooleanArray(q + 1)
    for (k in 0 until nEdges) {
        isSrc[trs.srcIds[k]] = true
        isDst[trs.dstIds[k]] = true
    }
    val root = mddRoot(trs, isSrc, isDst)
    if (root == -1) return null

    // CSR adjacency (dst per src) for the layering BFS.
    val outStart = IntArray(q + 2)
    for (k in 0 until nEdges) outStart[trs.srcIds[k] + 1]++
    for (s in 1..q + 1) outStart[s] += outStart[s - 1]
    val cursor = outStart.copyOf()
    val adjDst = IntArray(nEdges)
    for (k in 0 until nEdges) adjDst[cursor[trs.srcIds[k]]++] = trs.dstIds[k]

    val layer = IntArray(q + 1) { -1 }
    layer[root] = 0
    var maxLayer = 0
    val queue = ArrayDeque<Int>()
    queue.add(root)
    while (queue.isNotEmpty()) {
        val s = queue.removeFirst()
        val nl = layer[s] + 1
        for (i in outStart[s] until outStart[s + 1]) {
            val d = adjDst[i]
            if (layer[d] == -1) {
                layer[d] = nl
                if (nl > maxLayer) maxLayer = nl
                queue.add(d)
            } else if (layer[d] != nl) {
                return null // reachable at two depths — not layered
            }
        }
    }
    val nLayers = maxLayer
    for (id in 1..q) {
        if (layer[id] == -1) return null // unreachable from the root
        // A sink (no outgoing edge) must sit at the final layer, else it is a short accepting path.
        if (outStart[id] == outStart[id + 1] && layer[id] != nLayers) return null
    }

    val countPerLayer = IntArray(nLayers + 1)
    val localIdx = IntArray(q)
    for (id in 1..q) {
        localIdx[id - 1] = countPerLayer[layer[id]]
        countPerLayer[layer[id]]++
    }
    val nodeLayer = IntArray(q) { layer[it + 1] }
    val edgeSrc = IntArray(nEdges) { trs.srcIds[it] - 1 }
    val edgeDst = IntArray(nEdges) { trs.dstIds[it] - 1 }
    val edgeSym = LongArray(nEdges) { trs.symbols[it].toLong() }
    val accepting = IntArrayList()
    for (id in 1..q) if (layer[id] == nLayers) accepting.add(id - 1)
    return packLayeredMdd(
        nLayers, countPerLayer, localIdx, nodeLayer,
        edgeSrc, edgeSym, edgeDst, root - 1, accepting.toIntArray(),
    )
}

/** Post a [Regular] factor over [seqVars] for the shared [automaton], allocating per-constraint only the
 *  offset-shifted sequence (and its channels) when the automaton's symbols are not already 1-based. */
private fun Xcsp3.Builder.emitRegular(seqVars: IntArray, automaton: RegularAutomaton) {
    if (seqVars.isEmpty()) throw UnsupportedXcsp3Exception("regular/mdd: empty sequence list")
    val offset = automaton.offset
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
            numStates = automaton.numStates,
            alphabetSize = automaton.alphabetSize,
            transitions = automaton.transitions,
            q0 = automaton.q0,
            accepting = automaton.accepting,
        ),
    )
}

internal fun Xcsp3.Builder.cumulative(e: XmlElement) {
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
            capacity = if (capVar == null) cap.toLong() else domains[capVar].max,
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
internal fun Xcsp3.Builder.taskDims(text: String): Pair<LongArray, IntArray> {
    parseInts(text)?.let { return it.widenToLong() to IntArray(0) }
    val vars = refList(text)
    return LongArray(vars.size) { domains[vars[it]].max } to vars
}

internal fun Xcsp3.Builder.circuit(e: XmlElement) {
    val offset = e.attr("startIndex").ifBlank { "0" }.toInt()
    if (offset != 0) throw UnsupportedXcsp3Exception("circuit: only startIndex=0 supported")
    val succ = refList(listText(e))
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
    val lists = e.children.filter { it.tag == "list" }.map { refList(it.textContent) }
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

/** Rows of a `<matrix>`: explicit `(a,b)(c,d)` tuples, or a 2-D array reference (`x[][]`,
 *  `g[1..8][8..15]`). The reference is reshaped from its two index axes — an empty bracket spans the
 *  array's declared dimension ([Xcsp3.Builder.arrayDims]), a `lo..hi` bracket that inclusive range —
 *  and each cell is referenced directly by index, so no structure is recovered from generated names. */
internal fun Xcsp3.Builder.matrixRows(text: String): List<IntArray> {
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

    ".." in spec -> spec.split("..").let { (lo, hi) ->
        val from = lo.trim().toInt()
        IntArray(hi.trim().toInt() - from + 1) { from + it }
    }

    else -> intArrayOf(spec.toInt())
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
        val lenVars = refList(lengthsEl.textContent)
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
    val vars = refList(listText(e))
    for (i in 0 until vars.size - 1) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[i + 1]), LinearOp.EQ, 0))
    }
}

/** `minimum`/`maximum` of a list constrained by a condition, via [ArrayMinMax] + the condition. */
internal fun Xcsp3.Builder.minMax(e: XmlElement, max: Boolean) {
    // Entries may be plain variables or expressions (e.g. `sub(y,37)`), each resolved to an int var.
    val vars = requireNotNull(e.child("list")).textContent.splitWs()
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
            val loadVars = loadsEl.textContent.splitWs()
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
    val vars = refList(listText(e))
    if (vars.isEmpty()) return
    val values = parseInts(e.child("values")?.textContent)
        ?: vars.flatMap { domainValues(it) }.distinct().sorted().toIntArray()
    if (values.size < 2) return
    if (values.size.toLong() * vars.size * vars.size > negTableCap) {
        throw UnsupportedXcsp3Exception("precedence: too large to decompose")
    }
    val eqLits = MutableLongIntMap()
    fun eqLit(j: Int, v: Int): Int {
        val key = j.toLong() shl 32 or (v.toLong() and 0xffffffffL)
        val existing = eqLits.getOrDefault(key, Int.MIN_VALUE)
        if (existing != Int.MIN_VALUE) return existing
        return reifyLinear(intArrayOf(1), intArrayOf(vars[j]), LinearOp.EQ, v).also { eqLits.put(key, it) }
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
internal fun Xcsp3.Builder.tupleRows(text: String, resolve: (String) -> Int): List<IntArray> {
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
internal fun Xcsp3.Builder.allDifferent(e: XmlElement) {
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

/** Post one all-different over [vars] as an [AllDifferent] factor, or as pairwise `!=` when the
 *  value span would overflow AllDifferent's Int-sized value-indexed scratch (sound at any magnitude).
 *  Fewer than two variables is vacuous and posts nothing. */
internal fun Xcsp3.Builder.postAllDifferent(vars: IntArray) {
    if (vars.size < 2) return
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
