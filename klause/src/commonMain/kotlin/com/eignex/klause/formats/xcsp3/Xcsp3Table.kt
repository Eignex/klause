package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit
import com.eignex.util.arrays.binarySearchLong
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet

// A tuple column may be a value, a `*` wildcard, or a `lo..hi` range. Positive tables keep these as short-support
// cells and negative tables lower to one nogood per forbidden tuple, so both stay linear in the written tuples
// rather than in the Cartesian product the wildcards denote.

internal fun Compiler.Builder.extension(e: XmlElement) {
    val vars = listVars(e)
    // Untrimmed: the support template cache keys on the String object a group's rows share.
    val supports = e.child("supports")?.textContent
    val conflicts = e.child("conflicts")?.textContent
    when {
        supports != null && supports.isBlank() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

        conflicts != null && conflicts.isBlank() -> Unit

        supports != null -> postSupportTable(vars, supports)

        conflicts != null -> postConflicts(vars, conflicts)

        else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
    }
}

// Selects the representation, not a size limit: below it a negative table is complemented to a positive Table
// for GAC-strength STR2 propagation; above it, nogood clauses avoid building an astronomical complement.
private const val COMPLEMENT_MATERIALIZE_CEILING = 1_000_000L

// GAC matters for dense binary negative tables, where the forward checking of nogood clauses is far too weak.
// STR2: C. Lecoutre, "STR2: optimized simple tabular reduction for table constraints", Constraints 16(4),
// 2011, https://doi.org/10.1007/s10601-011-9107-6.
internal fun Compiler.Builder.postConflicts(vars: IntArray, text: String) {
    val rows = parseShortRows(text, vars.size)
    var product = 1L
    for (v in vars) {
        val values = domains[v].spanOrNull(COMPLEMENT_MATERIALIZE_CEILING / product)
            ?: return postConflictClauses(vars, rows)
        product *= values.size
    }
    postConflictComplement(vars, rows)
}

internal fun Compiler.Builder.postConflictComplement(vars: IntArray, rows: ShortRows) {
    val arity = vars.size
    val doms = vars.map { v -> domains[v].span().let { values -> LongArray(values.size) { values.valueAt(it) } } }
    val nRows = rows.lo.size / arity

    // A tuple of per-column domain indices encodes to one mixed-radix Long, maintained incrementally down the
    // recursion, so no tuple is boxed per row or per enumerated point.
    val stride = LongArray(arity)
    var acc = 1L
    for (c in 0 until arity) {
        stride[c] = acc
        acc *= doms[c].size
    }

    // Ground forbidden rows go in the code set; rows with a `*`/range cell are scanned per combination.
    // A ground row carrying a value outside its column's domain matches no combination, so it is dropped.
    val forbiddenPoints = LongHashSet()
    val intervalRows = ArrayList<Int>()
    for (r in 0 until nRows) {
        val point = (0 until arity).all { rows.lo[r * arity + it] == rows.hi[r * arity + it] }
        if (!point) {
            intervalRows.add(r)
            continue
        }
        var code = 0L
        var inDomain = true
        for (c in 0 until arity) {
            val idx = doms[c].binarySearchLong(rows.lo[r * arity + c])
            if (idx < 0) {
                inDomain = false
                break
            }
            code += idx * stride[c]
        }
        if (inDomain) forbiddenPoints.add(code)
    }

    val allowed = LongArrayList()
    var count = 0
    val combo = LongArray(arity)
    fun matchesInterval(): Boolean {
        for (r in intervalRows) {
            if ((0 until arity).all { combo[it] in rows.lo[r * arity + it]..rows.hi[r * arity + it] }) {
                return true
            }
        }
        return false
    }
    fun rec(p: Int, code: Long) {
        if (p == arity) {
            if (code !in forbiddenPoints && !matchesInterval()) {
                for (c in 0 until arity) allowed.add(combo[c])
                count++
            }
            return
        }
        val dom = doms[p]
        val s = stride[p]
        for (idx in dom.indices) {
            combo[p] = dom[idx]
            rec(p + 1, code + idx * s)
        }
    }
    rec(0, 0L)
    if (count == 0) {
        factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
        return
    }
    factors.add(Table(xs = vars, tuples = allowed.toLongArray()))
}

