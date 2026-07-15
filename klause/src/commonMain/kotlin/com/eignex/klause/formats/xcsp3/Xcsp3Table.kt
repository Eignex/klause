package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntIntMap

// `<extension>` (positive/negative table) lowering for the XCSP3 front-end — split out of Xcsp3.kt.
//
// A tuple column may be a value, a `*` wildcard, or a `lo..hi` range. Positive tables carry these as
// short-support cells (each written tuple is one row — no Cartesian expansion), and negative tables
// lower to one nogood clause per forbidden tuple. Both avoid the blow-up the old expansion capped.

internal fun Xcsp3.Builder.extension(e: XmlElement) {
    val vars = listVars(e)
    // The raw (untrimmed) text is one shared String object across a group's rows, so the support
    // template cache keys on it by identity; the tuple scan already skips surrounding whitespace.
    val supports = e.child("supports")?.textContent
    val conflicts = e.child("conflicts")?.textContent
    when {
        // No allowed tuple ⇒ the constraint (hence the instance) is unsatisfiable.
        supports != null && supports.isBlank() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

        // No forbidden tuple ⇒ trivially satisfied; post nothing.
        conflicts != null && conflicts.isBlank() -> Unit

        supports != null -> postSupportTable(vars, supports)

        conflicts != null -> postConflicts(vars, conflicts)

        else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
    }
}

/** Post a negative `<conflicts>` table. When the variables' domain Cartesian product is small enough,
 *  complement it to a positive [Table] — STR2 propagates that at GAC strength, which matters for dense
 *  binary negatives (e.g. FRB) where forward checking is far too weak. Otherwise lower to nogood clauses
 *  (forward-checking strength) to avoid materializing a large complement. */
internal fun Xcsp3.Builder.postConflicts(vars: IntArray, text: String) {
    val rows = parseShortRows(text, vars.size)
    var product = 1L
    for (v in vars) {
        product *= domainValues(v).size
        if (product > negTableCap) return postConflictClauses(vars, rows)
    }
    postConflictComplement(vars, rows)
}

/** The allowed complement of a forbidden-tuple table as a positive [Table]: every domain combination
 *  that matches no forbidden row. An empty complement ⇒ every assignment forbidden ⇒ unsatisfiable. */
