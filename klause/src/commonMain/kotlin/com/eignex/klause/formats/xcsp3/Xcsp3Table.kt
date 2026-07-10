package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

// `<extension>` (positive/negative table) lowering for the XCSP3 front-end — split out of Xcsp3.kt.
//
// A tuple column may be a value, a `*` wildcard, or a `lo..hi` range. Positive tables carry these as
// short-support cells (each written tuple is one row — no Cartesian expansion), and negative tables
// lower to one nogood clause per forbidden tuple. Both avoid the blow-up the old expansion capped.

internal fun Xcsp3.Builder.extension(e: XmlElement) {
    val vars = listVars(e)
    val supports = e.child("supports")?.textContent?.trim()
    val conflicts = e.child("conflicts")?.textContent?.trim()
    when {
        // No allowed tuple ⇒ the constraint (hence the instance) is unsatisfiable.
        supports != null && supports.isEmpty() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

        // No forbidden tuple ⇒ trivially satisfied; post nothing.
        conflicts != null && conflicts.isEmpty() -> Unit

        supports != null -> postSupportTable(vars, supports)

        conflicts != null -> postConflictClauses(vars, conflicts)

        else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
    }
}

/** Post a positive `<supports>` table as a [Table] factor. `*`/range columns become short-support
 *  cells (an interval `[lo, hi]`; a `*` is `[MIN, MAX]`), so each written tuple is exactly one row.
 *  A fully unbounded row matches every assignment, so the whole constraint is trivially satisfied. */
internal fun Xcsp3.Builder.postSupportTable(vars: IntArray, text: String) {
    val arity = vars.size
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
        if (allFree) return
    }
    // Ground (all points) ⇒ no upper-bound array, keeping the fast path byte-identical.
    val short = rows.lo.indices.any { rows.lo[it] != rows.hi[it] }
    factors.add(Table(xs = vars, tuples = rows.lo, hi = if (short) rows.hi else null))
}

/** Post a negative `<conflicts>` table as one nogood clause per forbidden tuple — the disjunction of
 *  each column "differing" from its cell: `x ≠ v` for a point, `x < lo ∨ x > hi` for a range, and
 *  nothing for a `*` (it forbids regardless of that variable). Never materializes the complement. */
internal fun Xcsp3.Builder.postConflictClauses(vars: IntArray, text: String) {
    val arity = vars.size
    val rows = parseShortRows(text, arity)
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
    val t = text.trim()
    val lo = LongArrayList()
    val hi = LongArrayList()
    fun addCell(tok: String) {
        when {
            tok == "*" -> {
                lo.add(Long.MIN_VALUE)
                hi.add(Long.MAX_VALUE)
            }

            ".." in tok -> tok.split("..").let {
                lo.add(it[0].toLong())
                hi.add(it[1].toLong())
            }

            else -> {
                val v = tok.toLong()
                lo.add(v)
                hi.add(v)
            }
        }
    }

    // Bound the row count so a pathologically large table (millions of tuples) fails cleanly here
    // rather than exhausting the heap while building the row arrays. Interval/`*` cells do not expand,
    // so this counts written tuples directly.
    fun capRows() {
        if (lo.size / arity > negTableCap) throw UnsupportedXcsp3Exception("table exceeds cap ($negTableCap rows)")
    }
    if (arity == 1 && '(' !in t) {
        for (tok in t.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            addCell(tok)
            capRows()
        }
    } else {
        for (m in Regex("""\(([^)]*)\)""").findAll(t)) {
            val row = m.groupValues[1].split(",").map { it.trim() }
            if (row.size != arity) throw UnsupportedXcsp3Exception("tuple arity ${row.size} != $arity")
            for (tok in row) addCell(tok)
            capRows()
        }
    }
    return ShortRows(lo.toLongArray(), hi.toLongArray())
}