// Shared across a group's rows. [hi] is null for a fully ground table; [triviallySat] flags a fully unbounded
// row, which matches every assignment.
internal class SupportTemplate(val triviallySat: Boolean, val tuples: LongArray, val hi: LongArray?)

@Suppress("AvoidReferentialEquality")
private inline fun Compiler.Builder.supportTemplateFor(text: String, compute: () -> SupportTemplate): SupportTemplate {
    cachedSupportTemplate?.let { if (text === cachedSupportsText) return it }
    val built = compute()
    cachedSupportsText = text
    cachedSupportTemplate = built
    return built
}

internal fun Compiler.Builder.postSupportTable(vars: IntArray, text: String) {
    val arity = vars.size
    val tpl = supportTemplateFor(text) { buildSupportTemplate(arity, text) }
    if (tpl.triviallySat) return
    factors.add(Table(xs = vars, tuples = tpl.tuples, hi = tpl.hi))
}

private fun Compiler.Builder.buildSupportTemplate(arity: Int, text: String): SupportTemplate {
    val rows = parseShortRows(text, arity)
    val n = rows.lo.size / arity
    for (r in 0 until n) {
        var allFree = true
        for (c in 0 until arity) {
            if (!(rows.lo[r * arity + c] == Long.MIN_VALUE && rows.hi[r * arity + c] == Long.MAX_VALUE)) {
                allFree = false
                break
            }
        }
        if (allFree) return SupportTemplate(triviallySat = true, tuples = LongArray(0), hi = null)
    }
    return SupportTemplate(triviallySat = false, tuples = rows.lo, hi = if (rows.short) rows.hi else null)
}

// Each nogood is the disjunction of every column differing from its cell; a `*` column contributes nothing.
internal fun Compiler.Builder.postConflictClauses(vars: IntArray, rows: ShortRows) {
    val arity = vars.size
    val n = rows.lo.size / arity
    for (r in 0 until n) {
        val lits = IntArrayList()
        for (c in 0 until arity) {
            val lo = rows.lo[r * arity + c]
            val hiC = rows.hi[r * arity + c]
            when {
                // `*`: forbids regardless — omit
                lo == Long.MIN_VALUE && hiC == Long.MAX_VALUE -> Unit

                lo == hiC -> lits.add(
                    Lit.negate(reifyLinear(longArrayOf(1), intArrayOf(vars[c]), LinearOp.EQ, lo)),
                )

                else -> {
                    if (lo != Long.MIN_VALUE) {
                        lits.add(reifyLinear(longArrayOf(1), intArrayOf(vars[c]), LinearOp.LE, lo - 1))
                    }
                    if (hiC != Long.MAX_VALUE) {
                        lits.add(reifyLinear(longArrayOf(1), intArrayOf(vars[c]), LinearOp.GE, hiC + 1))
                    }
                }
            }
        }
        if (lits.isEmpty()) {
            // A fully unbounded forbidden tuple rules out every assignment ⇒ unsatisfiable.
            factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
            return
        }
        factors.add(Clause(lits.toIntArray()))
    }
}

// Row-major inclusive cell bounds. A ground table carries no second array, since a multi-MB table would otherwise
// pay a full duplicate; [hi] is then [lo]. Both arrays are read-only.
internal class ShortRows(val lo: LongArray, private val intervals: LongArray?) {
    val hi: LongArray get() = intervals ?: lo

    val short: Boolean get() = intervals != null
}