internal fun Xcsp3.Builder.postConflictComplement(vars: IntArray, rows: ShortRows) {
    val arity = vars.size
    val doms = vars.map { domainValues(it) }
    val nRows = rows.lo.size / arity

    // A tuple of per-column domain indices encodes to a single mixed-radix [Long] in `[0, product)`,
    // where the domain product is bounded (this path is only taken when it fits [negTableCap]). So the
    // ground forbidden rows live in a primitive [LongHashSet] and the enumeration probes it by a code
    // maintained incrementally down the recursion — no boxed tuple per row nor per enumerated point.
    val stride = LongArray(arity)
    var acc = 1L
    for (c in 0 until arity) {
        stride[c] = acc
        acc *= doms[c].size
    }
    val valueIndex = Array(arity) { c ->
        val m = MutableIntIntMap(doms[c].size)
        for (idx in doms[c].indices) m.put(doms[c][idx], idx)
        m
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
            val idx = valueIndex[c].getOrDefault(rows.lo[r * arity + c].toInt(), -1)
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
    val combo = IntArray(arity)
    fun matchesInterval(): Boolean {
        for (r in intervalRows) {
            if ((0 until arity).all { combo[it].toLong() in rows.lo[r * arity + it]..rows.hi[r * arity + it] }) {
                return true
            }
        }
        return false
    }
    fun rec(p: Int, code: Long) {
        if (p == arity) {
            if (code !in forbiddenPoints && !matchesInterval()) {
                for (c in 0 until arity) allowed.add(combo[c].toLong())
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

/** The parsed short-support tuple arrays of a `<supports>` table, shared across a group's rows: the
 *  row-major cell lower bounds [tuples] and, for a short table, the per-cell upper bounds [hi] (null
 *  for a fully-ground table). [triviallySat] flags a table with a fully unbounded row, which matches
 *  every assignment — the constraint posts nothing. */
internal class SupportTemplate(val triviallySat: Boolean, val tuples: LongArray, val hi: LongArray?)

/** Return the support template for [text], reusing the last one when [text] is the same object — the
 *  case for a `<group>`'s rows, which share one `<supports>` String object. */
@Suppress("AvoidReferentialEquality")
private inline fun Xcsp3.Builder.supportTemplateFor(text: String, compute: () -> SupportTemplate): SupportTemplate {
    cachedSupportTemplate?.let { if (text === cachedSupportsText) return it }
    val built = compute()
    cachedSupportsText = text
    cachedSupportTemplate = built
    return built
}

/** Post a positive `<supports>` table as a [Table] factor. `*`/range columns become short-support
 *  cells (an interval `[lo, hi]`; a `*` is `[MIN, MAX]`), so each written tuple is exactly one row.
 *  A fully unbounded row matches every assignment, so the whole constraint is trivially satisfied. The
 *  parsed tuple arrays are cached by text identity and shared across a group's rows. */
internal fun Xcsp3.Builder.postSupportTable(vars: IntArray, text: String) {
    val arity = vars.size
    val tpl = supportTemplateFor(text) { buildSupportTemplate(arity, text) }
    if (tpl.triviallySat) return
    factors.add(Table(xs = vars, tuples = tpl.tuples, hi = tpl.hi))
}

private fun Xcsp3.Builder.buildSupportTemplate(arity: Int, text: String): SupportTemplate {
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
    // Ground (all points) ⇒ no upper-bound array, keeping the fast path byte-identical.
    val short = rows.lo.indices.any { rows.lo[it] != rows.hi[it] }
    return SupportTemplate(triviallySat = false, tuples = rows.lo, hi = if (short) rows.hi else null)
}

/** Post a negative `<conflicts>` table as one nogood clause per forbidden tuple — the disjunction of
 *  each column "differing" from its cell: `x ≠ v` for a point, `x < lo ∨ x > hi` for a range, and
 *  nothing for a `*` (it forbids regardless of that variable). Never materializes the complement. */
internal fun Xcsp3.Builder.postConflictClauses(vars: IntArray, rows: ShortRows) {
    val arity = vars.size
    val n = rows.lo.size / arity
    for (r in 0 until n) {
        val lits = IntArrayList()
        for (c in 0 until arity) {
            val lo = rows.lo[r * arity + c]
            val hiC = rows.hi[r * arity + c]
            when {
                lo == Long.MIN_VALUE && hiC == Long.MAX_VALUE -> Unit

                // `*`: forbids regardless — omit
                lo == hiC -> lits.add(
                    Lit.negate(reifyLinear(intArrayOf(1), intArrayOf(vars[c]), LinearOp.EQ, lo.toInt())),
                )

                else -> {
                    lits.add(reifyLinear(intArrayOf(1), intArrayOf(vars[c]), LinearOp.LE, (lo - 1).toInt())) // x < lo
                    lits.add(reifyLinear(intArrayOf(1), intArrayOf(vars[c]), LinearOp.GE, (hiC + 1).toInt())) // x > hi
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

/** Flat short-tuple rows: [lo]/[hi] hold each cell's inclusive interval bounds row-major (equal for a
 *  point value; `[MIN, MAX]` for a `*` wildcard). */
internal class ShortRows(val lo: LongArray, val hi: LongArray)

/** Parse `<supports>`/`<conflicts>` tuple text into short rows, one row per written tuple: a `*`
 *  column is `[MIN, MAX]`, a `lo..hi` column is that interval, everything else is a point `[v, v]`.
 *  Unary tables use the bare-value form (`0 1 2`, no parentheses). */
internal fun Xcsp3.Builder.parseShortRows(text: String, arity: Int): ShortRows {
    val lo = LongArrayList()
    val hi = LongArrayList()

    // A cell is read from the source in place (no substring per field): a `*` wildcard, a `lo..hi`
    // interval, or a point value — the parse of a multi-MB table dominated the ingestion, so it must
    // not allocate a MatchResult per tuple nor a String per field.
    fun addCell(from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (b - a == 1 && text[a] == '*') {
            lo.add(Long.MIN_VALUE)
            hi.add(Long.MAX_VALUE)
            return
        }
        // Single pass: parse the first integer, stopping at the first non-digit. If that is `..` the cell
        // is a `lo..hi` interval; otherwise it is a point value already fully read. A point cell (the bulk
        // of a numeric table) is thus scanned once, not twice — parsing digits and separately hunting for
        // `..` doubled the per-cell work on a multi-MB table.
        var p = a
        val neg = p < b && text[p] == '-'
        if (neg || (p < b && text[p] == '+')) p++
        require(p < b) { "empty integer in table tuple" }
        var v = 0L
        while (p < b && text[p] in '0'..'9') {
            v = v * 10 + (text[p] - '0')
            p++
        }
        val first = if (neg) -v else v
        if (p >= b) {
            lo.add(first)
            hi.add(first)
        } else {
            require(p + 1 < b && text[p] == '.' && text[p + 1] == '.') { "non-digit '${text[p]}' in table tuple" }
            lo.add(first)
            hi.add(parseLongIn(text, p + 2, b))
        }
    }

    // Bound the row count so a pathologically large table (millions of tuples) fails cleanly here
    // rather than exhausting the heap while building the row arrays. Interval/`*` cells do not expand,
    // so this counts written tuples directly.
    fun capRows() {
        if (lo.size / arity > negTableCap) throw UnsupportedXcsp3Exception("table exceeds cap ($negTableCap rows)")
    }

    val n = text.length
    if (arity == 1 && '(' !in text) {
        var i = 0
        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            addCell(start, i)
            capRows()
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
            capRows()
        }
    }
    return ShortRows(lo.toLongArray(), hi.toLongArray())
}

/** Parse a base-10 [Long] from `text[from, to)` (an optional sign then digits), without a substring. */
private fun parseLongIn(text: String, from: Int, to: Int): Long {
    var a = from
    val neg = a < to && text[a] == '-'
    if (neg || (a < to && text[a] == '+')) a++
    require(a < to) { "empty integer in table tuple" }
    var v = 0L
    while (a < to) {
        val c = text[a]
        require(c in '0'..'9') { "non-digit '$c' in table tuple" }
        v = v * 10 + (c - '0')
        a++
    }
    return if (neg) -v else v
}

/** Invoke [cell] with each trimmed comma-separated field of every `(...)` group in [text], then [endRow]
 *  after the group — one linear scan, no per-tuple Regex match. */
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
