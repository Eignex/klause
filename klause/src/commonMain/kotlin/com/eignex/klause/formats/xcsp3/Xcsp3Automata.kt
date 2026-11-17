package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.LayeredMddData
import com.eignex.klause.lowering.packLayeredMdd
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList

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

// Src/symbol/dst columns of a regular/mdd transition list, states interned to dense ids `1..[numStates]`.
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
 *  footprint of a multi-million-transition MDD are dominated by exactly those. Whitespace
 *  around the fields is tolerated as the grammar allows; the fields themselves carry no separators. */
private fun internTransitions(text: String, firstState: String? = null): InternedTransitions {
    val n = text.length
    val interner = StateInterner(n / 8) // ~1 state per short transition tuple, an over-estimate
    if (firstState != null) interner.internWhole(firstState) // the start state must hold id 1
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
 *  every row of a group over one template. [compute] runs only on a miss; a throw is not cached.
 *  Referential equality is the intent: group rows share one `<transitions>` String object, and distinct
 *  constraints hold distinct objects, so `===` reuses within a group and never conflates unrelated text. */
@Suppress("AvoidReferentialEquality")
private inline fun Compiler.Builder.automatonFor(text: String, compute: () -> RegularAutomaton): RegularAutomaton {
    cachedAutomaton?.let { if (text === cachedAutomatonText) return it }
    val built = compute()
    cachedAutomatonText = text
    cachedAutomaton = built
    return built
}

// Assemble the transition table from interned transitions (symbols shifted to 1-based columns).
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

internal fun Compiler.Builder.regular(e: XmlElement) {
    val text = requireNotNull(e.child("transitions")).textContent
    val automaton = automatonFor(text) {
        // The start state is interned first (id 1), then the transitions, then any final absent from
        // them, so state ids follow first-occurrence order in the source.
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
private inline fun Compiler.Builder.mddResultFor(text: String, compute: () -> MddResult): MddResult {
    cachedMddResult?.let { if (text === cachedMddText) return it }
    val built = compute()
    cachedMddText = text
    cachedMddResult = built
    return built
}

/** A multi-valued decision diagram is a layered automaton. It is lowered onto the native layered `Mdd`
 *  factor when the diagram is cleanly layered — every state at one depth from the root, all sinks at the
 *  final depth — and falls back to a flattened [Regular] automaton otherwise. */
internal fun Compiler.Builder.mdd(e: XmlElement) {
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

// Flattened-DFA fallback: a [Regular] automaton over the mdd's states (the sinks are the accepting set).
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
    val queue = IntArrayDeque()
    queue.addLast(root)
    while (queue.isNotEmpty()) {
        val s = queue.removeFirst()
        val nl = layer[s] + 1
        for (i in outStart[s] until outStart[s + 1]) {
            val d = adjDst[i]
            if (layer[d] == -1) {
                layer[d] = nl
                if (nl > maxLayer) maxLayer = nl
                queue.addLast(d)
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
private fun Compiler.Builder.emitRegular(seqVars: IntArray, automaton: RegularAutomaton) {
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