// Unary tables may use the bare-value form (`0 1 2`, no parentheses).
internal fun Compiler.Builder.parseShortRows(text: String, arity: Int): ShortRows {
    val bare = arity == 1 && '(' !in text
    // Size the cell arrays exactly to avoid transient copies from growable lists. Materialize `hi` only
    // after an interval cell appears, back-filling the points already read.
    val cells = if (bare) countBareCells(text) else countTuples(text) * arity
    val lo = LongArray(cells)
    var intervals: LongArray? = null
    var k = 0

    fun put(cellLo: Long, cellHi: Long) {
        require(cellLo <= cellHi) { "invalid table interval $cellLo..$cellHi" }
        lo[k] = cellLo
        if (cellHi != cellLo) {
            val hi = intervals ?: LongArray(cells).also { fresh ->
                lo.copyInto(fresh, 0, 0, k) // every earlier cell was a point, so its hi equals its lo
                intervals = fresh
            }
            hi[k] = cellHi
        } else {
            intervals?.set(k, cellHi)
        }
        k++
    }

    // A cell is read from the source in place (no substring per field): a `*` wildcard, a `lo..hi`
    // interval, or a point value. Parsing dominates ingestion of a multi-MB table, so it allocates no
    // MatchResult per tuple nor String per field.
    fun addCell(from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (b - a == 1 && text[a] == '*') {
            put(Long.MIN_VALUE, Long.MAX_VALUE)
            return
        }
        // Single pass: parse the first integer, stopping at the first non-digit. If that is `..` the cell
        // is a `lo..hi` interval; otherwise it is a point value already fully read, so a point cell (the
        // bulk of a numeric table) is scanned once.
        var p = a
        val neg = p < b && text[p] == '-'
        if (neg || (p < b && text[p] == '+')) p++
        require(p < b && text[p] in '0'..'9') { "empty integer in table tuple" }
        var v = 0L
        while (p < b && text[p] in '0'..'9') {
            val digit = text[p] - '0'
            require(v >= Long.MIN_VALUE / 10) { "table integer exceeds the supported 64-bit range" }
            v *= 10
            require(v >= Long.MIN_VALUE + digit) { "table integer exceeds the supported 64-bit range" }
            v -= digit
            p++
        }
        require(neg || v != Long.MIN_VALUE) { "table integer exceeds the supported 64-bit range" }
        val first = if (neg) v else -v
        if (p >= b) {
            put(first, first)
        } else {
            require(p + 1 < b && text[p] == '.' && text[p + 1] == '.') { "non-digit '${text[p]}' in table tuple" }
            put(first, parseLongIn(text, p + 2, b))
        }
    }

    val n = text.length
    if (bare) {
        var i = 0
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            addCell(start, i)
        }
    } else {
        var i = 0
        while (true) {
            while (i < n && text[i] != '(') i++
            if (i >= n) break
            i++
            var cellStart = i
            var cells = 0
            while (i < n && text[i] != ')') {
                if (text[i] == ',') {
                    addCell(cellStart, i)
                    cells++
                    cellStart = i + 1
                }
                i++
            }
            addCell(cellStart, i)
            cells++
            if (cells != arity) throw UnsupportedXcsp3Exception("tuple arity $cells != $arity")
            if (i < n) i++ // past ')'
        }
    }
    // A malformed table can produce fewer cells than the count promised; trim rather than leave zeros.
    return if (k == cells) ShortRows(lo, intervals) else ShortRows(lo.copyOf(k), intervals?.copyOf(k))
}

private fun countTuples(text: String): Int {
    var count = 0
    for (c in text) if (c == '(') count++
    return count
}

private fun countBareCells(text: String): Int {
    var count = 0
    var inToken = false
    for (c in text) {
        val space = c.isWhitespace()
        if (!space && !inToken) count++
        inToken = !space
    }
    return count
}

// Parse a base-10 [Long] from `text[from, to)` (an optional sign then digits), without a substring.
private fun parseLongIn(text: String, from: Int, to: Int): Long {
    var a = from
    val neg = a < to && text[a] == '-'
    if (neg || (a < to && text[a] == '+')) a++
    require(a < to) { "empty integer in table tuple" }
    var v = 0L
    while (a < to) {
        val c = text[a]
        require(c in '0'..'9') { "non-digit '$c' in table tuple" }
        val digit = c - '0'
        require(v >= Long.MIN_VALUE / 10) { "table integer exceeds the supported 64-bit range" }
        v *= 10
        require(v >= Long.MIN_VALUE + digit) { "table integer exceeds the supported 64-bit range" }
        v -= digit
        a++
    }
    require(neg || v != Long.MIN_VALUE) { "table integer exceeds the supported 64-bit range" }
    return if (neg) v else -v
}

internal inline fun forEachTuple(text: String, cell: (String) -> Unit, endRow: () -> Unit) {
    val n = text.length
    var i = 0
    while (true) {
        while (i < n && text[i] != '(') i++
        if (i >= n) break
        i++
        var start = i
        while (i < n && text[i] != ')') {
            if (text[i] == ',') {
                cell(text.substring(start, i).trim())
                start = i + 1
            }
            i++
        }
        cell(text.substring(start, i).trim())
        if (i < n) i++ // past ')'
        endRow()
    }
}
